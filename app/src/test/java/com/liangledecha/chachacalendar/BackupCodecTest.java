package com.liangledecha.chachacalendar;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BackupCodecTest {
    @Test public void scheduleCompletionSurvivesExportWithoutMovingAnchor() throws Exception {
        Event event = new Event(9, "普通日程", LocalDate.of(2026, 1, 31), "普通日程", -1, Event.MONTHLY,
                LocalTime.of(9, 0), false, -1, false, 0, 0, false);
        event.completedThrough = LocalDate.of(2026, 2, 28);
        Event decoded = BackupCodec.decode(new ByteArrayInputStream(BackupCodec.encode(Collections.singletonList(event), Collections.emptyMap()))).events.get(0);
        assertEquals(LocalDate.of(2026, 1, 31), decoded.date);
        assertEquals(LocalDate.of(2026, 3, 31), decoded.nextDate(LocalDate.of(2026, 3, 1)));
        Event once = new Event(10, "已完成普通日程", decoded.date, "普通日程", -1, Event.NONE, null, true, -1, false, 0, 0, false);
        assertEquals(true, BackupCodec.decode(new ByteArrayInputStream(BackupCodec.encode(Collections.singletonList(once), Collections.emptyMap()))).events.get(0).completed);
    }
    @Test public void roundTripAndRejectCorruption() throws Exception {
        Event event = new Event(7, "会议\n茶茶", LocalDate.of(2026, 9, 21), "待办", 7, Event.MONTHLY,
                LocalTime.of(9, 30), false, 88, false, 0, 0, false,
                LocalDateTime.of(2026, 9, 21, 9, 30), LocalDateTime.of(2026, 9, 22, 10, 0));
        event.completedThrough = LocalDate.of(2026, 8, 21);
        byte[] bytes = BackupCodec.encode(Collections.singletonList(event), Collections.singletonMap("week_numbers", "true"));
        BackupCodec.Data decoded = BackupCodec.decode(new ByteArrayInputStream(bytes));
        assertEquals("会议\n茶茶", decoded.events.get(0).title);
        assertEquals(LocalDate.of(2026, 8, 21), decoded.events.get(0).completedThrough);
        assertEquals("true", decoded.settings.get("week_numbers"));
        bytes[20] ^= 1;
        try { BackupCodec.decode(new ByteArrayInputStream(bytes)); fail("损坏的备份必须被拒绝"); }
        catch (IOException expected) { assertEquals("备份校验失败，文件可能已损坏", expected.getMessage()); }
    }
}
