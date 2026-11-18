package com.iptv.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

final class LogoLoader {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Context context;
    private final int targetPixels;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable dispatch = this::schedule;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Map<ImageView, Binding> bindings = new IdentityHashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final LruCache<String, Long> retryAfter = new LruCache<>(100);
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(String url, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private final Map<String, SSLSocketFactory> legacyTls = new HashMap<>();
    private boolean closed;

    private static final class Binding {
        final String url, channel;
        final int emptyVisibility;
        final Runnable changed;

        Binding(String url, String channel, int emptyVisibility, Runnable changed) {
            this.url = url;
            this.channel = channel;
            this.emptyVisibility = emptyVisibility;
            this.changed = changed;
        }
    }

    LogoLoader(Context context, int targetPixels) {
        this.context = context.getApplicationContext();
        this.targetPixels = targetPixels;
    }

    void bind(ImageView view, Playlist.Channel channel, int emptyVisibility, Runnable changed) {
        Binding previous = bindings.get(view);
        if (previous != null && previous.url.equals(channel.logo) && previous.channel.equals(channel.name)) {
            requestVisible();
            return;
        }
        Binding binding = new Binding(channel.logo, channel.name, emptyVisibility, changed);
        bindings.put(view, binding);
        show(view, binding, bitmaps.get(binding.url));
        requestVisible();
    }

    private void requestVisible() {
        main.removeCallbacks(dispatch);
        main.post(dispatch);
    }

    void unbind(ImageView view) {
        bindings.remove(view);
    }

    private void show(ImageView view, Binding binding, Bitmap bitmap) {
        view.setImageBitmap(bitmap);
        view.setVisibility(bitmap == null ? binding.emptyVisibility : View.VISIBLE);
        view.setContentDescription(bitmap == null ? null : "台标 " + binding.channel);
        if (binding.changed != null) binding.changed.run();
    }

    private void schedule() {
        if (closed) return;
        for (Binding binding : bindings.values()) {
            if (pending.size() >= 2) return;
            String url = binding.url;
            Long retry = retryAfter.get(url);
            if (!url.isEmpty() && bitmaps.get(url) == null && (retry == null || retry <= SystemClock.elapsedRealtime())
                && pending.add(url)) {
                workers.execute(() -> load(url));
            }
        }
    }

    private void load(String url) {
        Bitmap bitmap = null;
        try {
            for (URL candidate : LogoUrls.candidates(new URL(url))) {
                try {
                    bitmap = decode(candidate);
                    break;
                } catch (IOException failure) {
                    Log.d("IptvLogos", candidate.getHost() + ": " + failure.getClass().getSimpleName()
                        + (failure.getMessage() != null && failure.getMessage().startsWith("HTTP ")
                            ? " " + failure.getMessage() : ""));
                }
            }
        } catch (IOException invalid) {
            Log.d("IptvLogos", "Invalid logo address");
        }
        Bitmap loaded = bitmap;
        main.post(() -> {
            if (closed) return;
            pending.remove(url);
            if (loaded == null) retryAfter.put(url, SystemClock.elapsedRealtime() + 15_000);
            else {
                bitmaps.put(url, loaded);
                for (Map.Entry<ImageView, Binding> entry : bindings.entrySet()) {
                    if (url.equals(entry.getValue().url)) show(entry.getKey(), entry.getValue(), loaded);
                }
            }
            schedule();
        });
    }

    private Bitmap decode(URL url) throws IOException {
        byte[] data;
        try (InputStream input = open(url); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Logo too large");
                bytes.write(buffer, 0, count);
            }
            data = bytes.toByteArray();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        int pixels = Math.max(options.outWidth, options.outHeight);
        if (pixels <= 0) throw new IOException("Invalid logo image");
        options.inSampleSize = 1;
        while (pixels / options.inSampleSize > targetPixels) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Unsupported logo image");
        return bitmap;
    }

    private InputStream open(URL url) throws IOException {
        try {
            return HttpStreams.open(url, 2500, 3500, null);
        } catch (SSLHandshakeException failure) {
            if (Build.VERSION.SDK_INT > 25 || !LogoUrls.supportsLegacyTrust(url)) throw failure;
            return HttpStreams.open(url, 2500, 3500, legacyTls(url.getHost()));
        }
    }

    private synchronized SSLSocketFactory legacyTls(String host) throws IOException {
        SSLSocketFactory cached = legacyTls.get(host);
        if (cached != null) return cached;
        int certificate;
        switch (host) {
            case "cdn.jsdelivr.net": certificate = R.raw.iptv_sectigo_root_r46; break;
            case "fastly.jsdelivr.net": certificate = R.raw.iptv_globalsign_root_r46; break;
            case "raw.githubusercontent.com": certificate = R.raw.iptv_isrg_root_x1; break;
            default: throw new IOException("No legacy trust available for the host");
        }
        try (InputStream root = context.getResources().openRawResource(certificate)) {
            KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
            anchors.load(null, null);
            anchors.setCertificateEntry(host, CertificateFactory.getInstance("X.509").generateCertificate(root));
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(anchors);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust.getTrustManagers(), null);
            SSLSocketFactory factory = ssl.getSocketFactory();
            legacyTls.put(host, factory);
            return factory;
        } catch (java.security.GeneralSecurityException failure) {
            throw new IOException("Legacy CDN trust initialization failed", failure);
        }
    }

    void close() {
        closed = true;
        bindings.clear();
        pending.clear();
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
