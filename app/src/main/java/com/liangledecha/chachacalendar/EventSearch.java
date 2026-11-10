package com.liangledecha.chachacalendar;

import java.time.LocalDate;
import java.util.Locale;

/** 离线搜索已保存的事项系列。 */
final class EventSearch {
    static final String[] TYPES = {"全部类型", "待办", "普通日程", "纪念日", "生日", "倒数日"};
    static boolean matches(Event e, String name, int type, int completion, int lunar, LocalDate from, LocalDate until, LocalDate today) {
        if (!e.title.toLowerCase(Locale.ROOT).contains(name.trim().toLowerCase(Locale.ROOT))) return false;
        if (type > 0 && !e.type.equals(TYPES[type])) return false;
        if (completion > 0 && (!e.supportsCompletion() || e.isCompletedOn(today) != (completion == 2))) return false;
        if (lunar > 0 && e.lunarBased != (lunar == 2)) return false;
        if (from == null && until == null) return true;
        LocalDate start = from == null ? e.date : from;
        if (start.isBefore(e.date)) start = e.date;
        LocalDate occurrence = e.nextDateIgnoringCompletion(start);
        return !occurrence.isBefore(start) && (until == null || !occurrence.isAfter(until));
    }
}
