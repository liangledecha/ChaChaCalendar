package com.liangledecha.chachacalendar;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** WebDAV 的单次手动上传和下载；刻意不包含调度、重试队列或自动同步。 */
final class WebDavClient {
    private WebDavClient() { }

    static void put(String address, String username, String password, byte[] bytes) throws IOException {
        HttpURLConnection connection = open(address, username, password, "PUT");
        connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            check(connection);
        } finally { connection.disconnect(); }
    }

    static byte[] get(String address, String username, String password) throws IOException {
        HttpURLConnection connection = open(address, username, password, "GET"); check(connection);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, read;
            while ((read = in.read(buffer)) != -1) { total += read; if (total > 16 * 1024 * 1024 + 44) throw new IOException("远程备份超过 16 MB"); out.write(buffer, 0, read); }
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(String address, String username, String password, String method) throws IOException {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("WebDAV 地址必须使用 HTTPS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000); connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
        if (!username.isEmpty()) connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        return connection;
    }

    private static void check(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) { connection.disconnect(); throw new IOException("WebDAV 返回 HTTP " + code); }
    }
}
