package com.liangledecha.chachacalendar;

final class WidgetActionToken {
    static boolean accepts(String saved, String supplied) {
        return saved != null && !saved.isEmpty() && saved.equals(supplied);
    }
}
