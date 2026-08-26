package com.liangledecha.chachacalendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 日程数据库访问层。
 *
 * <p>界面只通过这个类保存、删除和读取事项，因此数据库细节不会散落到活动或小组件中。
 * 数据完全保存在手机本地，不需要网络连接。</p>
 */
public final class EventStore extends SQLiteOpenHelper {
    /** 数据库文件名；数据库保存在应用自己的私有目录中。 */
    private static final String DB = "chacha_calendar.db";
    /** 创建数据库帮助对象；数字二是当前数据库结构版本。 */
    public EventStore(Context context) { super(context, DB, null, 2); }

    /** 首次安装时建立日程表和全部字段。 */
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,event_date TEXT NOT NULL,type TEXT NOT NULL,visibility_days INTEGER NOT NULL DEFAULT -1,yearly INTEGER NOT NULL DEFAULT 1,repeat_rule TEXT NOT NULL DEFAULT 'YEARLY')");
    }
    /**
     * 升级旧数据库。
     * 第一版只有“是否每年重复”，第二版把它迁移为五种重复规则，已有数据不会丢失。
     */
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN repeat_rule TEXT NOT NULL DEFAULT 'YEARLY'");
            db.execSQL("UPDATE events SET repeat_rule=CASE WHEN yearly=1 THEN 'YEARLY' ELSE 'NONE' END");
        }
    }
    /**
     * 新增或更新一条事项。
     * 标识为零时执行新增，否则按主键更新原记录，并返回最终主键。
     */
    public long save(Event e) {
        ContentValues v = new ContentValues(); v.put("title", e.title); v.put("event_date", e.date.toString()); v.put("type", e.type);
        v.put("visibility_days", e.visibilityDays); v.put("yearly", Event.YEARLY.equals(e.repeatRule) ? 1 : 0); v.put("repeat_rule", e.repeatRule);
        if (e.id == 0) return getWritableDatabase().insertOrThrow("events", null, v);
        getWritableDatabase().update("events", v, "id=?", new String[]{Long.toString(e.id)}); return e.id;
    }
    /** 按数据库主键永久删除一条事项。 */
    public void delete(long id) { getWritableDatabase().delete("events", "id=?", new String[]{Long.toString(id)}); }

    /** 以今天为排序基准读取全部事项。 */
    public List<Event> all() { return all(LocalDate.now()); }

    /**
     * 读取全部事项，并按相对于基准日期的下一次发生时间排序。
     * 游标放在自动关闭结构中，读取完成后会及时释放数据库资源。
     */
    public List<Event> all(LocalDate anchor) {
        ArrayList<Event> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("events", null, null, null, null, null, null)) {
            while (c.moveToNext()) out.add(new Event(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("title")), LocalDate.parse(c.getString(c.getColumnIndexOrThrow("event_date"))), c.getString(c.getColumnIndexOrThrow("type")), c.getInt(c.getColumnIndexOrThrow("visibility_days")), c.getString(c.getColumnIndexOrThrow("repeat_rule"))));
        }
        out.sort(Comparator.comparing(e -> e.nextDate(anchor))); return out;
    }
    /** 以今天为基准读取符合提前显示规则的事项，供桌面小组件使用。 */
    public List<Event> visible() { return visible(LocalDate.now(), false); }

    /**
     * 读取下半区应显示的事项。
     *
     * @param anchor 计算倒计时和显示窗口的基准日期
     * @param browsing 为真时列出基准日期之后的全部事项；为假时严格执行提前显示天数
     */
    public List<Event> visible(LocalDate anchor, boolean browsing) {
        ArrayList<Event> result = new ArrayList<>();
        for (Event e : all(anchor)) if (browsing ? e.daysUntil(anchor) >= 0 : e.shouldShow(anchor)) result.add(e);
        return result;
    }
}
