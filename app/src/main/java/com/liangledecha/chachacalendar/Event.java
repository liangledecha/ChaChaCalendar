package com.liangledecha.chachacalendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 日程数据模型。
 *
 * <p>一个对象代表一条纪念日、生日、待办或倒数日。这个类不访问界面和数据库，
 * 只负责保存字段，并计算重复事项的下一次发生日期、倒计时和显示条件。</p>
 */
public final class Event {
    // 重复规则在数据库中保存的固定值。使用常量可以避免各处手写字符串造成拼写错误。
    public static final String NONE = "NONE", YEARLY = "YEARLY", MONTHLY = "MONTHLY", WEEKLY = "WEEKLY", DAILY = "DAILY";
    /** 数据库主键；零表示尚未写入数据库的新事项。 */
    public long id;
    /** 用户输入的事项名称。 */
    public String title;
    /** 首次发生日期，也是计算各种重复周期的起点。 */
    public LocalDate date;
    /** 事项类别：纪念日、生日、待办或倒数日。 */
    public String type;
    /** 提前显示天数；负数表示一直显示。 */
    public int visibilityDays;
    /** 重复规则，取值来自本类顶部定义的五个常量。 */
    public String repeatRule;
    /** 待办的具体提醒时间；其他事项类型固定为空，表示全天事项。 */
    public LocalTime time;
    /** 待办是否已经完成；只有待办类型会使用此字段。 */
    public boolean completed;
    /** 对应的系统日历事件编号；负数表示尚未同步或系统事件已被移除。 */
    public long systemEventId;
    /** 为真表示用户按农历创建事项；为假表示按公历创建。 */
    public boolean lunarBased;
    /** 农历事项保存的月份和日号；公历事项均保存为零。 */
    public int lunarMonth, lunarDay;
    /** 农历事项是否指定闰月。 */
    public boolean lunarLeapMonth;

    /**
     * 创建一条日程对象，并把外部传入的数据保存到对应字段。
     * 当重复规则为空时自动按“不重复”处理，避免后续日期计算出现空值错误。
     */
    public Event(long id, String title, LocalDate date, String type, int visibilityDays, String repeatRule,
                 LocalTime time, boolean completed, long systemEventId, boolean lunarBased,
                 int lunarMonth, int lunarDay, boolean lunarLeapMonth) {
        this.id = id; this.title = title; this.date = date; this.type = type; this.visibilityDays = visibilityDays;
        this.repeatRule = repeatRule == null ? NONE : repeatRule;
        // 时间只对待办有意义，防止其他类型因旧数据或调用错误而意外显示时间。
        this.time = "待办".equals(type) ? time : null;
        this.completed = "待办".equals(type) && completed;
        this.systemEventId = systemEventId;
        this.lunarBased = lunarBased;
        this.lunarMonth = lunarBased ? lunarMonth : 0;
        this.lunarDay = lunarBased ? lunarDay : 0;
        this.lunarLeapMonth = lunarBased && lunarLeapMonth;
        // 旧版可能把临界朔日保存为偏后一天；按农历字段寻找最近的正确民用公历落点自动修复。
        if (lunarBased) this.date = normalizeStoredLunarDate(date, lunarMonth, lunarDay, lunarLeapMonth);
    }

    /**
     * 计算从基准日期开始的下一次发生日期。
     *
     * <p>计算顺序依次处理不重复、每日、每周、每月和每年。每月及每年重复时，
     * 如果目标月份不存在原始日号（例如二月没有三十一日），会自动使用该月最后一天。</p>
     *
     * @param anchor 用户当前选中的基准日期
     * @return 不早于基准日期的下一次发生日期；不重复的历史事项仍返回原日期
     */
    public LocalDate nextDate(LocalDate anchor) {
        // 农历每年重复不能套用固定公历月日，必须为每个农历年重新换算。
        if (lunarBased && YEARLY.equals(repeatRule)) return nextLunarYearlyDate(anchor);
        if (lunarBased && MONTHLY.equals(repeatRule)) return nextLunarMonthlyDate(anchor);
        if (NONE.equals(repeatRule) || anchor.isBefore(date)) return date;
        if (DAILY.equals(repeatRule)) return anchor;
        if (WEEKLY.equals(repeatRule)) {
            long elapsed = ChronoUnit.DAYS.between(date, anchor), remainder = elapsed % 7;
            return remainder == 0 ? anchor : anchor.plusDays(7 - remainder);
        }
        if (MONTHLY.equals(repeatRule)) {
            YearMonth ym = YearMonth.from(anchor); LocalDate candidate = safe(ym, date.getDayOfMonth());
            return candidate.isBefore(anchor) ? safe(ym.plusMonths(1), date.getDayOfMonth()) : candidate;
        }
        LocalDate candidate = safe(YearMonth.of(anchor.getYear(), date.getMonthValue()), date.getDayOfMonth());
        return candidate.isBefore(anchor) ? safe(YearMonth.of(anchor.getYear() + 1, date.getMonthValue()), date.getDayOfMonth()) : candidate;
    }

    /**
     * 判断事项是否应当出现在指定日期的月历格中。
     * 该方法被月历绘制逻辑调用，用来决定是否绘制蓝点或文字标签。
     */
    public boolean occursOn(LocalDate target) {
        if (target.isBefore(date)) return false;
        if (NONE.equals(repeatRule)) return target.equals(date);
        if (lunarBased && YEARLY.equals(repeatRule)) return target.equals(nextLunarYearlyDate(target));
        if (lunarBased && MONTHLY.equals(repeatRule)) {
            LunarDateUtils.LunarDate lunar = LunarDateUtils.fromSolar(target);
            if (lunar.day == lunarDay) return true;
            return lunarDay == 30 && lunar.day == 29 && LunarDateUtils.fromSolar(target.plusDays(1)).day == 1;
        }
        if (DAILY.equals(repeatRule)) return true;
        if (WEEKLY.equals(repeatRule)) return ChronoUnit.DAYS.between(date, target) % 7 == 0;
        if (MONTHLY.equals(repeatRule)) return target.getDayOfMonth() == Math.min(date.getDayOfMonth(), YearMonth.from(target).lengthOfMonth());
        return target.getMonthValue() == date.getMonthValue() && target.getDayOfMonth() == Math.min(date.getDayOfMonth(), YearMonth.from(target).lengthOfMonth());
    }

    /** 计算从基准日期到下一次发生日期相差的自然日数量。 */
    public long daysUntil(LocalDate anchor) { return ChronoUnit.DAYS.between(anchor, nextDate(anchor)); }

    /**
     * 生成用于排序和系统日历同步的下一次发生时刻。
     * 待办使用用户选择的分钟精度时间，全天事项统一使用当天零点。
     */
    public LocalDateTime nextDateTime(LocalDate anchor) {
        return LocalDateTime.of(nextDate(anchor), time == null ? LocalTime.MIDNIGHT : time);
    }

    /**
     * 根据“提前几天显示”设置判断事项能否出现在下半区。
     * 一直显示的事项直接返回真；限时事项必须位于零到设定天数的闭区间内。
     */
    public boolean shouldShow(LocalDate anchor) { long days = daysUntil(anchor); return visibilityDays < 0 || (days >= 0 && days <= visibilityDays); }

    /**
     * 按真实当前日期和分钟判断待办是否已经超过截止时刻。
     * 浏览月历中的其他日期不会改变这个结果；非待办始终不属于“过期待办”。
     */
    public boolean isOverdue() {
        if (!isTodo()) return false;
        LocalDate today = LocalDate.now(); LocalDate occurrence = nextDate(today);
        if (occurrence.isBefore(today)) return true;
        return occurrence.equals(today) && time != null && time.isBefore(LocalTime.now());
    }

    /** 把数据库内部的重复规则转换为用户能看懂的中文。 */
    public String repeatLabel() {
        if (YEARLY.equals(repeatRule)) return "每年"; if (MONTHLY.equals(repeatRule)) return "每月";
        if (WEEKLY.equals(repeatRule)) return "每周"; if (DAILY.equals(repeatRule)) return "每日"; return "不重复";
    }
    /** 把提前显示天数转换为事项卡片中的中文说明。 */
    public String visibilityLabel() { return visibilityDays < 0 ? "一直显示" : visibilityDays + "天前显示"; }

    /** 判断当前事项是不是待办，集中维护类型字符串的比较逻辑。 */
    public boolean isTodo() { return "待办".equals(type); }

    /** 返回列表和小组件使用的时间文字；非待办不显示时间。 */
    public String timeLabel() { return isTodo() && time != null ? String.format(Locale.CHINA, "%02d:%02d", time.getHour(), time.getMinute()) : ""; }

    /** 返回列表与小组件使用的日期文字，农历事项始终展示其农历设定。 */
    public String displayDate(LocalDate occurrence) {
        if (!lunarBased) return String.format(Locale.CHINA, "%d年%d月%d日", occurrence.getYear(), occurrence.getMonthValue(), occurrence.getDayOfMonth());
        LunarDateUtils.LunarDate lunar = LunarDateUtils.fromSolar(occurrence);
        return LunarDateUtils.format(lunar.month, lunar.day, lunar.leapMonth);
    }

    /** 寻找不早于基准日的下一次农历周年；闰月事项会跳到下一个实际存在该闰月的年份。 */
    private LocalDate nextLunarYearlyDate(LocalDate anchor) {
        int firstYear = LunarDateUtils.fromSolar(anchor).year;
        for (int year = firstYear; year <= firstYear + 20; year++) {
            LocalDate candidate = LunarDateUtils.toSolarSafely(year, lunarMonth, lunarDay, lunarLeapMonth);
            if (candidate != null && !candidate.isBefore(date) && !candidate.isBefore(anchor)) return candidate;
        }
        return date;
    }

    /** 在原公历年份附近寻找与农历字段吻合且距离原记录最近的日期，用于兼容旧数据。 */
    private static LocalDate normalizeStoredLunarDate(LocalDate stored, int month, int day, boolean leapMonth) {
        LocalDate best = stored; long bestDistance = Long.MAX_VALUE;
        for (int lunarYear = stored.getYear() - 1; lunarYear <= stored.getYear() + 1; lunarYear++) {
            LocalDate candidate = LunarDateUtils.toSolar(lunarYear, month, day, leapMonth);
            if (candidate == null) continue;
            long distance = Math.abs(ChronoUnit.DAYS.between(stored, candidate));
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return bestDistance <= 40 ? best : stored;
    }

    /** 寻找下一次农历每月重复日期；农历大小月变化时三十日自动落到当月最后一天。 */
    private LocalDate nextLunarMonthlyDate(LocalDate anchor) {
        if (anchor.isBefore(date)) return date;
        LocalDate cursor = anchor;
        for (int offset = 0; offset <= 62; offset++, cursor = cursor.plusDays(1)) {
            LunarDateUtils.LunarDate lunar = LunarDateUtils.fromSolar(cursor);
            if (lunar.day == lunarDay) return cursor;
            // 三十日事项在只有二十九日的小月按月末处理。
            LunarDateUtils.LunarDate tomorrow = LunarDateUtils.fromSolar(cursor.plusDays(1));
            if (lunarDay == 30 && lunar.day == 29 && tomorrow.day == 1) return cursor;
        }
        return date;
    }

    /** 在指定年月中安全创建日期，自动处理二月和小月没有二十九至三十一日的情况。 */
    private static LocalDate safe(YearMonth ym, int day) { return ym.atDay(Math.min(day, ym.lengthOfMonth())); }
}
