package com.liangledecha.chachacalendar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可交互的月历绘制控件。
 *
 * <p>控件支持单周、普通整月和全屏标签三种高度；上下滑动切换高度，
 * 左右滑动切换月份，点击日期改变下半区的计算基准。</p>
 */
public final class MonthCalendarView extends View {
    /** 三种月历显示状态，顺序同时表示从收起到展开。 */
    public enum Mode { COMPACT, MONTH, EXPANDED }
    /** 向主页面报告日期选择、状态切换和年月变化。 */
    public interface Listener { void onDateSelected(LocalDate date); void onModeChanged(Mode mode); void onPeriodChanged(); }

    /** 月历所有图形共用的抗锯齿画笔。 */
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 重复使用的拖动柄矩形，避免每次绘制都创建临时对象。 */
    private final RectF handleRect = new RectF();
    /** 日期到当日事项列表的映射，用于绘制蓝点和标签。 */
    private final Map<LocalDate, List<Event>> eventMap = new HashMap<>();
    /** 日期到节日、放假补班或节气短标签的映射，由主页面按开关和缓存统一生成。 */
    private final Map<LocalDate, String> culturalLabelMap = new HashMap<>();
    /** 从星期一开始的中文星期标题。 */
    private final String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
    /** 当前展示的年月。 */
    private YearMonth month = YearMonth.now();
    /** 用户当前选中的具体日期。 */
    private LocalDate selected = LocalDate.now();
    /** 交互目标状态。 */
    private Mode mode = Mode.MONTH;
    /** 动画过程中真正用于绘制的状态，使收起和展开过渡更自然。 */
    private Mode renderedMode = Mode.MONTH;
    /** 是否在日期网格最左侧显示年周数。 */
    private boolean showWeekNumbers = true;
    /** 是否在每个公历日号下方显示同一天的农历日号。 */
    private boolean showLunarDates = true;
    /** 手指按下坐标，用于判断主要滑动方向。 */
    private float downY, downX;
    /** 月份横向动画锁，防止重复触发。 */
    private boolean periodAnimating;
    /** 过长事项标签本轮动画开始时刻，所有可见标签共用以减少计时对象。 */
    private long marqueeStart = SystemClock.uptimeMillis();
    /** 本帧是否存在需要滚动的标签；用于只安排一次下一帧重绘。 */
    private boolean marqueeNeeded;
    /** 当前主题主色，由主页面设置并用于选中圆圈和事项标记。 */
    private int accent = Color.rgb(95, 143, 105);
    /** 主页面注册的回调监听器。 */
    private Listener listener;

    /** 代码创建控件时使用的构造函数。 */
    public MonthCalendarView(Context c) { super(c); init(); }
    /** 从布局资源创建控件时使用的构造函数。 */
    public MonthCalendarView(Context c, AttributeSet a) { super(c, a); init(); }
    /** 设置透明背景和默认字体。 */
    private void init() { setBackgroundColor(Color.TRANSPARENT); paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)); }

    /** 注册页面事件监听器。 */
    public void setListener(Listener l) { listener = l; }
    /** 修改周数显示开关并立即重绘。 */
    public void setShowWeekNumbers(boolean show) { showWeekNumbers = show; invalidate(); }
    /** 修改农历显示开关并立即重绘，不改变任何事项自身使用的日期制。 */
    public void setShowLunarDates(boolean show) { showLunarDates = show; invalidate(); }
    /** 应用用户选择的主题主色。 */
    public void setAccent(int color) { accent = color; invalidate(); }
    /** 更换当前网格的节日、假日和节气标签。 */
    public void setCulturalLabels(Map<LocalDate, String> labels) {
        culturalLabelMap.clear(); if (labels != null) culturalLabelMap.putAll(labels); invalidate();
    }
    /** 返回当前三态显示状态。 */
    public Mode getMode() { return mode; }
    /** 返回当前年月。 */
    public YearMonth getMonth() { return month; }
    /** 返回当前选中日期，供下半区计算显示窗口。 */
    public LocalDate getSelectedDate() { return selected; }
    /** 跳转到指定年月，尽量保留原来的日号，并通知主页面刷新。 */
    public void setMonth(YearMonth value) {
        month = value; selected = safeDate(value.getYear(), value.getMonthValue(), selected.getDayOfMonth()); invalidate();
        if (listener != null) { listener.onDateSelected(selected); listener.onPeriodChanged(); }
    }
    /** 播放动画切换到上一个月。 */
    public void previousMonth() { animateMonth(-1); }
    /** 播放动画切换到下一个月。 */
    public void nextMonth() { animateMonth(1); }
    /** 回到今天所在月份并选中今天。 */
    public void today() { selected = LocalDate.now(); month = YearMonth.from(selected); invalidate(); if (listener != null) { listener.onDateSelected(selected); listener.onPeriodChanged(); } }

    /**
     * 建立当前六周网格内的日期与事项映射。
     * 每条重复事项通过自身规则判断是否发生在该日期。
     */
    public void setEvents(List<Event> events) {
        eventMap.clear();
        LocalDate first = month.atDay(1), start = first.minusDays(first.getDayOfWeek().getValue() - 1L);
        for (int i = 0; i < 42; i++) { LocalDate d = start.plusDays(i); for (Event e : events) if (e.occursOn(d)) eventMap.computeIfAbsent(d, ignored -> new ArrayList<>()).add(e); }
        marqueeStart = SystemClock.uptimeMillis(); invalidate();
    }

    /** 安全创建日期；目标月份没有原日号时自动使用该月最后一天。 */
    private LocalDate safeDate(int y, int m, int d) {
        return LocalDate.of(y, m, Math.min(d, YearMonth.of(y, m).lengthOfMonth()));
    }

    /** 切换三态显示高度，并播放高度、缩放和透明度动画。 */
    public void setMode(Mode newMode) {
        if (mode == newMode) return;
        Mode previous = mode;
        int startHeight = getHeight() > 0 ? getHeight() : targetHeight(mode);
        mode = newMode;
        renderedMode = newMode.ordinal() > previous.ordinal() ? newMode : previous;
        if (listener != null) listener.onModeChanged(mode);
        int endHeight = targetHeight(newMode);
        ValueAnimator height = ValueAnimator.ofInt(startHeight, endHeight);
        height.setDuration(340);
        height.setInterpolator(new DecelerateInterpolator());
        height.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = getLayoutParams();
            lp.height = (int) a.getAnimatedValue();
            setLayoutParams(lp);
            float progress = a.getAnimatedFraction();
            if (newMode.ordinal() < previous.ordinal() && progress > .48f && renderedMode != newMode) renderedMode = newMode;
            setAlpha(.68f + Math.abs(progress - .5f) * .64f);
            invalidate();
        });
        setPivotY(0); setAlpha(.72f); setScaleY(.97f);
        animate().scaleY(1f).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
        height.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { renderedMode = newMode; setAlpha(1f); invalidate(); }
        });
        height.start();
    }

    /** 根据当前状态报告期望高度，宽度使用父容器给出的宽度。 */
    @Override protected void onMeasure(int ws, int hs) {
        int desired = targetHeight(mode);
        setMeasuredDimension(MeasureSpec.getSize(ws), resolveSize(desired, hs));
    }

    /** 计算各状态目标高度；全屏状态还会限制在手机可用高度内。 */
    private int targetHeight(Mode value) {
        if (value == Mode.COMPACT) return dp(120);
        if (value == Mode.MONTH) return dp(360);
        int available = getResources().getDisplayMetrics().heightPixels - dp(150);
        return Math.max(dp(420), Math.min(dp(610), available));
    }

    /** 播放月份横向滑出和滑入动画；方向一为下一月，负一为上一月。 */
    private void animateMonth(int direction) {
        if (periodAnimating || getWidth() == 0) return;
        periodAnimating = true;
        animate().translationX(-direction * getWidth() * .20f).alpha(.15f).setDuration(140).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            month = month.plusMonths(direction);
            selected = safeDate(month.getYear(), month.getMonthValue(), Math.min(selected.getDayOfMonth(), month.lengthOfMonth()));
            invalidate();
            setTranslationX(direction * getWidth() * .22f);
            // 先让新月份顺畅滑入，再刷新数据库、农历和节假日数据。
            // 旧实现把这些同步计算夹在滑出与滑入之间，主线程会短暂停住，表现为换月卡顿。
            animate().translationX(0).alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                periodAnimating = false;
                marqueeStart = SystemClock.uptimeMillis();
                if (listener != null) listener.onPeriodChanged();
            }).start();
        }).start();
    }

    /** 绘制星期标题、周数、日期格、事项提示和底部拖动柄。 */
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        marqueeNeeded = false;
        float weekW = showWeekNumbers ? dp(30) : dp(8);
        float cellW = (getWidth() - weekW - dp(8)) / 7f;
        float headerH = dp(28);
        float handleH = dp(20);
        int rows = renderedMode == Mode.COMPACT ? 1 : 6;
        float rowH = (getHeight() - headerH - handleH) / rows;

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(sp(12));
        paint.setColor(Color.rgb(105, 112, 130));
        for (int col = 0; col < 7; col++) c.drawText(weekdays[col], weekW + cellW * (col + .5f), dp(18), paint);

        LocalDate first = month.atDay(1);
        LocalDate gridStart = first.minusDays(first.getDayOfWeek().getValue() - 1L);
        if (renderedMode == Mode.COMPACT) gridStart = selected.minusDays(selected.getDayOfWeek().getValue() - 1L);

        for (int row = 0; row < rows; row++) {
            LocalDate rowDate = gridStart.plusDays(row * 7L);
            if (showWeekNumbers) drawWeek(c, rowDate, row, headerH, rowH);
            for (int col = 0; col < 7; col++) {
                LocalDate date = rowDate.plusDays(col);
                float left = weekW + col * cellW;
                drawDay(c, date, left, headerH + row * rowH, cellW, rowH);
            }
        }
        paint.setColor(Color.rgb(151, 157, 172));
        handleRect.set(getWidth()/2f-dp(24), getHeight()-dp(9), getWidth()/2f+dp(24), getHeight()-dp(5));
        c.drawRoundRect(handleRect, dp(3), dp(3), paint);
        // 只有确实存在溢出文字且处于展开状态时才约每四十毫秒重绘，不启动后台常驻任务。
        if (marqueeNeeded && mode == Mode.EXPANDED && renderedMode == Mode.EXPANDED && !periodAnimating && isShown()) postInvalidateDelayed(40);
    }

    /** 在指定行最左侧绘制年周数。 */
    private void drawWeek(Canvas c, LocalDate date, int row, float headerH, float rowH) {
        int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(sp(10)); paint.setColor(Color.rgb(140,146,161));
        c.drawText(Integer.toString(week), dp(15), headerH + row * rowH + dp(17), paint);
        paint.setTextSize(sp(8)); c.drawText("周", dp(15), headerH + row * rowH + dp(29), paint);
    }

    /** 绘制单个日期格，包括选中圆、今天边框、日期数字以及事项蓝点或标签。 */
    private void drawDay(Canvas c, LocalDate date, float left, float top, float width, float height) {
        float cx = left + width / 2;
        boolean inMonth = YearMonth.from(date).equals(month);
        boolean isSelected = date.equals(selected);
        boolean isToday = date.equals(LocalDate.now());
        if (isSelected) {
            paint.setColor(accent);
            c.drawCircle(cx, top + dp(25), dp(22), paint);
        } else if (isToday) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f)); paint.setColor(accent);
            c.drawCircle(cx, top + dp(25), dp(21), paint); paint.setStyle(Paint.Style.FILL);
        }
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(sp(renderedMode == Mode.EXPANDED ? 17 : 18));
        paint.setColor(isSelected ? Color.WHITE : inMonth ? Color.rgb(30,36,51) : Color.rgb(175,180,191));
        c.drawText(Integer.toString(date.getDayOfMonth()), cx, top + dp(showLunarDates ? 23 : 29), paint);
        String culturalLabel = culturalLabelMap.get(date);
        boolean hasSubLine = showLunarDates || culturalLabel != null;
        if (hasSubLine) {
            // 节日、假日和节气优先占用副行；没有特殊标签时才显示普通农历日号。
            paint.setTextSize(sp(8));
            if (isSelected) paint.setColor(Color.WHITE);
            else if (culturalLabel != null && culturalLabel.startsWith("休·")) paint.setColor(Color.rgb(224,73,86));
            else if (culturalLabel != null && culturalLabel.startsWith("班·")) paint.setColor(Color.rgb(224,139,52));
            else if (culturalLabel != null) paint.setColor(Color.rgb(73,101,210));
            else paint.setColor(inMonth ? Color.rgb(118,125,143) : Color.rgb(190,194,203));
            c.drawText(culturalLabel == null ? LunarDateUtils.compact(date) : culturalLabel, cx, top + dp(37), paint);
        }

        List<Event> events = eventMap.getOrDefault(date, Collections.emptyList());
        if (renderedMode == Mode.EXPANDED && !events.isEmpty()) {
            int max = Math.min(2, events.size());
            for (int i = 0; i < max; i++) {
                float y = top + dp((hasSubLine ? 43 : 39) + i * 18);
                paint.setColor(events.get(i).type.equals("待办") ? Color.rgb(235,159,68) : accent);
                c.drawRoundRect(new RectF(left + dp(3), y, left + width - dp(3), y + dp(15)), dp(4), dp(4), paint);
                paint.setColor(Color.WHITE); paint.setTextSize(sp(8)); paint.setTextAlign(Paint.Align.LEFT);
                drawMarqueeLabel(c, events.get(i).title, left + dp(6), y, width - dp(12));
            }
        } else if (!events.isEmpty()) {
            // 小蓝点放在选中圆或今天边框下方，避免与两种圆形状态重叠；圆形状态也保留日程提示。
            paint.setColor(accent);
            c.drawCircle(cx, top + Math.min(height - dp(4), dp(hasSubLine ? 51 : 47)), dp(2.2f), paint);
        }
    }

    /**
     * 绘制可能溢出的事项名称：左对齐停一秒，随后匀速向左滚动到右边界对齐，
     * 再停一秒后回到开头循环。未溢出的短名称保持静止，完全没有额外重绘。
     */
    private void drawMarqueeLabel(Canvas canvas, String text, float textLeft, float rowTop, float availableWidth) {
        float textWidth = paint.measureText(text); float offset = 0;
        // 只有第三种完全展开状态且当前没有换月动画时才启动滚动；其他状态始终静态左对齐。
        if (textWidth > availableWidth && mode == Mode.EXPANDED && renderedMode == Mode.EXPANDED && !periodAnimating) {
            marqueeNeeded = true;
            float travel = textWidth - availableWidth;
            long scrollDuration = Math.max(800L, Math.round(travel / dp(24) * 1000));
            long cycle = 1000L + scrollDuration + 1000L;
            long elapsed = (SystemClock.uptimeMillis() - marqueeStart) % cycle;
            if (elapsed > 1000L && elapsed < 1000L + scrollDuration) offset = travel * (elapsed - 1000L) / scrollDuration;
            else if (elapsed >= 1000L + scrollDuration) offset = travel;
        }
        int save = canvas.save();
        canvas.clipRect(textLeft, rowTop, textLeft + availableWidth, rowTop + dp(15));
        canvas.drawText(text, textLeft - offset, rowTop + dp(11), paint);
        canvas.restoreToCount(save);
    }

    /** 区分横向换月、纵向三态切换、拖动柄点击和日期点击。 */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) { downY = e.getY(); downX = e.getX(); return true; }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dy = e.getY() - downY;
            float dx = e.getX() - downX;
            if (Math.abs(dx) > dp(46) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                animateMonth(dx < 0 ? 1 : -1);
                return true;
            }
            if (Math.abs(dy) > dp(34) && Math.abs(dy) > Math.abs(dx)) {
                if (dy > 0) setMode(mode == Mode.COMPACT ? Mode.MONTH : Mode.EXPANDED);
                else setMode(mode == Mode.EXPANDED ? Mode.MONTH : Mode.COMPACT);
                return true;
            }
            if (e.getY() > getHeight() - dp(28)) {
                setMode(mode == Mode.COMPACT ? Mode.MONTH : mode == Mode.MONTH ? Mode.EXPANDED : Mode.MONTH);
                return true;
            }
            selectAt(e.getX(), e.getY());
            performClick();
            return true;
        }
        return true;
    }

    /** 向无障碍服务报告一次有效点击。 */
    @Override public boolean performClick() { super.performClick(); return true; }

    /** 把点击坐标换算成网格行列，更新选中日期并通知主页面。 */
    private void selectAt(float x, float y) {
        float weekW = showWeekNumbers ? dp(30) : dp(8);
        if (x < weekW || y < dp(28)) return;
        float cellW = (getWidth() - weekW - dp(8)) / 7f;
        int col = Math.max(0, Math.min(6, (int)((x - weekW) / cellW)));
        int rows = mode == Mode.COMPACT ? 1 : 6;
        float rowH = (getHeight() - dp(48)) / rows;
        int row = Math.max(0, Math.min(rows - 1, (int)((y - dp(28)) / rowH)));
        LocalDate start = month.atDay(1).minusDays(month.atDay(1).getDayOfWeek().getValue() - 1L);
        if (mode == Mode.COMPACT) start = selected.minusDays(selected.getDayOfWeek().getValue() - 1L);
        selected = start.plusDays(row * 7L + col);
        month = YearMonth.from(selected);
        invalidate();
        if (listener != null) listener.onDateSelected(selected);
    }

    /** 把与屏幕密度无关的尺寸转换为实际像素。 */
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    /** 把字体尺寸转换为考虑用户字体缩放设置的实际像素。 */
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
}
