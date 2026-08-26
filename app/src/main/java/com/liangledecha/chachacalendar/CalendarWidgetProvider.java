package com.liangledecha.chachacalendar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 桌面日历小组件接收器。
 *
 * <p>负责把系统时间、当天日期、最近事项和系统天气入口写入桌面组件。
 * 时间文本由系统时钟控件自行刷新，本类不启动分钟级定时任务，因此更省电。</p>
 */
public final class CalendarWidgetProvider extends AppWidgetProvider {
    /** 用户点击组件刷新按钮时发送的应用内广播动作。 */
    private static final String ACTION_REFRESH = "com.liangledecha.chachacalendar.REFRESH_WIDGET";
    /** 小组件默认高度；桌面未返回尺寸时用它保证默认显示四条。 */
    private static final int DEFAULT_HEIGHT_DP = 220;
    /** 两组五级字号。第一级保持旧版默认，第五级适合组件铺满整个桌面。 */
    private static final float[] TIME_SIZES = {24, 28, 32, 36, 40};
    private static final float[] DATE_SIZES = {13, 15, 17, 19, 21};
    private static final float[] WEATHER_SIZES = {11, 12, 13, 14, 15};
    private static final float[] EVENT_SIZES = {13, 15, 17, 19, 21};
    /** 各事项字号对应的两行最小高度，既防止遮挡，也用于计算容纳数量。 */
    private static final int[] EVENT_ROW_HEIGHTS_DP = {32, 38, 43, 48, 54};
    /** 系统要求刷新某一批小组件时，把工作统一交给完整刷新方法。 */
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { updateAll(context, manager, ids); }

    /** 用户拖动改变组件尺寸后，只刷新被调整的实例并重新计算可显示条数。 */
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle newOptions) {
        updateAll(context, manager, new int[]{appWidgetId});
    }

    /** 接收日期变化和时区变化广播，并在必要时重新计算倒计时。 */
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (Intent.ACTION_DATE_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || ACTION_REFRESH.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            updateAll(context, manager, manager.getAppWidgetIds(new ComponentName(context, CalendarWidgetProvider.class)));
            // 农历年度重复无法用系统公历重复规则表达，日期变化时把下一次真实公历落点补写给系统日历。
            if (Intent.ACTION_DATE_CHANGED.equals(action) && SystemCalendarSync.hasPermission(context)) {
                PendingResult pending = goAsync();
                new Thread(() -> {
                    try {
                        EventStore store = new EventStore(context);
                        for (Event event : store.all()) if (event.lunarBased && Event.YEARLY.equals(event.repeatRule)) {
                            long systemId = SystemCalendarSync.sync(context, event);
                            store.setSystemEventId(event.id, systemId);
                        }
                        store.close();
                    } finally { pending.finish(); }
                }, "更新农历系统日历落点").start();
            }
        }
    }

    /**
     * 刷新指定编号的全部桌面小组件实例。
     *
     * @param context 用于读取数据库和创建页面跳转意图
     * @param manager 系统小组件管理器
     * @param ids 需要更新的小组件实例编号数组
     */
    public static void updateAll(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null) return;
        // 今天用于格式化日期，并作为显示规则和倒计时的计算基准。
        LocalDate today = LocalDate.now();
        // 只读取今天符合提前显示规则的事项，避免组件显示过远内容。
        List<Event> events = new EventStore(context).visible();
        // 使用中国地区格式生成“月、日、星期”文字。
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA);
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        int background = backgroundForTransparency(preferences.getInt("widget_transparency", 15));
        int headerLevel = levelIndex(preferences.getInt("widget_header_font_level", 1));
        int eventLevel = levelIndex(preferences.getInt("widget_event_font_level", 1));
        boolean showLunar = preferences.getBoolean("show_lunar_dates", true);
        // 每个桌面实例都要单独创建远程视图并提交给系统。
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.calendar_widget);
            views.setInt(R.id.widget_root, "setBackgroundResource", background);
            String specialDate = specialDateLabel(context, preferences, today);
            String todayText = today.format(dateFormat) + (!specialDate.isEmpty() ? " · " + specialDate
                    : showLunar ? " · 农历" + LunarDateUtils.compact(today) : "");
            views.setTextViewText(R.id.widget_date, todayText);
            views.setTextViewTextSize(R.id.widget_time, TypedValue.COMPLEX_UNIT_SP, TIME_SIZES[headerLevel]);
            views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, DATE_SIZES[headerLevel]);
            views.setTextViewTextSize(R.id.widget_weather, TypedValue.COMPLEX_UNIT_SP, WEATHER_SIZES[headerLevel]);
            views.setViewVisibility(R.id.widget_empty, events.isEmpty() ? View.VISIBLE : View.GONE);
            views.removeAllViews(R.id.widget_event_container);
            int limit = itemLimit(manager.getAppWidgetOptions(id), headerLevel, eventLevel);
            for (int index = 0; index < Math.min(limit, events.size()); index++) {
                RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_event_row);
                row.setTextViewText(R.id.widget_item_text, widgetText(events.get(index), today));
                row.setTextColor(R.id.widget_item_text, events.get(index).isOverdue() ? Color.rgb(210,55,67) : Color.rgb(32,39,55));
                row.setTextViewTextSize(R.id.widget_item_text, TypedValue.COMPLEX_UNIT_SP, EVENT_SIZES[eventLevel]);
                row.setInt(R.id.widget_item_text, "setMinHeight", dp(context, EVENT_ROW_HEIGHTS_DP[eventLevel]));
                views.addView(R.id.widget_event_container, row);
            }
            // 点击组件主体时打开主日历页面。
            Intent openCalendar = new Intent(context, MainActivity.class);
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1000 + id, openCalendar, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            // 刷新按钮只重新读取本地数据库，不启动网络请求或常驻后台任务。
            Intent refresh = new Intent(context, CalendarWidgetProvider.class).setAction(ACTION_REFRESH);
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 2000 + id, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            // 如果系统公开了天气应用入口，天气文字可点击；否则只显示不可用状态。
            Intent weather = SystemWeatherBridge.findWeatherApp(context);
            if (weather != null) {
                views.setTextViewText(R.id.widget_weather, "天气 · 系统来源");
                views.setOnClickPendingIntent(R.id.widget_weather, PendingIntent.getActivity(context, 3000 + id, weather, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                views.setTextViewText(R.id.widget_weather, "天气 · 系统未开放");
            }
            manager.updateAppWidget(id, views);
        }
    }

    /** 把一条事项压缩成小组件中的两行信息，待办会在类型后显示分钟精度时间。 */
    private static String widgetText(Event event, LocalDate today) {
        LocalDate next = event.nextDate(today);
        long days = event.daysUntil(today);
        String time = event.timeLabel().isEmpty() ? "" : " · " + event.timeLabel();
        String countdown = event.isOverdue() ? "已过期" : days == 0 ? "今天" : days > 0 ? days + " 天后" : "已过 " + (-days) + " 天";
        return "● " + event.title + " · " + event.type + time + "\n"
                + event.displayDate(next) + " · " + countdown;
    }

    /** 把设置中一至五级转换为数组使用的零至四下标。 */
    private static int levelIndex(int level) { return Math.max(0, Math.min(4, level - 1)); }

    /**
     * 根据桌面报告的当前最小高度计算事项容量。默认尺寸固定四条，增加高度后逐行增加，
     * 最多二十条以控制远程视图传输体积。
     */
    private static int itemLimit(Bundle options, int headerLevel, int eventLevel) {
        int height = options == null ? DEFAULT_HEIGHT_DP : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP);
        int headerReserve = 88 + headerLevel * 7;
        int calculated = (height - headerReserve) / EVENT_ROW_HEIGHTS_DP[eventLevel];
        return Math.max(4, Math.min(20, calculated));
    }

    /** 把密度无关像素转换为当前设备实际像素，供远程视图的最小高度接口使用。 */
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    /** 按“法定假日、节气、普通节日”的优先顺序生成小组件顶部的今日标签。 */
    private static String specialDateLabel(Context context, SharedPreferences preferences, LocalDate today) {
        String label = "";
        if (preferences.getBoolean("show_festivals", true)) {
            List<String> names = CalendarCultureUtils.festivalNames(today); if (!names.isEmpty()) label = names.get(0);
        }
        if (preferences.getBoolean("show_solar_terms", true)) {
            String term = CalendarCultureUtils.solarTerm(today); if (!term.isEmpty()) label = term;
        }
        if (preferences.getBoolean("show_public_holidays", true)) {
            HolidayRepository.HolidayInfo holiday = HolidayRepository.year(context, today.getYear()).get(today);
            if (holiday != null) label = holiday.label();
        }
        return label;
    }

    /** 根据用户选择的透明比例返回对应的预制圆角背景，不在刷新时生成位图。 */
    private static int backgroundForTransparency(int transparency) {
        if (transparency >= 60) return R.drawable.widget_bg_40;
        if (transparency >= 45) return R.drawable.widget_bg_55;
        if (transparency >= 30) return R.drawable.widget_bg_70;
        if (transparency >= 15) return R.drawable.widget_bg_85;
        return R.drawable.widget_bg_100;
    }
}
