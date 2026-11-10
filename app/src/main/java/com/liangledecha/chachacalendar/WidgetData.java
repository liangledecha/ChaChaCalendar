package com.liangledecha.chachacalendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WidgetData {
    static final int AGENDA = 0, TODOS = 1, COUNTDOWN = 2;

    // 与应用待办页一致：显示窗口和完成状态不删除待办清单中的记录。
    static List<Event> todos(List<Event> all, LocalDate today) {
        List<Event> result = new ArrayList<>();
        for (Event event : all) if (event.supportsCompletion()) result.add(event);
        result.sort(order(today));
        return result;
    }

    static List<Event> countdowns(List<Event> all, LocalDate today) {
        List<Event> result = new ArrayList<>(all);
        result.sort(order(today));
        return result;
    }

    private static Comparator<Event> order(LocalDate today) {
        Map<Event, LocalDateTime> dates = new HashMap<>();
        return Comparator.comparing(event -> dates.computeIfAbsent(event, item -> LocalDateTime.of(item.nextDateAt(today, today),
                item.time == null ? java.time.LocalTime.MIDNIGHT : item.time)));
    }

    static Event pinned(List<Event> all, long id) {
        for (Event event : all) if (event.id == id) return event;
        return null;
    }

    static String countdown(Event event, LocalDate today) {
        return countdown(event.nextDateAt(today, today), today);
    }

    static String countdown(LocalDate occurrence, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, occurrence);
        return days == 0 ? "今天" : Math.abs(days) + (days > 0 ? "天后" : "天前");
    }

}
