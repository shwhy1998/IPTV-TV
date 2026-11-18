package com.iptv.tv;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CatalogueRepository {
    interface Listener {
        void onCatalogue(Playlist catalogue);
        void onComplete(String result);
    }

    private final CatalogueCache cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    CatalogueRepository(CatalogueCache cache) {
        this.cache = cache;
    }

    static boolean refreshDue(int hours, long previous, long now) {
        return hours > 0 && (previous == 0 || now - previous >= hours * 3_600_000L);
    }

    void refresh(String source, Playlist current, boolean fetchList, boolean fetchGuide,
                 boolean readCache, Listener listener) {
        worker.execute(() -> {
            Playlist latest = readCache ? cache.read(source) : current;
            if (readCache && latest != null) listener.onCatalogue(latest);
            boolean changed = false;
            boolean updateGuide = fetchGuide;
            boolean listFailed = false;
            if (fetchList || latest == null) {
                try {
                    Playlist fresh = Playlist.load(source);
                    boolean guideChanged = latest == null || !Objects.equals(latest.guideUrl, fresh.guideUrl);
                    if (!guideChanged) fresh.guide.putAll(latest.guide);
                    latest = fresh;
                    changed = true;
                    updateGuide |= guideChanged;
                    listener.onCatalogue(latest);
                } catch (IOException failure) {
                    listFailed = true;
                    android.util.Log.w("IptvCatalogue", "Playlist refresh failed", failure);
                }
            }
            boolean hasGuide = latest != null && latest.guideUrl != null;
            boolean guideFailed = false;
            if (latest != null) {
                if (updateGuide && hasGuide) guideFailed = !publishGuide(source, latest, listener);
                else if (changed) cache.write(source, latest);
            }
            listener.onComplete(result(listFailed, hasGuide, guideFailed));
        });
    }

    private boolean publishGuide(String source, Playlist base, Listener listener) {
        try {
            Playlist updated = new Playlist();
            updated.channels.addAll(base.channels);
            updated.guideUrl = base.guideUrl;
            updated.guide.putAll(base.loadGuide());
            cache.write(source, updated);
            listener.onCatalogue(updated);
            return true;
        } catch (Exception failure) {
            android.util.Log.w("IptvCatalogue", "EPG refresh failed", failure);
            cache.write(source, base);
            return false;
        }
    }

    private String result(boolean listFailed, boolean hasGuide, boolean guideFailed) {
        if (listFailed) return "刷新失败：无法读取直播源";
        if (guideFailed) return "直播源已刷新，EPG 刷新失败";
        return hasGuide ? "刷新完成：直播源和 EPG 已更新" : "直播源刷新完成，未配置 EPG";
    }

    void clearCache() {
        worker.execute(cache::clear);
    }

    void close() {
        worker.shutdownNow();
    }
}
