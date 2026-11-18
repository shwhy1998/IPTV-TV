package com.iptv.tv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

final class AppUpdates {
    private static final String REPOSITORY = "shwhy1998/IPTV-TV";
    private static final String CDN = "https://data.jsdelivr.com/v1/package/gh/" + REPOSITORY;
    private static final String RELEASES = "https://api.github.com/repos/" + REPOSITORY + "/releases/";
    private static final String DOWNLOADS = "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final int MAX_APK_BYTES = 32 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 256 * 1024;

    private final Context context;
    private final PackageManager packages;
    private final File directory;

    AppUpdates(Context context) {
        this.context = context.getApplicationContext();
        packages = context.getPackageManager();
        directory = new File(context.getCacheDir(), "updates");
    }

    String installedVersion() throws PackageManager.NameNotFoundException {
        return packages.getPackageInfo(context.getPackageName(), 0).versionName;
    }

    String findNewerVersion() throws Exception {
        String installed = installedVersion();
        String candidate = installed;
        try {
            JSONArray versions = new JSONObject(read(CDN, MAX_METADATA_BYTES)).getJSONArray("versions");
            for (int i = 0; i < versions.length(); i++) {
                String version = versions.getString(i);
                if (newer(version, candidate)) candidate = version;
            }
            if (newer(candidate, installed)) {
                String release = releaseVersion("tags/v" + candidate);
                if (candidate.equals(release)) return release;
            }
        } catch (Exception failure) {
            android.util.Log.w("IptvUpdates", "CDN lookup failed; checking GitHub Releases", failure);
        }
        String release = releaseVersion("latest");
        return newer(release, installed) ? release : null;
    }

    private String releaseVersion(String path) throws Exception {
        JSONObject release = new JSONObject(read(RELEASES + path, MAX_METADATA_BYTES));
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return "";
        String tag = release.getString("tag_name");
        return tag.startsWith("v") ? tag.substring(1) : "";
    }

    File download(String version) throws Exception {
        PackageInfo installed = packages.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        if (!newer(version, installed.versionName)) throw new IOException("版本不是更新版");
        String base = DOWNLOADS + "v" + version + "/IPTV-TV.apk";
        String expected = read(base + ".sha256", 1024).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[a-f0-9]{64}")) throw new IOException("更新校验文件无效");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新缓存");
        File apk = new File(directory, "IPTV-TV.apk");
        if (apk.isFile() && matchesHash(apk, expected)) {
            verifyPackage(apk, version, installed);
            return apk;
        }
        File partial = new File(directory, "download.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                transfer(base, output, MAX_APK_BYTES);
            }
            if (!matchesHash(partial, expected)) throw new IOException("更新包的 SHA-256 校验失败");
            verifyPackage(partial, version, installed);
            if (!partial.renameTo(apk)) throw new IOException("无法保存更新包");
            return apk;
        } finally {
            partial.delete();
        }
    }

    private void verifyPackage(File file, String version, PackageInfo installed) throws IOException {
        PackageInfo update = packages.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (update == null || !installed.packageName.equals(update.packageName)
            || !version.equals(update.versionName) || update.versionCode <= installed.versionCode) {
            throw new IOException("更新包名称或版本不匹配");
        }
        Signature[] oldSigners = installed.signatures;
        Signature[] newSigners = update.signatures;
        if (oldSigners == null || newSigners == null || oldSigners.length != 1
            || newSigners.length != 1 || !oldSigners[0].equals(newSigners[0])) {
            throw new IOException("更新包签名与当前应用不一致");
        }
    }

    private static boolean newer(String candidate, String installed) {
        String versionPattern = "[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}";
        if (!candidate.matches(versionPattern) || !installed.matches(versionPattern)) return false;
        String[] a = candidate.split("\\.");
        String[] b = installed.split("\\.");
        for (int i = 0; i < a.length; i++) {
            int order = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (order != 0) return order > 0;
        }
        return false;
    }

    private static boolean matchesHash(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return expected.contentEquals(actual);
    }

    private static String read(String url, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transfer(url, bytes, limit);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void transfer(String url, OutputStream output, int limit) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "IPTV-TV");
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > limit) throw new IOException("更新响应超过大小限制");
                output.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
    }
}
