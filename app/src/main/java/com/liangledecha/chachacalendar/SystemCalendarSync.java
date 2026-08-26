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

    /** 检查应用是否同时拥有读取日历列表和写入日历事件的权限。 */
    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 新增或更新一条系统日历事件，并返回系统事件编号。
     * 已完成待办会从系统日历中移除，以免继续触发提醒。
     */
    public static long sync(Context context, Event event) {
        if (!hasPermission(context)) return -1;
        if (event.isTodo() && event.completed) {
            delete(context, event.systemEventId);
            return -1;
        }
        long calendarId = findWritableCalendar(context.getContentResolver());
        if (calendarId < 0) return -1;

        ContentValues values = buildEventValues(event, calendarId);
        ContentResolver resolver = context.getContentResolver();
        long eventId = event.systemEventId;
        try {
            if (eventId > 0) {
                Uri existing = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
                if (resolver.update(existing, values, null, null) == 0) eventId = -1;
            }
            if (eventId <= 0) {
                Uri inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, values);
                if (inserted == null) return -1;
                eventId = ContentUris.parseId(inserted);
            }
            replaceReminder(resolver, eventId);
            return eventId;
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 某些厂商日历会拒绝不受支持的字段或临时撤回权限；本地事项仍然保留。
            return -1;
        }
    }

    /** 删除与本地事项对应的系统日历事件；没有编号时直接返回。 */
    public static void delete(Context context, long systemEventId) {
        if (!hasPermission(context) || systemEventId <= 0) return;
        try {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, systemEventId), null, null);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 系统日历中的事件可能已被用户手动删除，重复删除不影响本地数据。
        }
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
    private static ContentValues buildEventValues(Event event, long calendarId) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, event.title);
        values.put(CalendarContract.Events.DESCRIPTION, SOURCE + "\n类型：" + event.type + "\n本地编号：" + event.id);
        values.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
        values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED);
        values.put(CalendarContract.Events.HAS_ALARM, 1);

        LocalDate occurrenceDate = event.nextDate(LocalDate.now());
        boolean timedTodo = event.isTodo() && event.time != null;
        if (timedTodo) {
            ZoneId zone = ZoneId.systemDefault();
            LocalDateTime start = LocalDateTime.of(occurrenceDate, event.time);
            values.put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli());
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.getId());
            values.put(CalendarContract.Events.ALL_DAY, 0);
        } else {
            // 安卓全天事件按协调世界时零点保存，结束日期使用不包含在事件内的次日。
            values.put(CalendarContract.Events.DTSTART, occurrenceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            values.put(CalendarContract.Events.ALL_DAY, 1);
        }

        // 系统日历的年度和月度规则按公历解释；农历事项只写入下一次真实落点并由日期变化广播滚动更新。
        String recurrence = event.lunarBased && (Event.YEARLY.equals(event.repeatRule) || Event.MONTHLY.equals(event.repeatRule))
                ? null : recurrenceRule(event.repeatRule);
        if (recurrence == null) {
            long end;
            if (timedTodo) {
                LocalDateTime start = LocalDateTime.of(occurrenceDate, event.time == null ? LocalTime.MIDNIGHT : event.time);
                end = start.plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } else {
                end = occurrenceDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            values.put(CalendarContract.Events.DTEND, end);
            values.putNull(CalendarContract.Events.DURATION);
            values.putNull(CalendarContract.Events.RRULE);
        } else {
            values.put(CalendarContract.Events.RRULE, recurrence);
            values.put(CalendarContract.Events.DURATION, timedTodo ? "PT30M" : "P1D");
            values.putNull(CalendarContract.Events.DTEND);
        }
        return values;
    }

    /** 把应用内部重复规则转换为系统日历使用的重复表达式。 */
    private static String recurrenceRule(String repeatRule) {
        if (Event.DAILY.equals(repeatRule)) return "FREQ=DAILY";
        if (Event.WEEKLY.equals(repeatRule)) return "FREQ=WEEKLY";
        if (Event.MONTHLY.equals(repeatRule)) return "FREQ=MONTHLY";
        if (Event.YEARLY.equals(repeatRule)) return "FREQ=YEARLY";
        return null;
    }

    /**
     * 为系统事件建立“发生时提醒”。先删除旧提醒可以防止每次编辑都叠加一条通知。
     * 具体的声音、震动、横幅和状态栏样式由系统日历通知渠道决定。
     */
    private static void replaceReminder(ContentResolver resolver, long eventId) {
        try {
            resolver.delete(CalendarContract.Reminders.CONTENT_URI,
                    CalendarContract.Reminders.EVENT_ID + "=?", new String[]{Long.toString(eventId)});
            ContentValues reminder = new ContentValues();
            reminder.put(CalendarContract.Reminders.EVENT_ID, eventId);
            reminder.put(CalendarContract.Reminders.MINUTES, 0);
            reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 少数日历账户不支持弹窗提醒；事件本身仍然同步成功。
        }
    }
}
