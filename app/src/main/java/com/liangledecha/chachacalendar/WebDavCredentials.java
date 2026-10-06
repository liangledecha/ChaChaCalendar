package com.liangledecha.chachacalendar;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** WebDAV 配置；密码使用 Android Keystore 的 AES-GCM 密钥加密后再落盘。 */
final class WebDavCredentials {
    private static final String ALIAS = "chacha_webdav_password", PREFS = "webdav";
    final String address, username, password;

    WebDavCredentials(String address, String username, String password) { this.address = address; this.username = username; this.password = password; }

    static WebDavCredentials load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = prefs.getString("password", ""), password = "";
        try { if (!encrypted.isEmpty()) password = decrypt(encrypted); } catch (Exception ignored) { }
        return new WebDavCredentials(prefs.getString("address", ""), prefs.getString("username", ""), password);
    }

    static void save(Context context, String address, String username, String password) throws Exception {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("address", address.trim())
                .putString("username", username).putString("password", encrypt(password)).apply();
    }

    static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), iv = cipher.getIV(), combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length); System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    static String decrypt(String value) throws Exception {
        byte[] combined = Base64.decode(value, Base64.NO_WRAP), iv = new byte[12], encrypted = new byte[combined.length - 12];
        System.arraycopy(combined, 0, iv, 0, 12); System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
