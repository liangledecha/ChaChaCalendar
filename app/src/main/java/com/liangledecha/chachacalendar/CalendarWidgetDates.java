package com.liangledecha.chachacalendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 小组件的纯日期与待办投影；不依赖桌面进程或数据库，便于验证跨月和重复周期。 */
final class CalendarWidgetDates {
    static final int DAYS_PER_WEEK = 7;
    /** 只有展示适配由此类负责，农历换算和重复规则仍由已有领域模型负责。 */
    private CalendarWidgetDates() { }

    /** 周一作为一周起点，与应用月历保持一致，跨年时也连续展示七天。 */
    static LocalDate weekStart(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    /** 月历补齐首尾相邻月份，按实际需要生成四至六行，避免多余空白。 */
    static List<LocalDate> month(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        LocalDate start = weekStart(month.atDay(1));
        int occupied = month.atDay(1).getDayOfWeek().getValue() - 1 + month.lengthOfMonth();
        int count = ((occupied + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK) * DAYS_PER_WEEK;
        return consecutive(start, count);
    }

    /** 本周包含今天，末尾可能跨入下一月或下一年。 */
    static List<LocalDate> week(LocalDate today) {
        return consecutive(weekStart(today), DAYS_PER_WEEK);
    }

    /** 只筛选已按显示窗口读取的待办，排除本次已完成的重复待办并按到期时间排序。 */
    static List<Event> recentTodos(List<Event> visible, LocalDate today) {
        List<Event> result = new ArrayList<>();
        for (Event event : visible) {
            if (event.isTodo() && !event.isCompletedOn(today)) result.add(event);
        }
        // 使用明确的今天作为周期基准，过期待办排在未来待办前，重复任务不擅自跳周期。
        result.sort(Comparator.comparing((Event event) -> event.nextDateAt(today, today))
                .thenComparing(event -> event.time == null ? java.time.LocalTime.MIDNIGHT : event.time)
                .thenComparingLong(event -> event.id));
        return result;
    }

    /** 星期六、星期日始终使用周末配色；调休信息不改变星期本身的语义。 */
    static boolean isWeekend(LocalDate date) { return date.getDayOfWeek().getValue() >= 6; }

    /** 按自然日递增，避免用固定毫秒数在夏令时切换日产生偏移。 */
    private static List<LocalDate> consecutive(LocalDate start, int count) {
        List<LocalDate> dates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) dates.add(start.plusDays(index));
        return dates;
    }
}
