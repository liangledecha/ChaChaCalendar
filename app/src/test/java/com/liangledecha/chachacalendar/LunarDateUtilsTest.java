package com.liangledecha.chachacalendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.time.LocalDate;

/** 农历换算回归测试，防止临界朔日再次出现整体偏移一天。 */
public final class LunarDateUtilsTest {
    /** 系统民用历书确认 2027 年正月初六对应 2 月 11 日。 */
    @Test public void solarToLunarUsesCivilCalendarBoundary() {
        LunarDateUtils.LunarDate lunar = LunarDateUtils.fromSolar(LocalDate.of(2027, 2, 11));
        assertEquals(2027, lunar.year); assertEquals(1, lunar.month); assertEquals(6, lunar.day); assertFalse(lunar.leapMonth);
    }

    /** 农历转公历必须与反向换算落在同一天，不能得到原来的 2 月 12 日。 */
    @Test public void lunarToSolarUsesCivilCalendarBoundary() {
        assertEquals(LocalDate.of(2027, 2, 11), LunarDateUtils.toSolar(2027, 1, 6, false));
    }

    /** 2027 农历年没有闰月，选择器不应出现人工闰月开关。 */
    @Test public void leapMonthIsDetectedFromLunarYear() { assertEquals(0, LunarDateUtils.leapMonthOfYear(2027)); }

    /** 旧版已保存成 2 月 12 日的事项，读取后也应自动恢复为正确的 2 月 11 日。 */
    @Test public void oldStoredOccurrenceIsNormalized() {
        Event event = new Event(1, "测试", LocalDate.of(2027, 2, 12), "生日", -1, Event.YEARLY,
                null, false, -1, true, 1, 6, false);
        assertEquals(LocalDate.of(2027, 2, 11), event.nextDate(LocalDate.of(2027, 1, 1)));
    }
}
