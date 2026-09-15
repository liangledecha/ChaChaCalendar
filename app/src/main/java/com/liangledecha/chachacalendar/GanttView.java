package com.liangledecha.chachacalendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量甘特时间轴。
 *
 * <p>左侧固定显示事项名称，右侧只绘制屏幕当前可见的日期和任务条。普通日程、待办有计划
 * 区间时显示横条；纪念日、生日、倒数日及旧数据显示为里程碑菱形。该控件不启动定时器，
 * 滑动结束后也不继续工作。</p>
 */
public final class GanttView extends View {
    /** 点击查看、长按编辑沿用主页面现有交互。 */
    public interface Listener { void onClick(Event event); void onLongClick(Event event); }

    /** 日、周、月三个密度级别。 */
    public static final int SCALE_DAY = 0, SCALE_WEEK = 1, SCALE_MONTH = 2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final List<Event> items = new ArrayList<>();
    private Listener listener;
    private LocalDate anchor = LocalDate.now(), baseDate = anchor.minusDays(365);
    private float dayWidth, horizontalOffset, verticalOffset;
    private int scale = SCALE_DAY, accent = Color.rgb(95, 143, 105);
    private final float labelWidth, headerHeight, rowHeight;

    /** 初始化固定尺寸和手势；所有尺寸均按屏幕密度换算。 */
    public GanttView(Context context) {
        super(context); setBackgroundColor(Color.WHITE); setFocusable(true);
        labelWidth = dp(116); headerHeight = dp(48); rowHeight = dp(58); dayWidth = dp(44);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
                horizontalOffset = clamp(horizontalOffset + distanceX, 0, maxHorizontal());
                verticalOffset = clamp(verticalOffset + distanceY, 0, maxVertical()); invalidate(); return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                Event item = itemAt(event.getY()); if (item != null && listener != null) listener.onClick(item); return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                Event item = itemAt(event.getY()); if (item != null && listener != null) listener.onLongClick(item);
            }
        });
        setContentDescription("甘特时间轴，可左右查看日期、上下查看事项；点击查看，长按编辑");
    }

    /** 设置点击回调。 */
    public void setListener(Listener value) { listener = value; }

    /** 应用用户选择的主题色。 */
    public void setAccent(int value) { accent = value; invalidate(); }

    /** 替换事项并把今天放在时间轴左侧附近。 */
    public void setItems(List<Event> values, LocalDate value) {
        items.clear(); if (values != null) items.addAll(values);
        anchor = value == null ? LocalDate.now() : value; baseDate = anchor.minusDays(365);
        horizontalOffset = 362 * dayWidth; verticalOffset = 0; invalidate();
    }

    /** 在日、周、月三个密度间切换，同时保持当前屏幕中心日期基本不变。 */
    public void setScale(int value) {
        int next = Math.max(SCALE_DAY, Math.min(SCALE_MONTH, value));
        if (next == scale) return;
        float oldWidth = dayWidth, centerDay = (horizontalOffset + Math.max(0, getWidth() - labelWidth) / 2f) / oldWidth;
        scale = next; dayWidth = dp(scale == SCALE_DAY ? 44 : scale == SCALE_WEEK ? 20 : 8);
        horizontalOffset = clamp(centerDay * dayWidth - Math.max(0, getWidth() - labelWidth) / 2f, 0, maxHorizontal());
        invalidate();
    }

    /** 返回当前密度，供页面上的循环切换按钮生成中文名称。 */
    public int getScale() { return scale; }

    /** 把今天重新放回时间轴左侧附近，并回到第一条事项。 */
    public void scrollToToday() {
        long todayIndex = ChronoUnit.DAYS.between(baseDate, LocalDate.now());
        horizontalOffset = clamp((todayIndex - 3) * dayWidth, 0, maxHorizontal());
        verticalOffset = 0; invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= labelWidth || height <= headerHeight) return;
        int firstDay = Math.max(0, (int) Math.floor(horizontalOffset / dayWidth));
        int lastDay = Math.min(730, firstDay + (int) Math.ceil((width - labelWidth) / dayWidth) + 2);
        LocalDate visibleStart = baseDate.plusDays(firstDay), visibleEnd = baseDate.plusDays(lastDay);

        // 先绘制右侧日期背景、周末底色、网格和日期标题。
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); canvas.drawRect(labelWidth, 0, width, height, paint);
        for (int day = firstDay; day <= lastDay; day++) {
            LocalDate date = baseDate.plusDays(day); float x = labelWidth + day * dayWidth - horizontalOffset;
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                paint.setColor(Color.rgb(246,248,246)); canvas.drawRect(x, headerHeight, x + dayWidth, height, paint);
            }
            paint.setColor(Color.rgb(229,233,229)); paint.setStrokeWidth(dp(1)); canvas.drawLine(x, 0, x, height, paint);
            if (shouldLabel(date)) {
                textPaint.setColor(Color.rgb(82,89,100)); textPaint.setTextSize(sp(11)); textPaint.setFakeBoldText(false);
                String label = scale == SCALE_MONTH ? date.getMonthValue() + "月" :
                        date.getDayOfMonth() == 1 ? date.getMonthValue() + "/1" : Integer.toString(date.getDayOfMonth());
                canvas.drawText(label, x + dp(3), dp(29), textPaint);
            }
        }

        // 今天线始终使用主题色，便于用户快速定位当前时刻。
        float todayX = dateX(LocalDate.now().atStartOfDay());
        if (todayX >= labelWidth && todayX <= width) {
            paint.setColor(accent); paint.setStrokeWidth(dp(2)); canvas.drawLine(todayX, 0, todayX, height, paint);
        }

        int firstRow = Math.max(0, (int) Math.floor(verticalOffset / rowHeight));
        int lastRow = Math.min(items.size() - 1, firstRow + (int) Math.ceil((height - headerHeight) / rowHeight) + 1);
        for (int row = firstRow; row <= lastRow; row++) {
            Event event = items.get(row); float top = headerHeight + row * rowHeight - verticalOffset;
            paint.setColor(Color.rgb(232,235,232)); paint.setStrokeWidth(dp(1)); canvas.drawLine(0, top + rowHeight, width, top + rowHeight, paint);
            drawOccurrences(canvas, event, top, visibleStart, visibleEnd);
        }

        // 最后覆盖固定名称栏，保证横向滑动时任务条不会穿过文字。
        paint.setColor(Color.WHITE); canvas.drawRect(0, 0, labelWidth, height, paint);
        paint.setColor(Color.rgb(218,223,218)); canvas.drawRect(labelWidth - dp(1), 0, labelWidth, height, paint);
        textPaint.setColor(Color.rgb(60,67,78)); textPaint.setTextSize(sp(12)); textPaint.setFakeBoldText(true);
        canvas.drawText("事项", dp(12), dp(29), textPaint);
        for (int row = firstRow; row <= lastRow; row++) {
            Event event = items.get(row); float top = headerHeight + row * rowHeight - verticalOffset;
            paint.setColor(row % 2 == 0 ? Color.WHITE : Color.rgb(250,251,250)); canvas.drawRect(0, top, labelWidth - dp(1), top + rowHeight, paint);
            paint.setColor(Color.rgb(232,235,232)); canvas.drawLine(0, top + rowHeight, labelWidth, top + rowHeight, paint);
            textPaint.setColor(event.completed ? Color.rgb(145,148,156) : event.isOverdue() ? Color.rgb(210,55,67) : Color.rgb(35,40,48));
            textPaint.setTextSize(sp(13)); textPaint.setFakeBoldText(false);
            CharSequence title = TextUtils.ellipsize(event.title, textPaint, labelWidth - dp(20), TextUtils.TruncateAt.END);
            canvas.drawText(title.toString(), dp(10), top + rowHeight / 2f + dp(5), textPaint);
        }
        if (items.isEmpty()) {
            textPaint.setColor(Color.rgb(120,126,136)); textPaint.setTextSize(sp(14)); textPaint.setFakeBoldText(false);
            canvas.drawText("暂无事项", dp(18), headerHeight + dp(36), textPaint);
        }
    }

    /** 绘制所有可能与当前可见范围相交的重复实例，包括发生日在屏幕外的长计划条。 */
    private void drawOccurrences(Canvas canvas, Event event, float rowTop, LocalDate visibleStart, LocalDate visibleEnd) {
        LocalDate searchStart = event.ganttSearchStart(visibleStart), searchEnd = event.ganttSearchEnd(visibleEnd);
        for (LocalDate date = searchStart; !date.isAfter(searchEnd); date = date.plusDays(1)) {
            if (!event.occursOn(date)) continue;
            LocalDateTime start = event.ganttStart(date), end = event.ganttEnd(date);
            float x1 = dateX(start), x2 = dateX(end);
            int color = event.completed ? Color.rgb(155,160,166) : event.isOverdue() ? Color.rgb(210,55,67) : accent;
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            float center = rowTop + rowHeight / 2f;
            if (event.plannedStart == null || event.plannedEnd == null || !end.isAfter(start)) {
                Path diamond = new Path(); float size = dp(7);
                diamond.moveTo(x1, center - size); diamond.lineTo(x1 + size, center);
                diamond.lineTo(x1, center + size); diamond.lineTo(x1 - size, center); diamond.close(); canvas.drawPath(diamond, paint);
            } else {
                x2 = Math.max(x2, x1 + dp(7));
                canvas.drawRoundRect(new RectF(x1, center - dp(8), x2, center + dp(8)), dp(7), dp(7), paint);
            }
        }
    }

    /** 根据当前密度减少标题数量，防止周视图和月视图文字重叠。 */
    private boolean shouldLabel(LocalDate date) {
        if (scale == SCALE_DAY) return true;
        if (scale == SCALE_WEEK) return date.getDayOfWeek() == DayOfWeek.MONDAY || date.getDayOfMonth() == 1;
        return date.getDayOfMonth() == 1;
    }

    /** 把日期时间转换为右侧时间轴横坐标，时间精确到分钟比例。 */
    private float dateX(LocalDateTime value) {
        long days = ChronoUnit.DAYS.between(baseDate, value.toLocalDate());
        float fraction = value.getHour() / 24f + value.getMinute() / 1440f;
        return labelWidth + (days + fraction) * dayWidth - horizontalOffset;
    }

    /** 根据纵坐标找到对应事项；标题栏和空白区返回空。 */
    private Event itemAt(float y) {
        int row = (int) ((y - headerHeight + verticalOffset) / rowHeight);
        return y < headerHeight || row < 0 || row >= items.size() ? null : items.get(row);
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }

    private float maxHorizontal() { return Math.max(0, 731 * dayWidth - Math.max(0, getWidth() - labelWidth)); }
    private float maxVertical() { return Math.max(0, items.size() * rowHeight - Math.max(0, getHeight() - headerHeight)); }
    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
