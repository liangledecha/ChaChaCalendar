package com.liangledecha.chachacalendar;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 中国大陆法定放假与补班数据仓库。
 *
 * <p>联网成功后按年份缓存结构化结果；没有网络、接口不可用或数据尚未公布时，
 * 继续使用最近缓存。2026 年另有依据国务院通知整理的内置兜底数据。</p>
 */
public final class HolidayRepository {
    /** 免费整年节假日接口；年份参数在请求时追加。 */
    private static final String API = "https://timor.tech/api/holiday/year/%d/";
    /** 同一年最多每七天自动检查一次，避免每次打开日历都联网。 */
    private static final long REFRESH_INTERVAL = 7L * 24 * 60 * 60 * 1000;

    /** 一天的政策状态，放假为真，补班为假。 */
    public static final class HolidayInfo {
        public final String name;
        public final boolean dayOff;
        HolidayInfo(String name, boolean dayOff) { this.name = name; this.dayOff = dayOff; }
        /** 返回适合月历格显示的短标签。 */
        public String label() { return (dayOff ? "休·" : "班·") + name.replace("节", ""); }
    }

    /** 工具仓库不需要创建对象。 */
    private HolidayRepository() { }

    /** 判断指定年份是否需要联网检查新数据。 */
    public static boolean needsRefresh(Context context, int year) {
        long last = preferences(context).getLong("holiday_updated_" + year, 0);
        return System.currentTimeMillis() - last >= REFRESH_INTERVAL;
    }

    /**
     * 在调用线程中下载整年节假日并写入缓存，调用方必须放在后台线程。
     *
     * @return 下载和解析均成功时返回真
     */
    public static boolean updateYear(Context context, int year) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(String.format(java.util.Locale.ROOT, API, year)).openConnection();
            connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ChaChaCalendar/1.8");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("code", -1) != 0 || root.optJSONObject("holiday") == null) return false;
            preferences(context).edit().putString("holiday_json_" + year, root.getJSONObject("holiday").toString())
                    .putLong("holiday_updated_" + year, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception ignored) {
            // 联网失败不是日历核心错误，保留旧缓存并由界面给出简短提示。
            return false;
        } finally { if (connection != null) connection.disconnect(); }
    }

    /** 读取一个年份的缓存；2026 年没有缓存时返回国务院通知的内置兜底。 */
    public static Map<LocalDate, HolidayInfo> year(Context context, int year) {
        String cached = preferences(context).getString("holiday_json_" + year, null);
        HashMap<LocalDate, HolidayInfo> result = new HashMap<>();
        if (cached != null) {
            try {
                JSONObject values = new JSONObject(cached);
                Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); JSONObject item = values.optJSONObject(key); if (item == null) continue;
                    String[] parts = key.split("-");
                    if (parts.length != 2) continue;
                    LocalDate date = LocalDate.of(year, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    result.put(date, new HolidayInfo(item.optString("name", "节假日"), item.optBoolean("holiday", true)));
                }
            } catch (Exception ignored) { result.clear(); }
        }
        if (result.isEmpty() && year == 2026) addOfficial2026Fallback(result);
        return result;
    }

    /** 根据国务院办公厅 2026 年通知写入放假区间和补班日期。 */
    private static void addOfficial2026Fallback(Map<LocalDate, HolidayInfo> out) {
        addRange(out, "2026-01-01", "2026-01-03", "元旦"); addWork(out, "2026-01-04", "元旦补班");
        addRange(out, "2026-02-15", "2026-02-23", "春节"); addWork(out, "2026-02-14", "春节补班"); addWork(out, "2026-02-28", "春节补班");
        addRange(out, "2026-04-04", "2026-04-06", "清明节");
        addRange(out, "2026-05-01", "2026-05-05", "劳动节"); addWork(out, "2026-05-09", "劳动节补班");
        addRange(out, "2026-06-19", "2026-06-21", "端午节");
        addRange(out, "2026-09-25", "2026-09-27", "中秋节");
        addRange(out, "2026-10-01", "2026-10-07", "国庆节"); addWork(out, "2026-09-20", "国庆节补班"); addWork(out, "2026-10-10", "国庆节补班");
    }

    /** 向兜底表中加入首尾均包含的连续放假日期。 */
    private static void addRange(Map<LocalDate, HolidayInfo> out, String start, String end, String name) {
        LocalDate cursor = LocalDate.parse(start), last = LocalDate.parse(end);
        while (!cursor.isAfter(last)) { out.put(cursor, new HolidayInfo(name, true)); cursor = cursor.plusDays(1); }
    }
    /** 向兜底表中加入一条周末补班日期。 */
    private static void addWork(Map<LocalDate, HolidayInfo> out, String date, String name) { out.put(LocalDate.parse(date), new HolidayInfo(name, false)); }
    /** 返回本仓库专用的设置存储。 */
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences("holiday_cache", Context.MODE_PRIVATE); }
}
