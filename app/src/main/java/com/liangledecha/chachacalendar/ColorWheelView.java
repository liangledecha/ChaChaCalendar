package com.liangledecha.chachacalendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** 原生圆形取色盘：角度选择色相，离圆心距离选择饱和度。 */
public final class ColorWheelView extends View {
    /** 选择颜色后通知设置页面更新预览。 */
    public interface Listener { void onColorChanged(int color); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue, saturation, value = .62f;
    private Listener listener;

    public ColorWheelView(Context context) {
        super(context);
        marker.setStyle(Paint.Style.STROKE); marker.setStrokeWidth(dp(2)); marker.setColor(Color.WHITE);
        setColor(Color.rgb(95, 143, 105));
    }
    /** 设置初始或已保存的颜色。 */
    public void setColor(int color) { float[] hsv = new float[3]; Color.colorToHSV(color, hsv); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; invalidate(); }
    /** 返回当前选择色，供明度滑杆和保存按钮使用。 */
    public int getColor() { return Color.HSVToColor(new float[]{hue, saturation, value}); }
    /** 修改明度，不改变用户已选的色相和饱和度。 */
    public void setValue(float v) { value = Math.max(.15f, Math.min(1f, v)); changed(); }
    public float getValue() { return value; }
    public void setListener(Listener value) { listener = value; }
    @Override protected void onDraw(Canvas canvas) {
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(4), cx = getWidth() / 2f, cy = getHeight() / 2f;
        int[] colors = {Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED};
        paint.setShader(new SweepGradient(cx, cy, colors, null)); canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        // 半透明白色中心叠层把“距离中心＝低饱和度”直观表现出来。
        paint.setColor(Color.argb(210, 255, 255, 255)); canvas.drawCircle(cx, cy, radius * .18f, paint);
        double angle = Math.toRadians(hue); float distance = radius * saturation;
        canvas.drawCircle(cx + (float)Math.cos(angle) * distance, cy + (float)Math.sin(angle) * distance, dp(7), marker);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN && event.getAction() != MotionEvent.ACTION_MOVE) return true;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, dx = event.getX() - cx, dy = event.getY() - cy;
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(4);
        hue = (float)((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
        saturation = Math.min(1f, (float)Math.hypot(dx, dy) / radius); changed(); return true;
    }
    private void changed() { invalidate(); if (listener != null) listener.onColorChanged(getColor()); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
