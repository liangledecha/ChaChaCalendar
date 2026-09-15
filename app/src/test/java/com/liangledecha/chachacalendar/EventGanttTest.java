package com.liangledecha.chachacalendar;

import org.junit.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.Assert.assertEquals;

/** 验证甘特计划区间会随重复实例整体平移，并保持原持续时间。 */
public final class EventGanttTest {
    @Test public void repeatedOccurrenceKeepsPlannedDuration() {
        LocalDate base = LocalDate.of(2026, 9, 15);
        LocalDateTime start = LocalDateTime.of(2026, 9, 14, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 16, 18, 0);
        Event event = new Event(1, "周计划", base, "待办", -1, Event.WEEKLY, LocalTime.of(9, 0),
                false, -1, false, 0, 0, false, start, end);
        LocalDate occurrence = base.plusWeeks(2);
        assertEquals(start.plusWeeks(2), event.ganttStart(occurrence));
        assertEquals(Duration.between(start, end), Duration.between(event.ganttStart(occurrence), event.ganttEnd(occurrence)));
    }

    /** 公历闰日周年按年份计算，不能因下一年落在二月二十八日而少算。 */
    @Test public void anniversaryUsesOccurrenceYear() {
        Event anniversary = new Event(2, "纪念", LocalDate.of(2024, 2, 29), "纪念日", -1, Event.YEARLY,
                null, false, -1, false, 0, 0, false);
        assertEquals(1, anniversary.anniversaryAt(LocalDate.of(2025, 2, 28)));
        Event todo = new Event(3, "待办", LocalDate.of(2024, 2, 29), "待办", -1, Event.NONE,
                LocalTime.of(9, 0), false, -1, false, 0, 0, false);
        assertEquals(-1, todo.anniversaryAt(LocalDate.of(2025, 2, 28)));
    }

    /** 发生日在屏幕外时，只要九月一日至十八日的计划条与窗口相交，搜索范围仍必须包含它。 */
    @Test public void longBarRemainsDiscoverableBeforeOccurrenceDate() {
        LocalDate occurrence = LocalDate.of(2026, 9, 15);
        Event event = new Event(4, "跨日事项", occurrence, "普通日程", -1, Event.NONE, LocalTime.of(9, 0),
                false, -1, false, 0, 0, false,
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 18, 18, 0));
        LocalDate searchStart = event.ganttSearchStart(LocalDate.of(2026, 9, 4));
        LocalDate searchEnd = event.ganttSearchEnd(LocalDate.of(2026, 9, 8));
        org.junit.Assert.assertFalse(occurrence.isBefore(searchStart));
        org.junit.Assert.assertFalse(occurrence.isAfter(searchEnd));
    }
}
