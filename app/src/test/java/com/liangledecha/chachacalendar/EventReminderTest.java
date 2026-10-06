package com.liangledecha.chachacalendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class EventReminderTest {
    @Test public void reminderDoesNotSkipUndoneTodoOrRepeatOneOff() {
        LocalDate day = LocalDate.of(2026, 9, 28);
        Event todo = new Event(1, "待办", day, "待办", -1, Event.DAILY,
                LocalTime.of(10, 0), false, -1, false, 0, 0, false);
        assertEquals(day.atTime(10, 0), todo.nextLocalReminder(day.atTime(9, 59)));
        assertNull(todo.nextLocalReminder(day.atTime(10, 1)));
        todo.completedThrough = day;
        assertEquals(day.plusDays(1).atTime(10, 0), todo.nextLocalReminder(day.atTime(10, 1)));

        Event birthday = new Event(2, "生日", day, "生日", -1, Event.YEARLY,
                null, false, -1, false, 0, 0, false);
        assertEquals(day.plusYears(1).atTime(9, 0), birthday.nextLocalReminder(day.atTime(9, 1)));
        Event once = new Event(3, "会议", day, "普通日程", -1, Event.NONE,
                LocalTime.of(10, 0), false, -1, false, 0, 0, false);
        assertNull(once.nextLocalReminder(LocalDateTime.of(2026, 9, 28, 10, 1)));
    }
}
