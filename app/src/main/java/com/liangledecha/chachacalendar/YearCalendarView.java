package com.liangledecha.chachacalendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 全年十二个月总览控件。
 *
 * <p>控件使用画布直接绘制三列四行的月份缩略图，支持左右滑动换年，
 * 也支持点击某个月份进入相应月视图。</p>
 */
public final class YearCalendarView extends View {
    /** 把年份变化和月份点击事件通知主页面。 */
    public interface Listener { void onYearChanged(int year); void onMonthSelected(YearMonth month); }
    /** 所有文字、圆形和颜色共用的画笔，开启抗锯齿保证边缘平滑。 */
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 当前正在展示的年份，首次打开时使用系统年份。 */
    private int year = LocalDate.now().getYear();
    /** 手指按下时的横纵坐标，用于区分点击和横向滑动。 */
    private float downX, downY;
    /** 换年动画是否正在进行，防止连续手势造成动画叠加。 */
    private boolean animating;
    /** 当前主题主色，用于月份标题和当天圆圈。 */
    private int accent = Color.rgb(95, 143, 105);
    /** 主页面注册的事件监听器。 */
    private Listener listener;

    /** 初始化全年视图的字体和透明背景。 */
    public YearCalendarView(Context context) { super(context); paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)); setBackgroundColor(Color.TRANSPARENT); }
    /** 注册事件监听器。 */
    public void setListener(Listener value) { listener = value; }
    /** 返回当前展示年份。 */
    public int getYear() { return year; }
    /** 跳转到指定年份并请求重新绘制。 */
    public void setYear(int value) { year = value; invalidate(); }
    /** 应用用户选择的主题主色。 */
    public void setAccent(int color) { accent = color; invalidate(); }

    /** 把可用区域平均分成十二格，并逐个绘制月份。 */
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float outer = dp(4), colW = (getWidth() - outer * 2) / 3f, rowH = getHeight() / 4f;
        for (int month = 1; month <= 12; month++) {
            int index = month - 1, col = index % 3, row = index / 3;
            drawMonth(canvas, YearMonth.of(year, month), outer + col * colW, row * rowH, colW, rowH);
        }
    }

    /** 在给定矩形范围内绘制一个月份的标题、星期和全部日期数字。 */
    private void drawMonth(Canvas canvas, YearMonth ym, float left, float top, float width, float height) {
        float side = dp(7), usable = width - side * 2, cellW = usable / 7f;
        paint.setTextAlign(Paint.Align.LEFT); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(sp(14)); paint.setColor(accent);
        canvas.drawText(ym.getMonthValue() + "月", left + side, top + dp(20), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(sp(7.5f)); paint.setColor(Color.rgb(145,150,164));
        String[] week = {"一","二","三","四","五","六","日"};
        for (int i=0;i<7;i++) canvas.drawText(week[i], left + side + cellW*(i+.5f), top+dp(34), paint);
        int offset = ym.atDay(1).getDayOfWeek().getValue() - 1;
        float cellH = Math.min(dp(16), (height - dp(39)) / 6f);
        LocalDate today = LocalDate.now();
        for (int day=1; day<=ym.lengthOfMonth(); day++) {
            int pos = offset + day - 1, c = pos % 7, r = pos / 7;
            float cx = left + side + cellW*(c+.5f), cy = top + dp(45) + cellH*r;
            if (today.getYear()==year && today.getMonthValue()==ym.getMonthValue() && today.getDayOfMonth()==day) {
                paint.setColor(accent); canvas.drawCircle(cx, cy-dp(2.5f), dp(7), paint); paint.setColor(Color.WHITE);
            } else paint.setColor(c >= 5 ? Color.rgb(118,123,139) : Color.rgb(39,45,58));
            paint.setTextSize(sp(7.5f)); canvas.drawText(Integer.toString(day), cx, cy, paint);
        }
    }

    /** 识别左右换年手势和月份点击手势。 */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction()==MotionEvent.ACTION_DOWN) { downX=event.getX(); downY=event.getY(); return true; }
        if (event.getAction()==MotionEvent.ACTION_UP) {
            float dx=event.getX()-downX, dy=event.getY()-downY;
            if (Math.abs(dx)>dp(48) && Math.abs(dx)>Math.abs(dy)) { animateYear(dx<0?1:-1); return true; }
            if (Math.abs(dx)<dp(12) && Math.abs(dy)<dp(12)) {
                int col=Math.max(0,Math.min(2,(int)(event.getX()/(getWidth()/3f))));
                int row=Math.max(0,Math.min(3,(int)(event.getY()/(getHeight()/4f))));
                int month=row*3+col+1;
                if (listener!=null) listener.onMonthSelected(YearMonth.of(year,month));
                performClick();
            }
            return true;
        }
        return true;
    }

    /** 向无障碍服务报告一次有效点击。 */
    @Override public boolean performClick() { super.performClick(); return true; }

    /** 播放旧年份滑出、新年份滑入的位移和透明度动画。方向一表示下一年，负一表示上一年。 */
    private void animateYear(int direction) {
        if (animating || getWidth()==0) return;
        animating=true;
        animate().translationX(-direction*getWidth()*.22f).alpha(.12f).setDuration(150).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            year+=direction; invalidate(); if (listener!=null) listener.onYearChanged(year);
            setTranslationX(direction*getWidth()*.24f);
            animate().translationX(0).alpha(1).setDuration(230).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> animating=false).start();
        }).start();
    }

    /** 把与屏幕密度无关的尺寸转换为实际像素。 */
    private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    /** 把字体尺寸转换为考虑用户字体缩放设置的实际像素。 */
    private float sp(float v) { return v*getResources().getDisplayMetrics().scaledDensity; }
}
