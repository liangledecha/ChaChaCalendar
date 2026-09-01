package com.liangledecha.chachacalendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.time.LocalTime;
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
    /** 创建数据库帮助对象；第五版加入重复待办的本次完成进度。 */
    public EventStore(Context context) { super(context, DB, null, 5); }

    /** 首次安装时建立日程表和全部字段。 */
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,event_date TEXT NOT NULL,type TEXT NOT NULL,visibility_days INTEGER NOT NULL DEFAULT -1,yearly INTEGER NOT NULL DEFAULT 1,repeat_rule TEXT NOT NULL DEFAULT 'YEARLY',event_time TEXT,completed INTEGER NOT NULL DEFAULT 0,completed_through TEXT,system_event_id INTEGER NOT NULL DEFAULT -1,date_system TEXT NOT NULL DEFAULT 'SOLAR',lunar_month INTEGER NOT NULL DEFAULT 0,lunar_day INTEGER NOT NULL DEFAULT 0,lunar_leap INTEGER NOT NULL DEFAULT 0)");
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
        if (oldVersion < 3) {
            // 旧事项没有时间和完成状态；待办时间留空，用户编辑时可补充。
            db.execSQL("ALTER TABLE events ADD COLUMN event_time TEXT");
            db.execSQL("ALTER TABLE events ADD COLUMN completed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE events ADD COLUMN system_event_id INTEGER NOT NULL DEFAULT -1");
            db.execSQL("UPDATE events SET event_time='09:00' WHERE type='待办'");
        }
        if (oldVersion < 4) {
            // 旧数据全部按原公历含义保留，只有用户新建或编辑为农历后才写入农历字段。
            db.execSQL("ALTER TABLE events ADD COLUMN date_system TEXT NOT NULL DEFAULT 'SOLAR'");
            db.execSQL("ALTER TABLE events ADD COLUMN lunar_month INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE events ADD COLUMN lunar_day INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE events ADD COLUMN lunar_leap INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 5) {
            // 重复待办不再使用永久完成状态；升级后重新激活，并从下一次勾选开始记录周期进度。
            db.execSQL("ALTER TABLE events ADD COLUMN completed_through TEXT");
            db.execSQL("UPDATE events SET completed=0 WHERE type='待办' AND repeat_rule<>'NONE'");
        }
    }
    /**
     * 新增或更新一条事项。
     * 标识为零时执行新增，否则按主键更新原记录，并返回最终主键。
     */
    public long save(Event e) {
        ContentValues v = new ContentValues(); v.put("title", e.title); v.put("event_date", e.date.toString()); v.put("type", e.type);
        v.put("visibility_days", e.visibilityDays); v.put("yearly", Event.YEARLY.equals(e.repeatRule) ? 1 : 0); v.put("repeat_rule", e.repeatRule);
        if (e.time == null) v.putNull("event_time"); else v.put("event_time", e.time.toString());
        v.put("completed", e.completed ? 1 : 0);
        if (e.completedThrough == null) v.putNull("completed_through"); else v.put("completed_through", e.completedThrough.toString());
        v.put("system_event_id", e.systemEventId);
        v.put("date_system", e.lunarBased ? "LUNAR" : "SOLAR");
        v.put("lunar_month", e.lunarMonth); v.put("lunar_day", e.lunarDay); v.put("lunar_leap", e.lunarLeapMonth ? 1 : 0);
        if (e.id == 0) { e.id = getWritableDatabase().insertOrThrow("events", null, v); return e.id; }
        getWritableDatabase().update("events", v, "id=?", new String[]{Long.toString(e.id)}); return e.id;
    }
    /** 只更新待办的完成状态，避免勾选时覆盖其他字段。 */
    public void setCompleted(long id, boolean completed) {
        ContentValues values = new ContentValues(); values.put("completed", completed ? 1 : 0);
        getWritableDatabase().update("events", values, "id=?", new String[]{Long.toString(id)});
    }
    /** 记录重复待办刚完成的周期；空值表示尚未完成过任何一次。 */
    public void setCompletedThrough(long id, LocalDate completedThrough) {
        ContentValues values = new ContentValues();
        if (completedThrough == null) values.putNull("completed_through"); else values.put("completed_through", completedThrough.toString());
        getWritableDatabase().update("events", values, "id=?", new String[]{Long.toString(id)});
    }
    /** 保存系统日历返回的事件编号，供后续编辑和删除定位同一条系统事项。 */
    public void setSystemEventId(long id, long systemEventId) {
        ContentValues values = new ContentValues(); values.put("system_event_id", systemEventId);
        getWritableDatabase().update("events", values, "id=?", new String[]{Long.toString(id)});
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
            while (c.moveToNext()) {
                String storedTime = c.getString(c.getColumnIndexOrThrow("event_time"));
                Event event = new Event(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        LocalDate.parse(c.getString(c.getColumnIndexOrThrow("event_date"))),
                        c.getString(c.getColumnIndexOrThrow("type")),
                        c.getInt(c.getColumnIndexOrThrow("visibility_days")),
                        c.getString(c.getColumnIndexOrThrow("repeat_rule")),
                        storedTime == null || storedTime.isEmpty() ? null : LocalTime.parse(storedTime),
                        c.getInt(c.getColumnIndexOrThrow("completed")) == 1,
                        c.getLong(c.getColumnIndexOrThrow("system_event_id")),
                        "LUNAR".equals(c.getString(c.getColumnIndexOrThrow("date_system"))),
                        c.getInt(c.getColumnIndexOrThrow("lunar_month")),
                        c.getInt(c.getColumnIndexOrThrow("lunar_day")),
                        c.getInt(c.getColumnIndexOrThrow("lunar_leap")) == 1);
                String completedThrough = c.getString(c.getColumnIndexOrThrow("completed_through"));
                event.completedThrough = completedThrough == null || completedThrough.isEmpty() ? null : LocalDate.parse(completedThrough);
                out.add(event);
            }
        }
        out.sort(Comparator.comparing(e -> e.nextDateTime(anchor))); return out;
    }

    /**
     * 返回可以绘制到日历和日程页中的事项。
     * 已完成待办仍保存在数据库和待办页，但不会再污染月历、日程页或桌面组件。
     */
    public List<Event> calendarItems(LocalDate anchor) {
        ArrayList<Event> result = new ArrayList<>();
        for (Event event : all(anchor)) if (!event.isTodo() || !event.completed) result.add(event);
        return result;
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
        for (Event e : all(anchor)) {
            if (e.isTodo() && e.completed) continue;
            // 今天的主列表和小组件始终保留未办结过期待办；浏览其他日期时仍严格服从该日期的显示窗口。
            boolean overdueToday = anchor.equals(LocalDate.now()) && e.isTodo() && e.isOverdue();
            if (overdueToday || (browsing ? e.daysUntil(anchor) >= 0 : e.shouldShow(anchor))) result.add(e);
        }
        return result;
    }
}
