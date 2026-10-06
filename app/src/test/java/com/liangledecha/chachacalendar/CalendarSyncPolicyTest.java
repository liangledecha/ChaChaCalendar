package com.liangledecha.chachacalendar;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalendarSyncPolicyTest {
    @Test public void snapshotKeepsOriginalDateEvenWhenTitleContainsSeparators() {
        assertEquals("1700000000000", CalendarSyncPolicy.field("4:会:议\n13:17000000000000:", 1));
        assertEquals("", CalendarSyncPolicy.field("4:会:议\n13:17000000000000:", 2));
        assertNull(CalendarSyncPolicy.field("999:x", 0));
        assertNull(CalendarSyncPolicy.field("-1:x", 0));
    }
    @Test public void defaultNeverImportsAndDirectionsAreExclusive() {
        for (int value : new int[]{0, -1, 3, Integer.MAX_VALUE}) {
            assertTrue(CalendarSyncPolicy.sends(value));
            assertFalse(CalendarSyncPolicy.receives(value));
        }
        assertFalse(CalendarSyncPolicy.sends(1));
        assertTrue(CalendarSyncPolicy.receives(1));
        assertTrue(CalendarSyncPolicy.sends(2));
        assertTrue(CalendarSyncPolicy.receives(2));
    }
    @Test public void unsupportedRecurrencesAreNotFlattened() {
        assertEquals(Event.NONE, CalendarSyncPolicy.repeat(null));
        assertEquals(Event.QUARTERLY, CalendarSyncPolicy.repeat("INTERVAL=3;FREQ=MONTHLY"));
        assertEquals(Event.DAILY, CalendarSyncPolicy.repeat("FREQ=DAILY;INTERVAL=1"));
        for (String rule : new String[]{"FREQ=WEEKLY;BYDAY=MO,WE", "FREQ=DAILY;COUNT=2", "FREQ=YEARLY;UNTIL=20270101", "FREQ=MONTHLY;INTERVAL=0", "FREQ=DAILY;FREQ=WEEKLY", "FREQ=DAILY;INTERVAL=x"})
            assertNull(rule, CalendarSyncPolicy.repeat(rule));
    }
}
