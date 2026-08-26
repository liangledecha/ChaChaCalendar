package com.liangledecha.chachacalendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

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

    /**
     * 创建一条日程对象，并把外部传入的数据保存到对应字段。
     * 当重复规则为空时自动按“不重复”处理，避免后续日期计算出现空值错误。
     */
    public Event(long id, String title, LocalDate date, String type, int visibilityDays, String repeatRule) {
        this.id = id; this.title = title; this.date = date; this.type = type; this.visibilityDays = visibilityDays;
        this.repeatRule = repeatRule == null ? NONE : repeatRule;
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
        if (DAILY.equals(repeatRule)) return true;
        if (WEEKLY.equals(repeatRule)) return ChronoUnit.DAYS.between(date, target) % 7 == 0;
        if (MONTHLY.equals(repeatRule)) return target.getDayOfMonth() == Math.min(date.getDayOfMonth(), YearMonth.from(target).lengthOfMonth());
        return target.getMonthValue() == date.getMonthValue() && target.getDayOfMonth() == Math.min(date.getDayOfMonth(), YearMonth.from(target).lengthOfMonth());
    }

    /** 计算从基准日期到下一次发生日期相差的自然日数量。 */
    public long daysUntil(LocalDate anchor) { return ChronoUnit.DAYS.between(anchor, nextDate(anchor)); }

    /**
     * 根据“提前几天显示”设置判断事项能否出现在下半区。
     * 一直显示的事项直接返回真；限时事项必须位于零到设定天数的闭区间内。
     */
    public boolean shouldShow(LocalDate anchor) { long days = daysUntil(anchor); return visibilityDays < 0 || (days >= 0 && days <= visibilityDays); }

    /** 把数据库内部的重复规则转换为用户能看懂的中文。 */
    public String repeatLabel() {
        if (YEARLY.equals(repeatRule)) return "每年"; if (MONTHLY.equals(repeatRule)) return "每月";
        if (WEEKLY.equals(repeatRule)) return "每周"; if (DAILY.equals(repeatRule)) return "每日"; return "不重复";
    }
    /** 把提前显示天数转换为事项卡片中的中文说明。 */
    public String visibilityLabel() { return visibilityDays < 0 ? "一直显示" : visibilityDays + "天前显示"; }

    /** 在指定年月中安全创建日期，自动处理二月和小月没有二十九至三十一日的情况。 */
    private static LocalDate safe(YearMonth ym, int day) { return ym.atDay(Math.min(day, ym.lengthOfMonth())); }
}
