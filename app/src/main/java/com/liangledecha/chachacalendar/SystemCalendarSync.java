package com.liangledecha.chachacalendar;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.content.SharedPreferences;

/**
 * 茶茶日历与安卓系统日历提供程序之间的同步桥梁。
 *
 * <p>本类不自行发送通知，而是把事项和提醒写入用户已有的可写系统日历。
 * 后续的横幅、状态栏、声音和震动由手机的系统日历应用及其通知渠道设置负责，
 * 因而能更好地适配不同厂商，同时避免茶茶日历常驻后台。</p>
 */
public final class SystemCalendarSync {
    /** 权限请求编号，主页面用它识别系统日历授权结果。 */
    public static final int PERMISSION_REQUEST = 2401;
    /** 写入系统事件说明字段的来源标记，方便用户在系统日历中识别数据来源。 */
    private static final String SOURCE = "由茶茶日历同步";

    /** 工具类不需要创建对象。 */
    private SystemCalendarSync() { }

    static int mode(Context context) {
        return CalendarSyncPolicy.normalize(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("calendar_sync_mode", 0));
    }

    static long selectedCalendar(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("calendar_sync_source", -1);
    }

    static final class CalendarChoice {
        final long id;
        final String label;
        final boolean writable;
        CalendarChoice(long id, String label, boolean writable) { this.id = id; this.label = label; this.writable = writable; }
    }

    static List<CalendarChoice> calendars(Context context) {
        List<CalendarChoice> result = new ArrayList<>();
        if (!hasPermission(context)) return result;
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{"_id", "calendar_displayName", "account_name", "calendar_access_level"},
                "visible=1 AND calendar_access_level>=?", new String[]{Integer.toString(CalendarContract.Calendars.CAL_ACCESS_READ)}, "_id ASC")) {
            while (cursor != null && cursor.moveToNext()) result.add(new CalendarChoice(cursor.getLong(0), cursor.getString(1) + "（" + cursor.getString(2) + "）",
                    cursor.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR));
        } catch (SecurityException | IllegalArgumentException ignored) { }
        return result;
    }

    /** 检查应用是否同时拥有读取日历列表和写入日历事件的权限。 */
    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 新增或更新一条系统日历事件，并返回系统事件编号。
     * 已完成待办会从系统日历中移除，以免继续触发提醒。
     */
    public static synchronized long sync(Context context, Event event) {
        if (!CalendarSyncPolicy.sends(mode(context)) || !hasPermission(context)) return event.systemEventId;
        if (event.supportsCompletion() && event.completed) {
            delete(context, event.systemEventId);
            return -1;
        }
        long calendarId = mode(context) == CalendarSyncPolicy.BOTH ? selectedCalendar(context) : findWritableCalendar(context.getContentResolver());
        if (calendarId < 0) return event.systemEventId;

        boolean calendarReminder = LocalReminder.source(context) == LocalReminder.SYSTEM_CALENDAR;
        ContentValues values = buildEventValues(event, calendarId, calendarReminder);
        ContentResolver resolver = context.getContentResolver();
        long eventId = event.systemEventId;
        try {
            if (eventId > 0) {
                // 双向模式下先核对上次快照；系统有未接入修改时，不得用本地旧值覆盖。
                if (mode(context) == CalendarSyncPolicy.BOTH) {
                    String actual = providerFingerprint(resolver, eventId);
                    String previous = snapshots(context).getString("remote:" + eventId, null);
                    if (previous == null || !previous.equals(actual)) return eventId;
                }
                Uri existing = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
                values.remove(CalendarContract.Events.CALENDAR_ID);
                if (resolver.update(existing, values, null, null) == 0) eventId = -1;
            }
            if (eventId <= 0) {
                values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
                Uri inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, values);
                if (inserted == null) return -1;
                eventId = ContentUris.parseId(inserted);
            }
            replaceReminder(resolver, eventId, calendarReminder);
            remember(context, eventId, event);
            return eventId;
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 某些厂商日历会拒绝不受支持的字段或临时撤回权限；本地事项仍然保留。
            // 如果事件已创建而快照读取失败，仍保留新编号，避免下次同步重复插入。
            return eventId;
        }
    }

    /** 删除与本地事项对应的系统日历事件；没有编号时直接返回。 */
    public static void delete(Context context, long systemEventId) {
        if (!CalendarSyncPolicy.sends(mode(context)) || !hasPermission(context) || systemEventId <= 0) return;
        try {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, systemEventId), null, null);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 系统日历中的事件可能已被用户手动删除，重复删除不影响本地数据。
        }
    }

    private static final String[] IMPORT_COLUMNS = {"_id", "title", "dtstart", "dtend", "allDay", "eventTimezone", "rrule", "rdate", "exdate", "exrule", "original_id", "deleted", "eventStatus", "description"};

    private static SharedPreferences snapshots(Context context) {
        return context.getSharedPreferences("system_calendar_links", Context.MODE_PRIVATE);
    }

    private static String fingerprint(Cursor cursor) {
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < IMPORT_COLUMNS.length; i++) {
            String value = cursor.isNull(i) ? "" : cursor.getString(i);
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    private static String localFingerprint(Event event) {
        // 同步只比较实际本地业务字段，不将设备专属的系统编号当成业务变更。
        return new org.json.JSONArray(java.util.Arrays.asList(event.title, event.date, event.type,
                event.visibilityDays, event.repeatRule, event.time, event.completed, event.completedThrough,
                event.lunarBased, event.lunarMonth, event.lunarDay, event.lunarLeapMonth,
                event.plannedStart, event.plannedEnd)).toString();
    }

    private static String providerFingerprint(ContentResolver resolver, long id) {
        try (Cursor cursor = resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), IMPORT_COLUMNS, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? fingerprint(cursor) : null;
        }
    }

    private static void remember(Context context, long id, Event event) {
        String remote = providerFingerprint(context.getContentResolver(), id);
        if (remote != null) snapshots(context).edit().putString("remote:" + id, remote)
                .putString("local:" + id, localFingerprint(event)).apply();
    }

    /** 仅接入明确选定的日历；冲突和无法无损表示的重复规则保留原数据并报告。 */
    static synchronized String receive(Context context, EventStore store) {
        if (!CalendarSyncPolicy.receives(mode(context))) return "";
        long calendarId = selectedCalendar(context);
        if (!hasPermission(context) || calendarId < 0) return "请先授权并选择接入的系统日历";
        Map<Long, Event> linked = new HashMap<>();
        for (Event event : store.all()) if (event.systemEventId > 0) linked.put(event.systemEventId, event);
        int imported = 0, skipped = 0;
        SharedPreferences state = snapshots(context);
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Events.CONTENT_URI, IMPORT_COLUMNS,
                "calendar_id=?", new String[]{Long.toString(calendarId)}, "_id ASC")) {
            if (cursor == null) return "系统日历暂不可读，本地数据未改动";
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Event event = linked.get(id);
                ContentValues expected = event == null ? null : EventStore.values(event);
                String remote = fingerprint(cursor);
                String previous = state.getString("remote:" + id, null);
                if (event != null && remote.equals(previous)) continue;
                // 首次接入已有本地关联只建立基线，不反向覆盖原始锚点或农历属性。
                if (event != null && previous == null) { remember(context, id, event); continue; }
                if (event != null && !localFingerprint(event).equals(state.getString("local:" + id, null))) { skipped++; continue; }
                boolean deleted = cursor.getInt(11) != 0 || cursor.getInt(12) == CalendarContract.Events.STATUS_CANCELED;
                if (deleted) {
                    if (event != null) {
                        if (store.applyCalendarChange(event, expected, true)) imported++; else skipped++;
                    }
                    continue;
                }
                String rule = CalendarSyncPolicy.repeat(cursor.getString(6));
                boolean unsupported = rule == null || !cursor.isNull(10);
                for (int i = 7; i <= 9; i++) unsupported |= !cursor.isNull(i) && !cursor.getString(i).isEmpty();
                // 日历不表达本地农历和完成进度；不允许反向改写这些事项的锚点。
                if (unsupported || (event != null && (event.lunarBased || event.completedThrough != null))) { skipped++; continue; }
                try {
                    boolean allDay = cursor.getInt(4) != 0;
                    ZoneId zone = allDay ? ZoneOffset.UTC : cursor.isNull(5) || cursor.getString(5).isEmpty() ? ZoneId.systemDefault() : ZoneId.of(cursor.getString(5));
                    LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(2)), zone);
                    LocalDateTime end = cursor.isNull(3) ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(3)), zone);
                    // 多日全天事件没有对应的本地全天区间字段，不能截断为单日。
                    if (allDay && end != null && !end.toLocalDate().equals(start.toLocalDate().plusDays(1))) { skipped++; continue; }
                    String title = cursor.getString(1);
                    if (title == null || title.trim().isEmpty()) title = "未命名日程";
                    boolean timingChanged = event == null;
                    if (event == null) {
                        // 曾由本应用移除的已完成事项不得因提供程序延迟再次出现而被复活。
                        if (previous != null) continue;
                        event = new Event(0, title, start.toLocalDate(), "普通日程", -1, rule,
                                allDay ? null : start.toLocalTime(), false, id, false, 0, 0, false);
                    } else {
                        // 比较上次系统字段而不是“今天的下一次日期”，避免跨年后仅改标题就丢失生日原始年份。
                        boolean changedStart = !java.util.Objects.equals(CalendarSyncPolicy.field(previous, 1), cursor.getString(2))
                                || !java.util.Objects.equals(CalendarSyncPolicy.field(previous, 3), cursor.getString(4))
                                || !java.util.Objects.equals(CalendarSyncPolicy.field(previous, 4), cursor.isNull(5) ? "" : cursor.getString(5));
                        timingChanged = changedStart || !java.util.Objects.equals(CalendarSyncPolicy.field(previous, 2), cursor.isNull(3) ? "" : cursor.getString(3));
                        event.title = title;
                        if (changedStart) {
                            event.date = start.toLocalDate();
                            event.time = allDay || !event.supportsTime() ? null : start.toLocalTime();
                        }
                        event.repeatRule = rule;
                    }
                    if (timingChanged) {
                        if (event.supportsPlanning() && !allDay && end != null && end.isAfter(start)) {
                            event.plannedStart = start; event.plannedEnd = end;
                        } else { event.plannedStart = null; event.plannedEnd = null; }
                    }
                    if (store.applyCalendarChange(event, expected, false)) { remember(context, id, event); imported++; }
                    else skipped++;
                } catch (java.time.DateTimeException error) { skipped++; }
            }
        } catch (SecurityException | IllegalArgumentException error) { return "系统日历读取失败，已有本地事项保留"; }
        return "接入 " + imported + " 条；冲突或不支持的事项 " + skipped + " 条（未覆盖）";
    }

    /** 从系统已存在的日历中选择第一个可写且可见的日历。 */
    private static long findWritableCalendar(ContentResolver resolver) {
        String[] columns = {CalendarContract.Calendars._ID};
        String where = CalendarContract.Calendars.VISIBLE + "=1 AND "
                + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?";
        String[] args = {Integer.toString(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)};
        try (Cursor cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI, columns, where, args,
                CalendarContract.Calendars.IS_PRIMARY + " DESC," + CalendarContract.Calendars._ID + " ASC")) {
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : -1;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return -1;
        }
    }

    /** 把茶茶日历的数据模型转换为系统日历事件字段。 */
    private static ContentValues buildEventValues(Event event, long calendarId, boolean calendarReminder) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, event.title);
        values.put(CalendarContract.Events.DESCRIPTION, SOURCE + "\n类型：" + event.type + "\n本地编号：" + event.id);
        values.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
        values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED);
        values.put(CalendarContract.Events.HAS_ALARM, calendarReminder ? 1 : 0);

        LocalDate occurrenceDate = event.nextReminderDate(LocalDate.now());
        boolean timedEvent = event.supportsTime() && event.time != null;
        LocalDateTime timedStart = event.plannedStart != null ? event.ganttStart(occurrenceDate)
                : LocalDateTime.of(occurrenceDate, event.time == null ? LocalTime.MIDNIGHT : event.time);
        LocalDateTime timedEnd = event.plannedEnd != null ? event.ganttEnd(occurrenceDate) : timedStart.plusMinutes(30);
        if (!timedEnd.isAfter(timedStart)) timedEnd = timedStart.plusMinutes(30);
        if (timedEvent) {
            ZoneId zone = ZoneId.systemDefault();
            values.put(CalendarContract.Events.DTSTART, timedStart.atZone(zone).toInstant().toEpochMilli());
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.getId());
            values.put(CalendarContract.Events.ALL_DAY, 0);
        } else {
            // 安卓全天事件按协调世界时零点保存，结束日期使用不包含在事件内的次日。
            values.put(CalendarContract.Events.DTSTART, occurrenceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            values.put(CalendarContract.Events.ALL_DAY, 1);
        }

        // 系统日历的年度和月度规则按公历解释；农历事项只写入下一次真实落点并由日期变化广播滚动更新。
        String recurrence = event.lunarBased && (Event.YEARLY.equals(event.repeatRule) || Event.MONTHLY.equals(event.repeatRule)
                || Event.QUARTERLY.equals(event.repeatRule) || Event.HALF_YEARLY.equals(event.repeatRule))
                ? null : recurrenceRule(event.repeatRule);
        if (recurrence == null) {
            long end;
            if (timedEvent) {
                end = timedEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } else {
                end = occurrenceDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            values.put(CalendarContract.Events.DTEND, end);
            values.putNull(CalendarContract.Events.DURATION);
            values.putNull(CalendarContract.Events.RRULE);
        } else {
            values.put(CalendarContract.Events.RRULE, recurrence);
            values.put(CalendarContract.Events.DURATION, timedEvent ? Duration.between(timedStart, timedEnd).toString() : "P1D");
            values.putNull(CalendarContract.Events.DTEND);
        }
        return values;
    }

    /** 把应用内部重复规则转换为系统日历使用的重复表达式。 */
    private static String recurrenceRule(String repeatRule) {
        if (Event.DAILY.equals(repeatRule)) return "FREQ=DAILY";
        if (Event.WEEKLY.equals(repeatRule)) return "FREQ=WEEKLY";
        if (Event.MONTHLY.equals(repeatRule)) return "FREQ=MONTHLY";
        if (Event.QUARTERLY.equals(repeatRule)) return "FREQ=MONTHLY;INTERVAL=3";
        if (Event.HALF_YEARLY.equals(repeatRule)) return "FREQ=MONTHLY;INTERVAL=6";
        if (Event.YEARLY.equals(repeatRule)) return "FREQ=YEARLY";
        return null;
    }

    /**
     * 为系统事件建立“发生时提醒”。先删除旧提醒可以防止每次编辑都叠加一条通知。
     * 具体的声音、震动、横幅和状态栏样式由系统日历通知渠道决定。
     */
    private static void replaceReminder(ContentResolver resolver, long eventId, boolean enabled) {
        try {
            resolver.delete(CalendarContract.Reminders.CONTENT_URI,
                    CalendarContract.Reminders.EVENT_ID + "=?", new String[]{Long.toString(eventId)});
            if (!enabled) return;
            ContentValues reminder = new ContentValues();
            reminder.put(CalendarContract.Reminders.EVENT_ID, eventId);
            reminder.put(CalendarContract.Reminders.MINUTES, 0);
            reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 少数日历账户不支持弹窗提醒；事件本身仍然同步成功。
        }
    }

    static boolean hasReminder(Context context, long eventId) {
        if (eventId <= 0 || !hasPermission(context)) return false;
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Reminders.CONTENT_URI,
                new String[]{CalendarContract.Reminders._ID}, CalendarContract.Reminders.EVENT_ID + "=?",
                new String[]{Long.toString(eventId)}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (SecurityException | IllegalArgumentException error) { return false; }
    }
}
