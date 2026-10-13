package com.liangledecha.chachacalendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 月历与周历的共享展示层；保持本地读取，无网络来源和常驻后台服务。 */
public abstract class CalendarGridWidgetProvider extends AppWidgetProvider {
    /** 广播与点击分别使用独立身份，避免系统把不同类型或旧组件的待处理意图合并。 */
    private static final String ACTION_REFRESH = "com.liangledecha.chachacalendar.REFRESH_GRID_WIDGET";
    private static final String ACTION_MIDNIGHT = "com.liangledecha.chachacalendar.GRID_WIDGET_MIDNIGHT";
    private static final int MIDNIGHT_REQUEST = 6100;
    private static final int HEADER_REQUEST = 6200, ADD_REQUEST = 6300, REFRESH_REQUEST = 6400, LIST_REQUEST = 6500;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA);
    private static final DateTimeFormatter WEEK_FORMAT = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);

    /** 两个注册入口只选择展示类型，其余布局和生命周期保持同一实现。 */
    protected abstract boolean showsMonth();

    /** 首次添加或系统周期刷新时只处理该入口下的实例。 */
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids);
        scheduleMidnight(context, manager);
    }

    /** 行列使用权重分配实际空间，尺寸变化后重建远程视图和列表宽度。 */
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, new int[]{id});
    }

    /** 最后一个实例移除后撤销日期刷新，避免组件不存在时继续唤醒应用。 */
    @Override public void onDeleted(Context context, int[] ids) {
        scheduleMidnight(context, AppWidgetManager.getInstance(context));
    }

    /** 系统重启、时间调整和安装覆盖后恢复日期；手动刷新同样更新两种入口。 */
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action) || ACTION_MIDNIGHT.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) updateInstalled(context);
    }

    /** 由现有应用刷新链调用；即使旧时钟组件没有实例，新入口也能收到数据变化。 */
    static void updateInstalled(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        CalendarGridWidgetProvider[] providers = {new MonthCalendarWidgetProvider(), new WeekCalendarWidgetProvider()};
        for (CalendarGridWidgetProvider provider : providers) {
            provider.update(context, manager, ids(context, manager, provider.getClass()));
        }
        scheduleMidnight(context, manager);
    }

    /** 一次刷新使用同一个日期快照，防止跨午夜时标题与日期格属于不同日期。 */
    private void update(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        LocalDate today = LocalDate.now();
        for (int id : ids) {
            manager.updateAppWidget(id, createViews(context, id, today));
            if (!showsMonth()) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_event_list);
        }
    }

    /** 展示组装独立于提交操作，便于用同一布局验证六行月份和字体缩放。 */
    final RemoteViews createViews(Context context, int id, LocalDate today) {
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean showLunar = settings.getBoolean("show_lunar_dates", true);
        List<LocalDate> dates = showsMonth() ? CalendarWidgetDates.month(today) : CalendarWidgetDates.week(today);
        RemoteViews views = new RemoteViews(context.getPackageName(), showsMonth()
                ? R.layout.widget_month_calendar : R.layout.widget_week_calendar);
        String title = showsMonth() ? today.format(MONTH_FORMAT)
                : context.getString(R.string.widget_week_range, dates.get(0).format(WEEK_FORMAT),
                dates.get(dates.size() - 1).format(WEEK_FORMAT));
        views.setTextViewText(R.id.grid_widget_title, title);
        views.setTextViewText(R.id.grid_widget_subtitle, context.getString(showsMonth()
                ? R.string.widget_month_subtitle : R.string.widget_week_subtitle));
        populateCalendar(context, views, dates, today, showLunar);
        bindActions(context, views, id);
        if (!showsMonth()) bindTodos(context, views, settings, id);
        return views;
    }

    /** 有限的日期格用原生远程子布局组成，文字仍可访问且随系统字号缩放。 */
    private static void populateCalendar(Context context, RemoteViews views, List<LocalDate> dates,
                                         LocalDate today, boolean showLunar) {
        views.removeAllViews(R.id.grid_widget_calendar);
        String[] weekdays = context.getResources().getStringArray(R.array.widget_weekdays);
        RemoteViews heading = new RemoteViews(context.getPackageName(), R.layout.widget_weekday_row);
        for (int column = 0; column < CalendarWidgetDates.DAYS_PER_WEEK; column++) {
            RemoteViews label = new RemoteViews(context.getPackageName(), R.layout.widget_weekday_cell);
            label.setTextViewText(R.id.grid_weekday, weekdays[column]);
            label.setTextColor(R.id.grid_weekday, context.getColor(column >= 5
                    ? R.color.grid_widget_weekend : R.color.grid_widget_secondary));
            heading.addView(R.id.grid_widget_row, label);
        }
        views.addView(R.id.grid_widget_calendar, heading);
        for (int offset = 0; offset < dates.size(); offset += CalendarWidgetDates.DAYS_PER_WEEK) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_calendar_row);
            for (int column = 0; column < CalendarWidgetDates.DAYS_PER_WEEK; column++) {
                row.addView(R.id.grid_widget_row, dayCell(context, dates.get(offset + column), today, showLunar));
            }
            views.addView(R.id.grid_widget_calendar, row);
        }
    }

    /** 周末底色和当天圆角高亮作用于整个日期格，农历始终处在数字下方。 */
    private static RemoteViews dayCell(Context context, LocalDate date, LocalDate today, boolean showLunar) {
        RemoteViews cell = new RemoteViews(context.getPackageName(), R.layout.widget_calendar_cell);
        boolean weekend = CalendarWidgetDates.isWeekend(date), current = date.equals(today);
        boolean otherMonth = date.getMonthValue() != today.getMonthValue() || date.getYear() != today.getYear();
        int textColor = current ? R.color.grid_widget_today_text : weekend ? R.color.grid_widget_weekend
                : otherMonth ? R.color.grid_widget_secondary : R.color.grid_widget_text;
        int background = current ? (weekend ? R.drawable.grid_widget_today_weekend : R.drawable.grid_widget_today)
                : weekend ? R.drawable.grid_widget_weekend : R.drawable.grid_widget_day;
        cell.setInt(R.id.grid_day_cell, "setBackgroundResource", background);
        cell.setTextViewText(R.id.grid_day_number, Integer.toString(date.getDayOfMonth()));
        cell.setTextColor(R.id.grid_day_number, context.getColor(textColor));
        cell.setTextColor(R.id.grid_day_lunar, context.getColor(current ? R.color.grid_widget_today_text
                : weekend ? R.color.grid_widget_weekend : R.color.grid_widget_secondary));
        String lunar = showLunar ? LunarDateUtils.compact(date) : "";
        cell.setTextViewText(R.id.grid_day_lunar, lunar);
        cell.setViewVisibility(R.id.grid_day_lunar, showLunar ? View.VISIBLE : View.GONE);
        cell.setInt(R.id.grid_day_number, "setGravity", showLunar
                ? Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM : Gravity.CENTER);
        // 相邻月份只使用次级字色，不叠加透明度；无障碍描述始终包含完整年月日。
        cell.setContentDescription(R.id.grid_day_cell, context.getString(R.string.widget_day_description,
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                current ? context.getString(R.string.widget_today_label) : "", lunar));
        return cell;
    }

    /** 月历区打开今天所在月份，待办区打开日程；加号仍使用现有创建表单。 */
    private void bindActions(Context context, RemoteViews views, int id) {
        Intent calendar = openActivity(context).putExtra(MainActivity.EXTRA_OPEN_MONTH, true);
        PendingIntent open = CalendarWidgetProvider.activityPendingIntent(context, HEADER_REQUEST, calendar,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.grid_widget_header, open);
        views.setOnClickPendingIntent(R.id.grid_widget_calendar, open);
        Intent create = openActivity(context).putExtra(MainActivity.EXTRA_OPEN_CREATE, true);
        views.setOnClickPendingIntent(R.id.grid_widget_add, CalendarWidgetProvider.activityPendingIntent(
                context, ADD_REQUEST, create, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent refresh = new Intent(context, getClass()).setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.grid_widget_refresh, PendingIntent.getBroadcast(context, REFRESH_REQUEST + id,
                refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    /** 延用现有原生滚动列表，适配器地址包含类型和实例，避免与旧组件共享错误缓存。 */
    private static void bindTodos(Context context, RemoteViews views, SharedPreferences settings, int id) {
        Intent service = new Intent(context, CalendarWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(CalendarWidgetService.EXTRA_TODOS_ONLY, true);
        service.setData(Uri.parse("chachacalendar://widget-items/week-todos/" + id + "/"
                + settings.getLong("widget_binding_generation", 0L)));
        views.setRemoteAdapter(R.id.widget_event_list, service);
        views.setEmptyView(R.id.widget_event_list, R.id.widget_empty);
        views.setScrollPosition(R.id.widget_event_list, 0);
        Intent agenda = openActivity(context).putExtra(MainActivity.EXTRA_OPEN_AGENDA, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent open = CalendarWidgetProvider.activityPendingIntent(context, LIST_REQUEST + id, agenda, flags);
        views.setPendingIntentTemplate(R.id.widget_event_list, open);
        views.setOnClickPendingIntent(R.id.widget_empty, open);
        views.setOnClickPendingIntent(R.id.grid_widget_todos_title, open);
    }

    /** 页面跳转统一复用现有活动，避免桌面重复点击创建多个页面。 */
    private static Intent openActivity(Context context) {
        return new Intent(context, MainActivity.class)
                .setData(Uri.parse("chachacalendar://grid-widget/open"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** 查询各入口的实例，不把一种布局提交给另一种小组件。 */
    private static int[] ids(Context context, AppWidgetManager manager, Class<?> provider) {
        return manager.getAppWidgetIds(new ComponentName(context, provider));
    }

    /** 一天一次的非精确定时器补充系统日期广播；不唤醒休眠设备、不要求额外权限。 */
    private static void scheduleMidnight(Context context, AppWidgetManager manager) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent tick = PendingIntent.getBroadcast(context, MIDNIGHT_REQUEST,
                new Intent(context, MonthCalendarWidgetProvider.class).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean installed = ids(context, manager, MonthCalendarWidgetProvider.class).length > 0
                || ids(context, manager, WeekCalendarWidgetProvider.class).length > 0;
        if (!installed) { alarms.cancel(tick); return; }
        long next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        alarms.set(AlarmManager.RTC, next, tick);
    }
}
