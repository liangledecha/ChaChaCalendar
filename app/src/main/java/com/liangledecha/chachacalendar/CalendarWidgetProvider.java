package com.liangledecha.chachacalendar;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * 桌面日历小组件接收器。
 *
 * <p>负责把系统时间、当天日期、最近事项和系统天气入口写入桌面组件。
 * 时间文本由系统时钟控件自行刷新，本类不启动分钟级定时任务，因此更省电。</p>
 */
public class CalendarWidgetProvider extends AppWidgetProvider {
    private static final Class<?>[] PROVIDERS = {CalendarWidgetProvider.class, TodoWidgetProvider.class, CountdownWidgetProvider.class};
    /** 用户点击组件刷新按钮时发送的应用内广播动作。 */
    private static final String ACTION_REFRESH = "com.liangledecha.chachacalendar.REFRESH_WIDGET";
    /** 两组五级字号。第一级保持旧版默认，第五级适合组件铺满整个桌面。 */
    private static final float[] TIME_SIZES = {24, 28, 32, 36, 40};
    private static final float[] DATE_SIZES = {13, 15, 17, 19, 21};
    private static final float[] WEATHER_SIZES = {11, 12, 13, 14, 15};
    private static final float[] EVENT_SIZES = {13, 15, 17, 19, 21};
    /** 系统要求刷新某一批小组件时，把工作统一交给完整刷新方法。 */
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context, manager, ids);
        NightMode.schedule(context);
    }

    @Override public void onDisabled(Context context) { NightMode.schedule(context); }

    /** 用户拖动改变组件尺寸后，只刷新被调整的实例并重新计算可显示条数。 */
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle newOptions) {
        if (mode(context, manager, appWidgetId) == WidgetData.TODOS) refreshTodo(context, appWidgetId);
        else updateAll(context, manager, new int[]{appWidgetId});
    }

    @Override public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor edit = settings(context).edit();
        for (int id : ids) edit.remove("widget_pin_" + id).remove("widget_todo_generation_" + id);
        edit.apply();
    }

    static SharedPreferences settings(Context context) { return context.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    static int mode(Context context, AppWidgetManager manager, int id) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null || !context.getPackageName().equals(info.provider.getPackageName())) return -1;
        for (int i = 0; i < PROVIDERS.length; i++) if (PROVIDERS[i].getName().equals(info.provider.getClassName())) return i;
        return -1;
    }

    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int mode : new int[]{WidgetData.TODOS, WidgetData.AGENDA, WidgetData.COUNTDOWN})
            updateAll(context, manager, manager.getAppWidgetIds(new ComponentName(context, PROVIDERS[mode])));
        NightMode.schedule(context);
    }

    static synchronized void invalidateLists(Context context) {
        SharedPreferences p = settings(context);
        p.edit().putLong("widget_binding_generation", p.getLong("widget_binding_generation", 0) + 1).apply();
    }

    static void refreshTodo(Context context, int id) {
        synchronized (CalendarWidgetProvider.class) {
            SharedPreferences p = settings(context);
            String key = "widget_todo_generation_" + id;
            p.edit().putLong(key, p.getLong(key, 0) + 1).apply();
        }
        updateAll(context, AppWidgetManager.getInstance(context), new int[]{id});
    }

    /** 接收日期变化和时区变化广播，并在必要时重新计算倒计时。 */
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (Intent.ACTION_DATE_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || ACTION_REFRESH.equals(action)) {
            // 升级或手动刷新时切换列表缓存标识，确保桌面重新绑定工厂并读取完整名称。
            if (ACTION_REFRESH.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                invalidateLists(context);
            }
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            refreshAll(context);
            // 农历年度重复无法用系统公历重复规则表达，日期变化时把下一次真实公历落点补写给系统日历。
            if (Intent.ACTION_DATE_CHANGED.equals(action) && SystemCalendarSync.hasPermission(context)) {
                PendingResult pending = goAsync();
                new Thread(() -> {
                    try {
                        EventStore store = new EventStore(context);
                        for (Event event : store.all()) if (event.lunarBased && Event.YEARLY.equals(event.repeatRule)) {
                            SystemCalendarSync.syncStored(context, store, event.id);
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
        if (ids == null || ids.length == 0) return;
        // 组件按当前时间换色，不继承 Activity 为正在编辑的弹窗冻结的配色。
        context = context.getApplicationContext();
        // 今天用于格式化日期，并作为显示规则和倒计时的计算基准。
        LocalDate today = LocalDate.now();
        // 使用中国地区格式生成“月、日、星期”文字。
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA);
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        synchronized (WidgetActionToken.class) {
            if (preferences.getString("widget_action_token", "").isEmpty()
                    && !preferences.edit().putString("widget_action_token", java.util.UUID.randomUUID().toString()).commit()) return;
        }
        int background = backgroundForTransparency(preferences.getInt("widget_transparency", 15), NightMode.isNight(context));
        int headerLevel = levelIndex(preferences.getInt("widget_header_font_level", 1));
        int eventLevel = levelIndex(preferences.getInt("widget_event_font_level", 1));
        int accent = NightMode.accent(context, preferences.getInt("theme_accent", Color.rgb(95, 143, 105)));
        boolean showLunar = preferences.getBoolean("show_lunar_dates", true);
        // 每个桌面实例都要单独创建远程视图并提交给系统。
        for (int id : ids) {
            int mode = mode(context, manager, id);
            if (mode == WidgetData.TODOS) { renderTodos(context, manager, id, today, preferences, background, accent); continue; }
            if (mode == WidgetData.COUNTDOWN) { renderCountdown(context, manager, id, today, preferences, background, accent); continue; }
            if (mode != WidgetData.AGENDA) continue;
            // 新布局编号使桌面丢弃旧控件树，避免同版本覆盖安装后把内容和点击写到错误控件。
            RemoteViews views = CalendarWidgetService.agendaViews(context, id);
            views.setInt(R.id.widget_root, "setBackgroundResource", background);
            String specialDate = specialDateLabel(context, preferences, today);
            String todayText = today.format(dateFormat) + (!specialDate.isEmpty() ? " · " + specialDate
                    : showLunar ? " · 农历" + LunarDateUtils.compact(today) : "");
            views.setTextViewText(R.id.widget_date, todayText);
            views.setTextColor(R.id.widget_date, accent);
            int foreground = NightMode.foreground(context, Color.rgb(29, 36, 51));
            views.setTextColor(R.id.widget_time, foreground);
            views.setTextColor(R.id.widget_refresh, foreground);
            views.setTextColor(R.id.widget_add, foreground);
            views.setTextColor(R.id.widget_weather, NightMode.foreground(context, 0xff687086));
            views.setTextColor(R.id.widget_empty, NightMode.foreground(context, 0xff73798a));
            views.setViewVisibility(R.id.widget_refresh, preferences.getBoolean("show_widget_refresh", false) ? View.VISIBLE : View.GONE);
            views.setTextViewTextSize(R.id.widget_time, TypedValue.COMPLEX_UNIT_SP, TIME_SIZES[headerLevel]);
            views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, DATE_SIZES[headerLevel]);
            views.setTextViewTextSize(R.id.widget_weather, TypedValue.COMPLEX_UNIT_SP, WEATHER_SIZES[headerLevel]);
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
            manager.updateAppWidget(id, views);
            if (views.getLayoutId() == R.layout.calendar_widget_service)
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_event_list);
        }
    }

    private static Intent agenda(Context context) {
        return new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private static void renderTodos(Context context, AppWidgetManager manager, int id, LocalDate today,
                                    SharedPreferences p, int background, int accent) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.todo_widget);
        views.setInt(R.id.widget_root, "setBackgroundResource", background);
        views.setTextViewText(R.id.widget_date, today.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)));
        views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP,
                DATE_SIZES[levelIndex(p.getInt("widget_header_font_level", 1))]);
        views.setTextColor(R.id.widget_date, NightMode.foreground(context, 0xff1d2433));
        views.setTextColor(R.id.widget_empty, NightMode.foreground(context, 0xff68736c));
        views.setTextColor(R.id.widget_add, accent);
        Intent service = new Intent(context, CalendarWidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("chachacalendar://widget-items/todo-2/" + id + "/" + p.getLong("widget_binding_generation", 0)
                        + "/" + p.getLong("widget_todo_generation_" + id, 0)));
        views.setRemoteAdapter(R.id.widget_event_list, service);
        views.setEmptyView(R.id.widget_event_list, R.id.widget_empty);
        Intent actions = new Intent(context, WidgetActionReceiver.class).setAction(WidgetActionReceiver.ACTION)
                .setData(Uri.parse("chachacalendar://widget-actions/" + id))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("widget_action_token", p.getString("widget_action_token", ""));
        views.setPendingIntentTemplate(R.id.widget_event_list, PendingIntent.getBroadcast(context, id, actions,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)));
        PendingIntent open = activityPendingIntent(context, id, agenda(context).setData(Uri.parse("chachacalendar://todos/" + id)),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_empty, open);
        views.setOnClickPendingIntent(R.id.widget_add, activityPendingIntent(context, id,
                new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CREATE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .setData(Uri.parse("chachacalendar://todos/" + id + "/create")),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(id, exactListViews(manager.getAppWidgetOptions(id), views));
        manager.notifyAppWidgetViewDataChanged(id, R.id.widget_event_list);
    }

    private static RemoteViews exactListViews(Bundle options, RemoteViews views) {
        if (Build.VERSION.SDK_INT < 31) return views;
        ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
        if (sizes == null || sizes.isEmpty()) return views;
        Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
        for (SizeF size : sizes) if (validSize(size) && layouts.size() < 16)
            layouts.put(size, new RemoteViews(views));
        return layouts.isEmpty() ? views : new RemoteViews(layouts);
    }

    private static boolean validSize(SizeF size) {
        return size != null && Float.isFinite(size.getWidth()) && Float.isFinite(size.getHeight())
                && size.getWidth() > 0 && size.getHeight() > 0;
    }

    private static void renderCountdown(Context context, AppWidgetManager manager, int id, LocalDate today,
                                       SharedPreferences p, int background, int accent) {
        long pin = p.getLong("widget_pin_" + id, -1);
        EventStore store = new EventStore(context);
        Event event;
        try { event = pin < 0 ? null : store.all().stream().filter(item -> item.id == pin).findFirst().orElse(null); }
        finally { store.close(); }
        // 新布局编号使旧实例丢弃固定高度与自动缩字状态，覆盖安装后也重新计算真实字号。
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_countdown_responsive);
        views.setInt(R.id.widget_root, "setBackgroundResource", background);
        int foreground = NightMode.foreground(context, Color.rgb(32, 39, 55));
        for (int text : new int[]{R.id.widget_countdown_title, R.id.widget_countdown_type, R.id.widget_countdown_date})
            views.setTextColor(text, foreground);
        views.setTextColor(R.id.widget_countdown_value, accent);
        views.setInt(R.id.widget_pin, "setColorFilter", foreground);
        String title = event != null ? event.title : context.getString(pin < 0 ? R.string.widget_countdown_empty : R.string.widget_countdown_missing);
        LocalDate next = event == null ? null : event.nextDateAt(today, today);
        String value = event == null ? context.getString(R.string.widget_countdown_placeholder) : WidgetData.countdown(next, today);
        SpannableString asymmetric = new SpannableString(value);
        int suffix = 0;
        while (suffix < value.length() && Character.isDigit(value.charAt(suffix))) suffix++;
        if (suffix > 0) asymmetric.setSpan(new RelativeSizeSpan(.45f), suffix, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        views.setTextViewText(R.id.widget_countdown_title, title);
        views.setTextViewText(R.id.widget_countdown_value, asymmetric);
        String type = event == null ? "" : event.type;
        String date = event == null ? "" : next.format(DateTimeFormatter.ofPattern("M月d日"));
        views.setTextViewText(R.id.widget_countdown_type, type);
        views.setTextViewText(R.id.widget_countdown_date, date);
        views.setContentDescription(R.id.widget_root, title + "，" + value + (event == null ? "" : "，" + event.type));
        Intent open = agenda(context).setData(Uri.parse("chachacalendar://countdown/" + id));
        if (event != null) open.putExtra(MainActivity.EXTRA_EVENT_ID, event.id);
        views.setOnClickPendingIntent(R.id.widget_root, activityPendingIntent(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent choose = new Intent(context, MainActivity.class).putExtra("widget_pin_id", id)
                .putExtra("widget_action_token", p.getString("widget_action_token", ""))
                .setData(Uri.parse("chachacalendar://countdown/" + id + "/choose"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        views.setOnClickPendingIntent(R.id.widget_pin, activityPendingIntent(context, id, choose,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Bundle options = manager.getAppWidgetOptions(id);
        ArrayList<SizeF> sizes = Build.VERSION.SDK_INT >= 31
                ? options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES) : null;
        Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT >= 31 && sizes != null) for (SizeF size : sizes) if (validSize(size) && layouts.size() < 16) {
            RemoteViews sized = new RemoteViews(views);
            sizeCountdown(context, sized, size, title, asymmetric, type, date);
            layouts.put(size, sized);
        }
        if (Build.VERSION.SDK_INT >= 31 && !layouts.isEmpty()) manager.updateAppWidget(id, new RemoteViews(layouts));
        else {
            RemoteViews portrait = views.clone(), landscape = views.clone();
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
            int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            int maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            sizeCountdown(context, portrait, new SizeF(widgetDimension(minWidth, maxWidth), widgetDimension(maxHeight, minHeight)), title, asymmetric, type, date);
            sizeCountdown(context, landscape, new SizeF(widgetDimension(maxWidth, minWidth), widgetDimension(minHeight, maxHeight)), title, asymmetric, type, date);
            manager.updateAppWidget(id, new RemoteViews(landscape, portrait));
        }
    }

    static int widgetDimension(int preferred, int alternative) {
        return preferred > 0 ? preferred : alternative > 0 ? alternative : 180;
    }

    private static void sizeCountdown(Context context, RemoteViews views, SizeF size,
                                      CharSequence title, CharSequence value, CharSequence type, CharSequence date) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.max(1, Math.round((size.getWidth() - 12) * density));
        int height = Math.max(1, Math.round((size.getHeight() - 12) * density));
        int titleWidth = Math.max(1, width - Math.round(40 * density));
        int titleLines = size.getHeight() < 130 ? 1 : 2;
        int[] ids = {R.id.widget_countdown_title, R.id.widget_countdown_value, R.id.widget_countdown_type, R.id.widget_countdown_date};
        CharSequence[] labels = {title, value, type, date};
        float[] proportions = {19, 56, 13, 16};
        float lower = 0, upper = height / 28f;
        TextPaint paint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextLocales(context.getResources().getConfiguration().getLocales());
        // 用真实字形和行高寻找可容纳四段文字的最大字号，不靠网格或手机型号猜比例。
        for (int attempt = 0; attempt < 12; attempt++) {
            float scale = (lower + upper) / 2;
            int usedHeight = 0;
            boolean fits = true;
            for (int i = 0; i < labels.length; i++) {
                paint.setTypeface(i < 2 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                paint.setTextSize(proportions[i] * scale);
                StaticLayout.Builder builder = StaticLayout.Builder.obtain(labels[i], 0, labels[i].length(), paint, i == 0 ? titleWidth : width)
                        .setIncludePad(false).setMaxLines(i == 0 ? titleLines : 1).setEllipsize(android.text.TextUtils.TruncateAt.END)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL);
                if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
                StaticLayout line = builder.build();
                usedHeight += line.getHeight();
                if (i > 0 && line.getEllipsisCount(0) > 0) fits = false;
            }
            if (fits && usedHeight <= height - Math.round(2 * density)) lower = scale;
            else upper = scale;
        }
        for (int i = 0; i < ids.length; i++) views.setTextViewTextSize(ids[i], TypedValue.COMPLEX_UNIT_PX, proportions[i] * lower);
        views.setInt(R.id.widget_countdown_title, "setMaxLines", titleLines);
        float menuGraphic = Math.max(12 * density, Math.min(32 * density, 56 * lower * .45f));
        int previousGraphic = Math.round(48 * density) - 2 * Math.round((48 * density - menuGraphic) / 2);
        // 面积缩为原来的 3/5，边长乘其平方根；按原来已取整的像素计算，保留 48dp 触区。
        int menuPadding = Math.round((Math.round(48 * density) - previousGraphic * (float) Math.sqrt(3f / 5f)) / 2);
        views.setViewPadding(R.id.widget_pin, menuPadding, menuPadding, menuPadding, menuPadding);
    }

    /** 小组件第二行固定为类型、日期及待办时间、倒计时；名称单独放在第一行。 */
    static String widgetDetails(Event event, LocalDate today, LocalDate next, boolean overdue) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, next);
        String time = event.timeLabel().isEmpty() ? "" : " " + event.timeLabel();
        String countdown = event.isCompletedOn(today) ? "已完成" : overdue ? "已过期" : days == 0 ? "今天" : days > 0 ? days + "天后" : "已过" + (-days) + "天";
        return event.type + "·" + event.displayDate(next) + time + "·" + countdown;
    }

    /** 把设置中一至五级转换为数组使用的零至四下标。 */
    static int levelIndex(int level) { return Math.max(0, Math.min(4, level - 1)); }

    /** 返回设置级别对应的事项字号，让列表服务和组件顶部保持同一档设置。 */
    static float eventTextSize(int level) { return EVENT_SIZES[levelIndex(level)]; }

    static float detailTextSize(float titleSize) { return Math.max(10, titleSize - 2); }

    /** 创建由小组件点击触发的页面入口，并兼容安卓十五的后台页面启动规则。 */
    static PendingIntent activityPendingIntent(Context context, int requestCode, Intent intent, int flags) {
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
    static int backgroundForTransparency(int transparency, boolean night) {
        if (transparency >= 60) return night ? R.drawable.widget_bg_dark_40 : R.drawable.widget_bg_40;
        if (transparency >= 45) return night ? R.drawable.widget_bg_dark_55 : R.drawable.widget_bg_55;
        if (transparency >= 30) return night ? R.drawable.widget_bg_dark_70 : R.drawable.widget_bg_70;
        if (transparency >= 15) return night ? R.drawable.widget_bg_dark_85 : R.drawable.widget_bg_85;
        return night ? R.drawable.widget_bg_dark_100 : R.drawable.widget_bg_100;
    }
}
