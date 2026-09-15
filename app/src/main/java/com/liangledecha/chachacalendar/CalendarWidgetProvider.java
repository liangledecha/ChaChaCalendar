package com.liangledecha.chachacalendar;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
    /** 两组五级字号。第一级保持旧版默认，第五级适合组件铺满整个桌面。 */
    private static final float[] TIME_SIZES = {24, 28, 32, 36, 40};
    private static final float[] DATE_SIZES = {13, 15, 17, 19, 21};
    private static final float[] WEATHER_SIZES = {11, 12, 13, 14, 15};
    private static final float[] EVENT_SIZES = {13, 15, 17, 19, 21};
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
        if (Intent.ACTION_DATE_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || ACTION_REFRESH.equals(action)) {
            // 升级或手动刷新时切换列表缓存标识，确保桌面重新绑定工厂并读取完整名称。
            if (ACTION_REFRESH.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
                settings.edit().putLong("widget_binding_generation", settings.getLong("widget_binding_generation", 0L) + 1).apply();
            }
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
        // 使用中国地区格式生成“月、日、星期”文字。
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA);
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        int background = backgroundForTransparency(preferences.getInt("widget_transparency", 15));
        int headerLevel = levelIndex(preferences.getInt("widget_header_font_level", 1));
        int eventLevel = levelIndex(preferences.getInt("widget_event_font_level", 1));
        int accent = preferences.getInt("theme_accent", Color.rgb(95, 143, 105));
        boolean showLunar = preferences.getBoolean("show_lunar_dates", true);
        // 每个桌面实例都要单独创建远程视图并提交给系统。
        for (int id : ids) {
            // 新布局编号使桌面丢弃旧控件树，避免同版本覆盖安装后把内容和点击写到错误控件。
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.calendar_widget_v3);
            views.setInt(R.id.widget_root, "setBackgroundResource", background);
            String specialDate = specialDateLabel(context, preferences, today);
            String todayText = today.format(dateFormat) + (!specialDate.isEmpty() ? " · " + specialDate
                    : showLunar ? " · 农历" + LunarDateUtils.compact(today) : "");
            views.setTextViewText(R.id.widget_date, todayText);
            views.setTextColor(R.id.widget_date, accent);
            views.setTextColor(R.id.widget_refresh, Color.rgb(29, 36, 51));
            views.setViewVisibility(R.id.widget_refresh, preferences.getBoolean("show_widget_refresh", false) ? View.VISIBLE : View.GONE);
            views.setTextViewTextSize(R.id.widget_time, TypedValue.COMPLEX_UNIT_SP, TIME_SIZES[headerLevel]);
            views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, DATE_SIZES[headerLevel]);
            views.setTextViewTextSize(R.id.widget_weather, TypedValue.COMPLEX_UNIT_SP, WEATHER_SIZES[headerLevel]);
            // 系统列表服务按需提供全部事项；每个组件使用不同地址，避免桌面错误复用另一实例的数据。
            Intent listService = new Intent(context, CalendarWidgetService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            listService.setData(Uri.parse("chachacalendar://widget-items/layout-3/" + id + "/"
                    + preferences.getLong("widget_binding_generation", 0L)));
            views.setRemoteAdapter(R.id.widget_event_list, listService);
            views.setEmptyView(R.id.widget_event_list, R.id.widget_empty);
            // 任何可靠的组件刷新都会把列表恢复到第一条，防止刷新后仍停留在旧位置。
            views.setScrollPosition(R.id.widget_event_list, 0);
            // 事项行、无事项提示和其余空白区域统一打开应用的日程页；已打开应用时复用原页面。
            Intent openAgenda = new Intent(context, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // 安卓十二起集合点击模板必须明确允许系统合并行级填充意图；目标组件仍固定为本应用页面。
            int templateFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent openAgendaPending = activityPendingIntent(context, 1000 + id, openAgenda, templateFlags);
            views.setPendingIntentTemplate(R.id.widget_event_list, openAgendaPending);
            views.setOnClickPendingIntent(R.id.widget_empty, openAgendaPending);
            views.setOnClickPendingIntent(R.id.widget_root, openAgendaPending);
            // 刷新按钮只重新读取本地数据库，不启动网络请求或常驻后台任务。
            Intent refresh = new Intent(context, CalendarWidgetProvider.class).setAction(ACTION_REFRESH);
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 2000 + id, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            // 加号直接调用应用内同一套新建表单，新增日期仍遵循主页面当前选中日期。
            Intent create = new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CREATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            views.setOnClickPendingIntent(R.id.widget_add, activityPendingIntent(context, 4000 + id, create,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            // 如果系统公开了天气应用入口，天气文字可点击；否则只显示不可用状态。
            Intent weather = SystemWeatherBridge.findWeatherApp(context);
            if (weather != null) {
                views.setTextViewText(R.id.widget_weather, "天气 · 系统来源");
                views.setOnClickPendingIntent(R.id.widget_weather, activityPendingIntent(context, 3000 + id, weather,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                views.setTextViewText(R.id.widget_weather, "天气 · 系统未开放");
            }
            // 先提交新布局和点击绑定，再通知其列表读取数据，防止通知发给旧列表。
            manager.updateAppWidget(id, views);
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_event_list);
        }
    }

    /** 小组件第二行固定为类型、日期及待办时间、倒计时；名称单独放在第一行。 */
    static String widgetDetails(Event event, LocalDate today) {
        LocalDate next = event.nextDate(today);
        long days = event.daysUntil(today);
        String time = event.timeLabel().isEmpty() ? "" : " " + event.timeLabel();
        String countdown = event.isOverdue() ? "已过期" : days == 0 ? "今天" : days > 0 ? days + "天后" : "已过" + (-days) + "天";
        return event.type + "·" + event.displayDate(next) + time + "·" + countdown;
    }

    /** 把设置中一至五级转换为数组使用的零至四下标。 */
    static int levelIndex(int level) { return Math.max(0, Math.min(4, level - 1)); }

    /** 返回设置级别对应的事项字号，让列表服务和组件顶部保持同一档设置。 */
    static float eventTextSize(int level) { return EVENT_SIZES[levelIndex(level)]; }

    /** 创建由小组件点击触发的页面入口，并兼容安卓十五的后台页面启动规则。 */
    private static PendingIntent activityPendingIntent(Context context, int requestCode, Intent intent, int flags) {
        Bundle options = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            options = activityOptions.toBundle();
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags, options);
    }

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
