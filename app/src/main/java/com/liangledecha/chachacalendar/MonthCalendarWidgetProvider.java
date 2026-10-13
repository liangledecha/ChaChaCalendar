package com.liangledecha.chachacalendar;

/** 月历的系统注册入口；日期计算、点击和刷新生命周期由共享接收器承担。 */
public final class MonthCalendarWidgetProvider extends CalendarGridWidgetProvider {
    @Override protected boolean showsMonth() { return true; }
}
