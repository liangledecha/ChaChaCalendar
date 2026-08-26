package com.liangledecha.chachacalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 传统节日、公历节日与二十四节气的离线计算工具。
 *
 * <p>节日名称只依赖日期，二十四节气使用通行的回归年分钟常数计算，
 * 不需要网络。法定放假和补班属于每年政策安排，由 {@link HolidayRepository} 负责。</p>
 */
public final class CalendarCultureUtils {
    /** 从小寒到冬至的二十四节气名称。 */
    private static final String[] TERM_NAMES = {"小寒","大寒","立春","雨水","惊蛰","春分","清明","谷雨","立夏","小满","芒种","夏至","小暑","大暑","立秋","处暑","白露","秋分","寒露","霜降","立冬","小雪","大雪","冬至"};
    /** 各节气相对基准时刻的分钟数，与回归年长度共同推算公历日期。 */
    private static final int[] TERM_MINUTES = {0,21208,42467,63836,85337,107014,128867,150921,173149,195551,218072,240693,263343,285989,308563,331033,353350,375494,397447,419210,440795,462224,483532,504758};
    /** 节气算法的协调世界时基准。 */
    private static final Instant TERM_BASE = LocalDateTime.of(1900,1,6,2,5).toInstant(ZoneOffset.UTC);

    /** 工具类不需要创建对象。 */
    private CalendarCultureUtils() { }

    /** 返回某一天的固定公历节日和传统农历节日，可能同时包含两项。 */
    public static List<String> festivalNames(LocalDate date) {
        ArrayList<String> names = new ArrayList<>();
        int month = date.getMonthValue(), day = date.getDayOfMonth();
        if (month == 1 && day == 1) names.add("元旦");
        if (month == 3 && day == 8) names.add("妇女节");
        if (month == 5 && day == 1) names.add("劳动节");
        if (month == 5 && day == 4) names.add("青年节");
        if (month == 6 && day == 1) names.add("儿童节");
        if (month == 9 && day == 10) names.add("教师节");
        if (month == 10 && day == 1) names.add("国庆节");

        LunarDateUtils.LunarDate lunar = LunarDateUtils.fromSolar(date);
        if (!lunar.leapMonth) {
            if (lunar.month == 1 && lunar.day == 1) names.add("春节");
            if (lunar.month == 1 && lunar.day == 15) names.add("元宵节");
            if (lunar.month == 5 && lunar.day == 5) names.add("端午节");
            if (lunar.month == 7 && lunar.day == 7) names.add("七夕");
            if (lunar.month == 7 && lunar.day == 15) names.add("中元节");
            if (lunar.month == 8 && lunar.day == 15) names.add("中秋节");
            if (lunar.month == 9 && lunar.day == 9) names.add("重阳节");
            if (lunar.month == 12 && lunar.day == 8) names.add("腊八节");
            LunarDateUtils.LunarDate tomorrow = LunarDateUtils.fromSolar(date.plusDays(1));
            if (lunar.month == 12 && tomorrow.month == 1 && tomorrow.day == 1) names.add("除夕");
        }
        return names;
    }

    /** 返回某天对应的节气名称；不是节气时返回空。 */
    public static String solarTerm(LocalDate date) {
        int year = date.getYear();
        if (year < 1900 || year > 2100) return "";
        for (int index = 0; index < TERM_NAMES.length; index++) {
            long milliseconds = Math.round(31556925974.7d * (year - 1900)) + TERM_MINUTES[index] * 60000L;
            LocalDate termDate = TERM_BASE.plusMillis(milliseconds).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (termDate.equals(date)) return TERM_NAMES[index];
        }
        return "";
    }
}
