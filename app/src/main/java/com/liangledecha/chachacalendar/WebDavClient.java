package com.liangledecha.chachacalendar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 只在用户导出时建目录并上传，导入只下载；不调度、不自动重试或同步。 */
final class WebDavClient {
    private static final int MAX_BYTES = 16 * 1024 * 1024 + 44;
    // 沿用网络库默认的 NO_COOKIES；回归测试锁定此策略，改变前须恢复后缀资源。
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    private WebDavClient() { }

    private static OkHttpClient transport(HttpUrl url) throws IOException {
        if (!url.isHttps()) return HTTP;
        final java.security.KeyStore certificates;
        try { certificates = java.security.KeyStore.getInstance("AndroidCAStore"); }
        catch (java.security.KeyStoreException unavailable) { return HTTP; }
        try {
            certificates.load(null);
            for (java.util.Enumeration<String> aliases = certificates.aliases(); aliases.hasMoreElements();) {
                if (aliases.nextElement().startsWith("user:")) return withTrustedCertificates(certificates);
            }
            return HTTP;
        } catch (java.security.GeneralSecurityException | IOException error) {
            throw new IOException("WebDAV 无法读取设备的可信证书", error);
        }
    }

    static OkHttpClient withTrustedCertificates(java.security.KeyStore certificates) throws java.security.GeneralSecurityException {
        javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        factory.init(certificates);
        javax.net.ssl.X509TrustManager manager = (javax.net.ssl.X509TrustManager) factory.getTrustManagers()[0];
        javax.net.ssl.SSLContext tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(null, new javax.net.ssl.TrustManager[]{manager}, null);
        return HTTP.newBuilder().sslSocketFactory(tls.getSocketFactory(), manager).hostnameVerifier((host, session) -> {
            if (!HTTP.hostnameVerifier().verify(host, session)) return false;
            try { ((java.security.cert.X509Certificate) session.getPeerCertificates()[0]).checkValidity(); return true; }
            catch (java.security.cert.CertificateException | javax.net.ssl.SSLPeerUnverifiedException error) { return false; }
        }).build();
    }

    static HttpUrl rootUrl(String address) throws IOException {
        final HttpUrl root;
        try { root = HttpUrl.get(address == null ? "" : address.trim()); }
        catch (IllegalArgumentException error) { throw new IOException("请输入有效的 http:// 或 https:// WebDAV 地址（可带端口和文件夹路径）"); }
        if (root.query() != null || root.fragment() != null
                || !root.username().isEmpty() || !root.password().isEmpty())
            throw new IOException("请只填写 WebDAV 地址和文件夹路径，不要在地址中填写查询参数或账号密码");
        return root.encodedPath().endsWith("/") ? root : root.newBuilder().addPathSegment("").build();
    }

    static void put(String address, String username, String password, byte[] bytes) throws IOException {
        put(address, username, password, bytes, transport(rootUrl(address)));
    }

    static void put(String address, String username, String password, byte[] bytes, OkHttpClient client) throws IOException {
        HttpUrl root = rootUrl(address);
        if (bytes.length > MAX_BYTES) throw new IOException("备份文件超过 16 MB");
        try (Response response = client.newCall(request(root.resolve("ChaChaCalendar/"), username, password,
                "MKCOL", RequestBody.create(new byte[0], null))).execute()) {
            // 已存在的目录通常返回 405；这不代表上传成功，后续仍必须检查 PUT 的结果。
            if (response.code() != 201 && response.code() != 405) throw failure(response, "创建备份文件夹");
        }
        try (Response response = client.newCall(request(root.resolve("ChaChaCalendar/" + BackupCodec.FILE_NAME),
                username, password, "PUT", RequestBody.create(bytes, MediaType.get("application/octet-stream")))).execute()) {
            check(response, "上传备份");
        }
    }

    static byte[] get(String address, String username, String password) throws IOException {
        return get(address, username, password, transport(rootUrl(address)));
    }

    static byte[] get(String address, String username, String password, OkHttpClient client) throws IOException {
        HttpUrl root = rootUrl(address);
        // 旧版可能把同一二进制备份直接放在根目录；只在 404 时尝试旧文件，不改写或删除它。
        String[] paths = {"ChaChaCalendar/" + BackupCodec.FILE_NAME, BackupCodec.FILE_NAME, "ChaChaCalendar.chacha"};
        for (String path : paths) {
            try (Response response = client.newCall(request(root.resolve(path), username, password, "GET", null)).execute()) {
                if (response.code() == 404) continue;
                check(response, "下载备份");
                ResponseBody body = response.body();
                if (body == null) throw new IOException("WebDAV 返回空备份");
                if (body.contentLength() > MAX_BYTES) throw new IOException("远程备份超过 16 MB");
                try (InputStream in = body.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int total = 0, read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("远程备份超过 16 MB");
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            }
        }
        throw new IOException("WebDAV 未找到备份文件，请先导出备份");
    }

    private static Request request(HttpUrl url, String username, String password, String method, RequestBody body) {
        Request.Builder request = new Request.Builder().url(url).method(method, body).header("Cache-Control", "no-cache");
        if (!username.isEmpty()) request.header("Authorization", Credentials.basic(username, password, StandardCharsets.UTF_8));
        return request.build();
    }

    private static void check(Response response, String action) throws IOException {
        if (!response.isSuccessful()) throw failure(response, action);
    }

    private static IOException failure(Response response, String action) {
        String detail = response.code() == 405 || response.code() == 501
                ? "，请检查 WebDAV 服务及反向代理是否允许 MKCOL/PUT/GET" : "";
        return new IOException("WebDAV " + action + "失败（HTTP " + response.code() + "）" + detail);
    }
}
