package com.iptv.tv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

final class AppUpdates {
    private static final String REPOSITORY = "shwhy1998/IPTV-TV";
    private static final String CDN = "https://data.jsdelivr.com/v1/package/gh/" + REPOSITORY;
    private static final String RELEASES = "https://api.github.com/repos/" + REPOSITORY + "/releases/";
    private static final String DOWNLOADS = "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final String MIRROR = "https://cdn.jsdelivr.net/gh/" + REPOSITORY + "@apk-v";
    private static final int MAX_APK_BYTES = 32 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 256 * 1024;

    private final Context context;
    private final PackageManager packages;
    private final File directory;
    private SSLSocketFactory legacyCdnTls;

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
            try {
                savePackage(MIRROR + version + "/IPTV-TV.apk", partial, expected);
            } catch (IOException mirrorFailure) {
                android.util.Log.w("IptvUpdates", "APK mirror failed; downloading from GitHub", mirrorFailure);
                savePackage(base, partial, expected);
            }
            verifyPackage(partial, version, installed);
            if (!partial.renameTo(apk)) throw new IOException("无法保存更新包");
            return apk;
        } finally {
            partial.delete();
        }
    }

    private void savePackage(String url, File file, String expected) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            transfer(url, output, MAX_APK_BYTES);
        }
        if (!matchesHash(file, expected)) throw new IOException("更新包的 SHA-256 校验失败");
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

    private String read(String url, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transfer(url, bytes, limit);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private void transfer(String url, OutputStream output, int limit) throws IOException {
        URL target = new URL(url);
        for (int redirects = 0; redirects <= 4; redirects++) {
            String host = target.getHost();
            boolean cdnHost = "data.jsdelivr.com".equals(host) || "release-assets.githubusercontent.com".equals(host);
            if (!"https".equals(target.getProtocol()) || (!cdnHost && !"cdn.jsdelivr.net".equals(host)
                && !"github.com".equals(host) && !"api.github.com".equals(host))) {
                throw new IOException("Unexpected update host");
            }
            HttpsURLConnection connection = open(target);
            try {
                int status;
                try {
                    status = connection.getResponseCode();
                } catch (SSLHandshakeException failure) {
                    if (!cdnHost || Build.VERSION.SDK_INT > 25) throw failure;
                    connection.disconnect();
                    connection = open(target);
                    connection.setSSLSocketFactory(legacyCdnTls());
                    status = connection.getResponseCode();
                }
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing update redirect");
                    target = new URL(target, location);
                    continue;
                }
                if (status != 200) throw new IOException("Update HTTP status " + status);
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        total += count;
                        if (total > limit) throw new IOException("更新响应超过大小限制");
                        output.write(buffer, 0, count);
                    }
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many update redirects");
    }

    private HttpsURLConnection open(URL target) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "IPTV-TV");
        return connection;
    }

    private SSLSocketFactory legacyCdnTls() throws IOException {
        if (legacyCdnTls != null) return legacyCdnTls;
        try (InputStream root = context.getResources().openRawResource(R.raw.iptv_isrg_root_x1)) {
            KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
            anchors.load(null, null);
            anchors.setCertificateEntry("isrg-root-x1", CertificateFactory.getInstance("X.509").generateCertificate(root));
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(anchors);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust.getTrustManagers(), null);
            legacyCdnTls = ssl.getSocketFactory();
            return legacyCdnTls;
        } catch (GeneralSecurityException failure) {
            throw new IOException("Cannot configure legacy CDN trust", failure);
        }
    }
}
