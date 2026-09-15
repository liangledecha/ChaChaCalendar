package com.liangledecha.chachacalendar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 从茶茶日历公开 GitHub 项目读取最新正式版本。
 *
 * <p>请求在后台线程执行，只保存公开版本号、发布页和安装包地址；应用中不包含 GitHub
 * 账号或令牌。网络失败时返回上次成功缓存，避免影响日历正常启动。</p>
 */
public final class GitHubReleaseChecker {
    /** GitHub 项目主页，供“关于”和更新失败回退时打开。 */
    public static final String PROJECT_URL = "https://github.com/liangledecha/ChaChaCalendar";
    /** GitHub 返回最新非草稿、非预发布版本的公开接口。 */
    private static final String LATEST_URL = "https://api.github.com/repos/liangledecha/ChaChaCalendar/releases/latest";
    /** 更新缓存与其他显示设置共用应用现有设置文件。 */
    private static final String SETTINGS = "settings";

    /** 一次检查得到的版本信息。 */
    public static final class Release {
        public final String tag, version, pageUrl, apkUrl;
        Release(String tag, String pageUrl, String apkUrl) {
            this.tag = tag;
            this.version = tag != null && (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
        }
    }

    /** 把后台检查结果交回主界面；错误为空表示检查成功或成功读取缓存。 */
    public interface Callback { void onResult(Release release, Exception error); }

    private GitHubReleaseChecker() { }

    /** 启动一次异步检查，所有回调都切回安卓主线程。 */
    public static void check(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Release release = null; Exception failure = null;
            try { release = request(app); }
            catch (Exception error) { failure = error; release = cached(app); }
            Release result = release; Exception finalFailure = failure;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result, finalFailure));
        }, "检查茶茶日历更新").start();
    }

    /** 发出条件请求；服务器内容未变化时直接复用本地缓存。 */
    private static Release request(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_URL).openConnection();
        connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "ChaChaCalendar-Android");
        String etag = prefs.getString("release_etag", "");
        if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            connection.disconnect();
            Release cached = cached(context);
            if (cached == null) throw new IllegalStateException("服务器未返回内容且本地没有版本缓存");
            return cached;
        }
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IllegalStateException("GitHub 返回状态码 " + status);
        }
        String body;
        try (java.io.InputStream stream = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        JSONObject json = new JSONObject(body);
        String tag = json.getString("tag_name");
        String page = json.optString("html_url", PROJECT_URL + "/releases/latest");
        String apk = ""; JSONArray assets = json.optJSONArray("assets");
        if (assets != null) for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if (asset.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                apk = asset.optString("browser_download_url", ""); break;
            }
        }
        String responseEtag = connection.getHeaderField("ETag"); connection.disconnect();
        prefs.edit().putString("release_tag", tag).putString("release_page", page)
                .putString("release_apk", apk).putString("release_etag", responseEtag == null ? "" : responseEtag).apply();
        return new Release(tag, page, apk);
    }

    /** 返回上次成功检查保存的公开版本信息。 */
    public static Release cached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        String tag = prefs.getString("release_tag", "");
        return tag.isEmpty() ? null : new Release(tag, prefs.getString("release_page", PROJECT_URL + "/releases/latest"),
                prefs.getString("release_apk", ""));
    }

    /** 按数字段比较版本，正确处理 1.10.10 大于 1.10.9 以及可选的 v 前缀。 */
    public static boolean isNewer(String candidate, String installed) {
        int[] left = numericParts(candidate), right = numericParts(installed);
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int a = index < left.length ? left[index] : 0, b = index < right.length ? right[index] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /** 提取版本字符串中的连续数字段，非数字后缀不会造成崩溃。 */
    private static int[] numericParts(String value) {
        if (value == null) return new int[0];
        String cleaned = value.replaceFirst("^[vV]", "");
        String[] pieces = cleaned.split("[^0-9]+"); java.util.ArrayList<Integer> numbers = new java.util.ArrayList<>();
        for (String piece : pieces) if (!piece.isEmpty()) try { numbers.add(Integer.parseInt(piece)); } catch (NumberFormatException ignored) { numbers.add(0); }
        int[] result = new int[numbers.size()]; for (int index = 0; index < result.length; index++) result[index] = numbers.get(index);
        return result;
    }
}
