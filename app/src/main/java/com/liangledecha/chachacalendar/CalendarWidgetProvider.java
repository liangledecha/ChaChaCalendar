package com.liangledecha.chachacalendar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
    /** 系统要求刷新某一批小组件时，把工作统一交给完整刷新方法。 */
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { updateAll(context, manager, ids); }

    /** 接收日期变化和时区变化广播，并在必要时重新计算倒计时。 */
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (Intent.ACTION_DATE_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            updateAll(context, manager, manager.getAppWidgetIds(new ComponentName(context, CalendarWidgetProvider.class)));
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
        // 没有事项时使用明确的空状态文字。
        String next = "暂无符合显示规则的纪念日";
        if (!events.isEmpty()) {
            Event e = events.get(0); long days = e.daysUntil(today);
            next = e.title + "  ·  " + (days == 0 ? "就是今天" : days + " 天后") + "\n";
            if (events.size() > 1) { Event e2 = events.get(1); next += e2.title + "  ·  " + e2.daysUntil(today) + " 天后"; }
        }
        // 使用中国地区格式生成“月、日、星期”文字。
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA);
        // 每个桌面实例都要单独创建远程视图并提交给系统。
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.calendar_widget);
            views.setTextViewText(R.id.widget_date, today.format(dateFormat));
            views.setTextViewText(R.id.widget_next, next);
            // 点击组件主体时打开主日历页面。
            Intent openCalendar = new Intent(context, MainActivity.class);
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 10, openCalendar, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            // 如果系统公开了天气应用入口，天气文字可点击；否则只显示不可用状态。
            Intent weather = SystemWeatherBridge.findWeatherApp(context);
            if (weather != null) {
                views.setTextViewText(R.id.widget_weather, "天气 · 系统来源");
                views.setOnClickPendingIntent(R.id.widget_weather, PendingIntent.getActivity(context, 11, weather, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                views.setTextViewText(R.id.widget_weather, "天气 · 系统未开放");
            }
            manager.updateAppWidget(id, views);
        }
    }
}
