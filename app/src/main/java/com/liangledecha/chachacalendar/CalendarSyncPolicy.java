package com.liangledecha.chachacalendar;

/** 方向和可无损接入的系统重复规则；未知设置按只向外同步处理。 */
final class CalendarSyncPolicy {
    static final int OUTBOUND = 0, INBOUND = 1, BOTH = 2;
    static final String[] LABELS = {"茶茶日历→系统日历", "系统日历→茶茶日历", "双向同步"};
    static int normalize(int mode) { return mode == INBOUND || mode == BOTH ? mode : OUTBOUND; }
    static boolean sends(int mode) { return normalize(mode) != INBOUND; }
    static boolean receives(int mode) { return normalize(mode) != OUTBOUND; }

    /** 快照字段使用长度前缀；标题中的冒号、换行不能改变后续日期字段的位置。 */
    static String field(String snapshot, int index) {
        if (snapshot == null || index < 0) return null;
        try {
            int offset = 0;
            for (int i = 0; i <= index; i++) {
                int colon = snapshot.indexOf(':', offset);
                if (colon < offset) return null;
                int length = Integer.parseInt(snapshot.substring(offset, colon));
                if (length < 0 || length > snapshot.length() - colon - 1) return null;
                offset = colon + 1;
                if (i == index) return snapshot.substring(offset, offset + length);
                offset += length;
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    /** 不静默丢掉 COUNT、UNTIL、BYDAY 等本地模型无法表达的限定。 */
    static String repeat(String rrule) {
        if (rrule == null || rrule.isEmpty()) return Event.NONE;
        String frequency = null;
        int interval = 1;
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (String part : rrule.toUpperCase(java.util.Locale.ROOT).split(";", -1)) {
            String[] pair = part.split("=", -1);
            if (pair.length != 2 || !keys.add(pair[0])) return null;
            if (pair[0].equals("FREQ")) frequency = pair[1];
            else if (pair[0].equals("INTERVAL")) {
                try { interval = Integer.parseInt(pair[1]); } catch (NumberFormatException error) { return null; }
            } else return null;
        }
        if ("MONTHLY".equals(frequency)) {
            if (interval == 1) return Event.MONTHLY;
            if (interval == 3) return Event.QUARTERLY;
            if (interval == 6) return Event.HALF_YEARLY;
        }
        if (interval != 1) return null;
        if ("DAILY".equals(frequency)) return Event.DAILY;
        if ("WEEKLY".equals(frequency)) return Event.WEEKLY;
        if ("YEARLY".equals(frequency)) return Event.YEARLY;
        return null;
    }
}
