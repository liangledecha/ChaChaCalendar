package com.liangledecha.chachacalendar;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;

/** 验证重复待办完成一次后会进入下一周期，同时保留每月原始日号。 */
public final class EventRepeatTodoTest {
    /** 未勾选的每周待办必须停在已过期周期，不能自动跳到未来。 */
    @Test public void uncheckedWeeklyTodoKeepsOverdueOccurrence() {
        Event event = todo(LocalDate.of(2026, 8, 24), Event.WEEKLY);
        assertEquals(LocalDate.of(2026, 8, 24), event.nextDate(LocalDate.of(2026, 9, 1)));
    }

    /** 漏掉多个周期时每次勾选只推进一次，下一次仍可继续显示过期。 */
    @Test public void missedWeeklyTodoAdvancesOnlyOneCycle() {
        Event event = todo(LocalDate.of(2026, 8, 24), Event.WEEKLY);
        event.completedThrough = LocalDate.of(2026, 8, 24);
        assertEquals(LocalDate.of(2026, 8, 31), event.nextDate(LocalDate.of(2026, 9, 1)));
    }

    /** 每周待办完成本周后应跳到下周同一天。 */
    @Test public void weeklyTodoMovesToNextOccurrence() {
        Event event = todo(LocalDate.of(2026, 8, 31), Event.WEEKLY);
        event.completedThrough = LocalDate.of(2026, 8, 31);
        assertEquals(LocalDate.of(2026, 9, 7), event.nextDate(LocalDate.of(2026, 8, 31)));
    }

    /** 三十一日待办即使二月按月末完成，三月仍应恢复到三十一日。 */
    @Test public void monthlyTodoKeepsOriginalDay() {
        Event event = todo(LocalDate.of(2026, 1, 31), Event.MONTHLY);
        event.completedThrough = LocalDate.of(2026, 2, 28);
        assertEquals(LocalDate.of(2026, 3, 31), event.nextDate(LocalDate.of(2026, 2, 1)));
    }

    /** 每季待办完成本季后应跳到三个月后的同一日。 */
    @Test public void quarterlyTodoMovesThreeMonths() {
        Event event = todo(LocalDate.of(2026, 1, 31), Event.QUARTERLY);
        event.completedThrough = LocalDate.of(2026, 1, 31);
        assertEquals(LocalDate.of(2026, 4, 30), event.nextDate(LocalDate.of(2026, 1, 31)));
    }

    /** 每半年待办完成本次后应跳到六个月后的同一日。 */
    @Test public void halfYearlyTodoMovesSixMonths() {
        Event event = todo(LocalDate.of(2026, 2, 28), Event.HALF_YEARLY);
        event.completedThrough = LocalDate.of(2026, 2, 28);
        assertEquals(LocalDate.of(2026, 8, 28), event.nextDate(LocalDate.of(2026, 2, 28)));
    }

    /** 创建测试待办，其他字段使用不影响重复计算的默认值。 */
    private static Event todo(LocalDate date, String repeatRule) {
        return new Event(1, "测试待办", date, "待办", -1, repeatRule,
                null, false, -1, false, 0, 0, false);
    }
}
