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
}
