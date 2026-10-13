package com.liangledecha.chachacalendar;

/** 周历与待办的系统注册入口；与月历共享日期格，复用现有事项列表服务。 */
public final class WeekCalendarWidgetProvider extends CalendarGridWidgetProvider {
    @Override protected boolean showsMonth() { return false; }
}
