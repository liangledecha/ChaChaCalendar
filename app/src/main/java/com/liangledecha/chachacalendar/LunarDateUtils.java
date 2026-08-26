package com.liangledecha.chachacalendar;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.LunarYear;
import com.nlf.calendar.Solar;

import java.time.LocalDate;

/**
 * 公历与中国农历之间的本地换算工具。
 *
 * <p>换算使用离线中国民用农历算法，不访问网络，也不把日期发送给任何服务。
 * 这样能避开安卓天文农历在临界朔日与大陆系统民用历书相差一天的问题。
 * 农历年份使用对应春节所在的公历年份表示，例如“农历二〇二六年正月”。</p>
 */
public final class LunarDateUtils {
    /** 十二个农历月份的中文名称。 */
    private static final String[] MONTH_NAMES = {"正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月"};
    /** 农历日期个位使用的中文数字。 */
    private static final String[] DAY_DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** 工具类只提供静态方法，不需要创建对象。 */
    private LunarDateUtils() { }

    /** 一组完整农历字段，用于在界面、模型和数据库之间传递日期。 */
    public static final class LunarDate {
        public final int year;
        public final int month;
        public final int day;
        public final boolean leapMonth;

        public LunarDate(int year, int month, int day, boolean leapMonth) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.leapMonth = leapMonth;
        }
    }

    /** 把一个公历日期转换成同一天的农历字段。 */
    public static LunarDate fromSolar(LocalDate solarDate) {
        Lunar lunar = Solar.fromYmd(solarDate.getYear(), solarDate.getMonthValue(), solarDate.getDayOfMonth()).getLunar();
        return new LunarDate(lunar.getYear(), Math.abs(lunar.getMonth()), lunar.getDay(), lunar.getMonth() < 0);
    }

    /**
     * 把农历字段转换成公历日期。
     *
     * @return 字段有效时返回公历日期；不存在的闰月或日期返回空
     */
    public static LocalDate toSolar(int lunarYear, int lunarMonth, int lunarDay, boolean leapMonth) {
        if (lunarMonth < 1 || lunarMonth > 12 || lunarDay < 1 || lunarDay > 30) return null;
        try {
            int signedMonth = leapMonth ? -lunarMonth : lunarMonth;
            Lunar lunar = Lunar.fromYmd(lunarYear, signedMonth, lunarDay);
            Solar solar = lunar.getSolar();
            LocalDate result = LocalDate.of(solar.getYear(), solar.getMonth(), solar.getDay());
            LunarDate roundTrip = fromSolar(result);
            return roundTrip.year == lunarYear && roundTrip.month == lunarMonth && roundTrip.day == lunarDay
                    && roundTrip.leapMonth == leapMonth ? result : null;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }

    /**
     * 计算某农历年的有效日期。若该月没有三十日，则回退到二十九日；
     * 若指定闰月在该年不存在，则返回空，由调用者继续寻找下一个存在该闰月的年份。
     */
    public static LocalDate toSolarSafely(int lunarYear, int lunarMonth, int lunarDay, boolean leapMonth) {
        for (int day = Math.min(30, lunarDay); day >= 1; day--) {
            LocalDate result = toSolar(lunarYear, lunarMonth, day, leapMonth);
            if (result != null) return result;
        }
        return null;
    }

    /** 返回“农历七月十三”一类适合列表和小组件的短文字。 */
    public static String format(int month, int day, boolean leapMonth) {
        return "农历" + (leapMonth ? "闰" : "") + monthName(month) + dayName(day);
    }

    /** 返回正月、冬月、腊月等农历月份名称，供农历滚轮直接显示中文。 */
    public static String monthName(int month) { return MONTH_NAMES[Math.max(1, Math.min(12, month)) - 1]; }

    /** 返回指定农历年实际存在的闰月；零表示该年没有闰月。 */
    public static int leapMonthOfYear(int lunarYear) { return LunarYear.fromYear(lunarYear).getLeapMonth(); }

    /** 月历格内的短文字：初一显示月份，其他日期显示农历日号。 */
    public static String compact(LocalDate solarDate) {
        LunarDate lunar = fromSolar(solarDate);
        return lunar.day == 1 ? (lunar.leapMonth ? "闰" : "") + MONTH_NAMES[lunar.month - 1] : dayName(lunar.day);
    }

    /** 把一至三十转换为初一、十五、廿九、三十等中文日号。 */
    public static String dayName(int day) {
        int value = Math.max(1, Math.min(30, day));
        if (value == 10) return "初十";
        if (value == 20) return "二十";
        if (value == 30) return "三十";
        String prefix = value < 10 ? "初" : value < 20 ? "十" : "廿";
        return prefix + DAY_DIGITS[value % 10 - 1];
    }
}
