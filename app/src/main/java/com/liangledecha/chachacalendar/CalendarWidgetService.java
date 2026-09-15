package com.liangledecha.chachacalendar;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextPaint;
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

    /** 从本地数据库读取可见事项，并把每条事项转换成远程行布局。 */
    private static final class EventFactory implements RemoteViewsFactory {
        /** 应用上下文用于访问本地数据库和布局资源，不持有页面对象。 */
        private final Context context;
        /** 当前提供给桌面启动器的全部可见事项。 */
        private final ArrayList<Event> events = new ArrayList<>();
        /** 今天用于重新计算重复事项的下一次日期和倒计时。 */
        private LocalDate today = LocalDate.now();
        /** 用户在设置中选择的事项字号。 */
        private float textSize;
        /** 本实例编号，用于读取桌面调整后的实际可用宽度。 */
        private final int widgetId;
        /** 第二行文字的测量画笔，只在组件数据或尺寸刷新时使用。 */
        private final TextPaint measurePaint = new TextPaint();
        /** 已扣除组件和事项左右内边距的内容宽度，单位为像素。 */
        private float contentWidth;

        EventFactory(Context context, int widgetId) { this.context = context; this.widgetId = widgetId; }

        /** 首次建立列表适配器时读取一次数据。 */
        @Override public void onCreate() { reload(); }

        /** 收到刷新、日期变化或尺寸变化通知时重新读取本地数据。 */
        @Override public void onDataSetChanged() { reload(); }

        /** 本工厂不持有数据库连接，销毁时只清空内存列表。 */
        @Override public void onDestroy() { events.clear(); }

        /** 告诉桌面启动器当前共有多少条事项。 */
        @Override public int getCount() { return events.size(); }

        /** 生成指定位置的两行事项视图，并保持过期待办的红色提示。 */
        @Override public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= events.size()) return null;
            Event event = events.get(position);
            // 使用独立的新布局编号，禁止桌面继续复用早期版本只有一个文字控件的行。
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_event_row_v2);
            String details = CalendarWidgetProvider.widgetDetails(event, today);
            int textColor = event.isOverdue() ? Color.rgb(210, 55, 67) : Color.rgb(32, 39, 55);
            LocalDate next = event.nextDate(today);
            int anniversary = event.anniversaryAt(next);
            String anniversaryText = anniversary < 0 ? "" : "·" + anniversary + "周年";
            row.setTextViewText(R.id.widget_item_text, "● " + event.title + anniversaryText);
            row.setTextViewText(R.id.widget_item_details, details);
            row.setTextColor(R.id.widget_item_text, textColor);
            row.setTextColor(R.id.widget_item_details, textColor);
            row.setTextViewTextSize(R.id.widget_item_text, TypedValue.COMPLEX_UNIT_SP, textSize);
            // 标题沿用五档字号；第二行小两号，按宽度适度收缩且保留最低可读字号。
            float detailSize = textSize - 2;
            measurePaint.setTextSize(detailSize * context.getResources().getDisplayMetrics().scaledDensity);
            float requiredWidth = measurePaint.measureText(details);
            if (requiredWidth > contentWidth) detailSize = Math.max(10, detailSize * contentWidth / requiredWidth);
            row.setTextViewTextSize(R.id.widget_item_details, TypedValue.COMPLEX_UNIT_SP, detailSize);
            row.setContentDescription(R.id.widget_item_root, event.title + "，" + details);
            // 集合中的事项行必须提供填充意图，点击后由组件的日程页模板启动应用。
            row.setOnClickFillInIntent(R.id.widget_item_root,
                    new Intent().putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                            .putExtra(MainActivity.EXTRA_EVENT_ID, event.id));
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
        private void reload() {
            today = LocalDate.now();
            EventStore store = new EventStore(context);
            List<Event> visible = store.visible();
            store.close();
            events.clear();
            events.addAll(visible);
            SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            textSize = CalendarWidgetProvider.eventTextSize(preferences.getInt("widget_event_font_level", 1));
            Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            // 部分桌面不提供尺寸时按默认组件估算；最终仍由单行控件按实际宽度省略。
            if (width <= 0) width = 280;
            contentWidth = Math.max(1, width - 36) * context.getResources().getDisplayMetrics().density;
        }
    }
}
