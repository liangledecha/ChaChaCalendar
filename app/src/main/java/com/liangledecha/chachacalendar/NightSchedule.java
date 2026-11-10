package com.liangledecha.chachacalendar;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.Locale;

/** 每日按本地钟表时间切换；开始包含，结束不包含。 */
final class NightSchedule {
    static final int OFF = 0, ON = 1, TIMED = 2;
    static final int DEFAULT_START = 22 * 60, DEFAULT_END = 7 * 60;

    static boolean valid(int start, int end) {
        return start >= 0 && start < 1440 && end >= 0 && end < 1440 && start != end;
    }

    static boolean active(int mode, int start, int end, LocalTime now) {
        if (mode == ON) return true;
        if (mode != TIMED || !valid(start, end)) return false;
        int minute = now.getHour() * 60 + now.getMinute();
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    static ZonedDateTime nextChange(int start, int end, ZonedDateTime now) {
        if (!valid(start, end)) throw new IllegalArgumentException("夜间模式起止时间无效");
        ZonedDateTime next = null;
        for (int minute : new int[]{start, end}) {
            ZonedDateTime candidate = now.toLocalDate().atTime(minute / 60, minute % 60).atZone(now.getZone());
            if (!candidate.isAfter(now) && candidate.withLaterOffsetAtOverlap().isAfter(now))
                candidate = candidate.withLaterOffsetAtOverlap();
            if (!candidate.isAfter(now)) candidate = now.toLocalDate().plusDays(1)
                    .atTime(minute / 60, minute % 60).atZone(now.getZone());
            if (next == null || candidate.isBefore(next)) next = candidate;
        }
        // 夏令时回拨可能再次穿过同一时段，转换点也重新判定一次。
        ZoneOffsetTransition transition = now.getZone().getRules().nextTransition(now.toInstant());
        if (transition != null && transition.getInstant().isBefore(next.toInstant()))
            next = transition.getInstant().atZone(now.getZone());
        return next;
    }

    static String format(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
}
