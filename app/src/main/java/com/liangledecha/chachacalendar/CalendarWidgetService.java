package com.liangledecha.chachacalendar;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseUtils;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.util.TypedValue;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 为桌面小组件的系统列表提供事项行。
 *
 * <p>桌面启动器只请求当前屏幕需要的行，因此事项再多也无需一次创建全部视图；
 * 用户可直接在组件事项区上下滑动，系统负责视图复用和触摸处理。</p>
 */
public final class CalendarWidgetService extends RemoteViewsService {
    /** 系统绑定服务时，为对应小组件实例创建一份轻量数据工厂。 */
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new EventFactory(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    static RemoteViews agendaViews(Context context, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.calendar_widget_v4);
        if (bindAgendaItems(context, widgetId, views)) return views;
        // 两种适配器必须使用不同控件树，否则系统可能保留上次的直接列表而跳过服务重绑。
        views = new RemoteViews(context.getPackageName(), R.layout.calendar_widget_service);
        Intent service = new Intent(context, CalendarWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("chachacalendar://widget-items/layout-4/" + widgetId + "/"
                        + CalendarWidgetProvider.settings(context).getLong("widget_binding_generation", 0L)));
        views.setRemoteAdapter(R.id.widget_event_list, service);
        return views;
    }

    /** 小列表随样式一次提交，不先清空组件再等待系统重绑列表服务。 */
    @android.annotation.TargetApi(31)
    static boolean bindAgendaItems(Context context, int widgetId, RemoteViews views) {
        if (Build.VERSION.SDK_INT < 31) return false;
        EventFactory snapshot = new EventFactory(context, widgetId);
        if (!snapshot.reload(WidgetData.AGENDA, true)) return false;
        // 大列表保留按需服务，不截断事项；同时给 Binder 的其余布局及点击数据留足空间。
        if (snapshot.getCount() > 100) return false;
        RemoteViews.RemoteCollectionItems.Builder builder = new RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true).setViewTypeCount(1);
        for (int i = 0; i < snapshot.getCount(); i++) builder.addItem(snapshot.getItemId(i), snapshot.getViewAt(i));
        RemoteViews.RemoteCollectionItems items = builder.build();
        Parcel parcel = Parcel.obtain();
        try {
            items.writeToParcel(parcel, 0);
            if (parcel.dataSize() > 128 * 1024) return false;
        } finally { parcel.recycle(); }
        views.setRemoteAdapter(R.id.widget_event_list, items);
        return true;
    }

    /** 从本地数据库读取可见事项，并把每条事项转换成远程行布局。 */
    private static final class EventFactory implements RemoteViewsFactory {
        /** 应用上下文用于访问本地数据库和布局资源，不持有页面对象。 */
        private final Context context;
        /** 当前提供给桌面启动器的全部可见事项。 */
        private final ArrayList<Event> events = new ArrayList<>();
        /** 今天用于重新计算重复事项的下一次日期和倒计时。 */
        private LocalDate today = LocalDate.now();
        private int mode;
        /** 用户在设置中选择的事项字号。 */
        private float textSize;
        /** 本实例编号，用于读取桌面调整后的实际可用宽度。 */
        private final int widgetId;

        EventFactory(Context context, int widgetId) { this.context = context; this.widgetId = widgetId; }

        /** 新工厂随后必经 onDataSetChanged，只在那里读库，避免字号重绑定时加载两遍。 */
        @Override public void onCreate() { }

        /** 收到刷新、日期变化或尺寸变化通知时重新读取本地数据。 */
        @Override public void onDataSetChanged() {
            reload(CalendarWidgetProvider.mode(context, AppWidgetManager.getInstance(context), widgetId), false);
        }

        /** 本工厂不持有数据库连接，销毁时只清空内存列表。 */
        @Override public void onDestroy() { events.clear(); }

        /** 告诉桌面启动器当前共有多少条事项。 */
        @Override public int getCount() { return events.size(); }

        /** 生成指定位置的两行事项视图，并保持过期待办的红色提示。 */
        @Override public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= events.size()) return null;
            Event event = events.get(position);
            // 使用独立的新布局编号，禁止桌面继续复用早期版本只有一个文字控件的行。
            RemoteViews row = new RemoteViews(context.getPackageName(), mode == WidgetData.TODOS ? R.layout.widget_todo_row : R.layout.widget_event_row_v2);
            LocalDate next = event.nextDate(today);
            boolean overdue = event.isOverdue();
            String details = CalendarWidgetProvider.widgetDetails(event, today, next, overdue);
            int textColor = event.isCompletedOn(LocalDate.now()) ? Color.rgb(145, 148, 156) : overdue ? Color.rgb(210, 55, 67) : Color.rgb(32, 39, 55);
            textColor = NightMode.foreground(context.getApplicationContext(), textColor);
            int anniversary = event.anniversaryAt(next);
            String anniversaryText = anniversary < 0 ? "" : "·" + anniversary + "周年";
            row.setTextViewText(R.id.widget_item_text, (mode == WidgetData.TODOS ? "" : "● ") + event.title + anniversaryText);
            row.setTextViewText(R.id.widget_item_details, details);
            row.setTextColor(R.id.widget_item_text, textColor);
            row.setTextColor(R.id.widget_item_details, textColor);
            row.setTextViewTextSize(R.id.widget_item_text, TypedValue.COMPLEX_UNIT_SP, textSize);
            // 两类组件共用字号；宽度不够时省略文字，不因勾选框而另缩小待办字体。
            row.setTextViewTextSize(R.id.widget_item_details, TypedValue.COMPLEX_UNIT_SP, CalendarWidgetProvider.detailTextSize(textSize));
            row.setContentDescription(R.id.widget_item_root, event.title + "，" + details);
            // 集合中的事项行必须提供填充意图，点击后由组件的日程页模板启动应用。
            Intent open = new Intent().putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                    .putExtra(MainActivity.EXTRA_EVENT_ID, event.id);
            if (mode == WidgetData.TODOS) {
                boolean done = event.isCompletedOn(today);
                row.setTextViewText(R.id.widget_item_check, context.getString(done ? R.string.widget_check_checked : R.string.widget_check_unchecked));
                row.setTextColor(R.id.widget_item_check, done ? textColor : NightMode.accent(context.getApplicationContext(),
                        CalendarWidgetProvider.settings(context).getInt("theme_accent", 0xff5f8f69)));
                row.setContentDescription(R.id.widget_item_check, event.title + "，" + context.getString(done ? R.string.widget_mark_incomplete : R.string.widget_mark_complete));
                row.setOnClickFillInIntent(R.id.widget_item_body, open.putExtra("operation", "open"));
                row.setOnClickFillInIntent(R.id.widget_item_check, new Intent().putExtra("operation", "complete")
                        .putExtra(MainActivity.EXTRA_EVENT_ID, event.id)
                        .putExtra("occurrence", next.toString()).putExtra("completed", !done));
            } else row.setOnClickFillInIntent(R.id.widget_item_root, open);
            return row;
        }

        /** 使用系统默认加载行，不额外维护占位布局。 */
        @Override public RemoteViews getLoadingView() { return null; }
        /** 所有事项共用同一种两行布局。 */
        @Override public int getViewTypeCount() { return 1; }
        /** 数据库编号作为稳定编号，帮助系统复用未变化的行。 */
        @Override public long getItemId(int position) { return position < events.size() ? events.get(position).id : position; }
        /** 声明事项编号在一次数据刷新期间保持稳定。 */
        @Override public boolean hasStableIds() { return true; }

        /** 严格复用主组件的提前显示规则和字号设置，不维护第二套筛选逻辑。 */
        private boolean reload(int widgetMode, boolean bounded) {
            today = LocalDate.now();
            EventStore store = new EventStore(context);
            mode = widgetMode;
            List<Event> visible;
            try {
                // 大库在计算农历和排序前回退，避免把原本异步的重活搬到组件接收器。
                if (bounded && DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "events", "deleted=0") > 100) return false;
                visible = mode == WidgetData.TODOS ? WidgetData.todos(store.all(), today) : store.visible();
            }
            finally { store.close(); }
            events.clear();
            events.addAll(visible);
            SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            textSize = CalendarWidgetProvider.eventTextSize(preferences.getInt("widget_event_font_level", 1));
            return true;
        }
    }
}
