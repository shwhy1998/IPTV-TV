package com.iptv.tv;

import android.app.*;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;

public final class MainActivity extends Activity {
    private static final String DEFAULT_SOURCE = "";
    private static final int DIVIDER = 0xff3e4145;
    private static final int INK = 0xfff7f7f7;
    private static final int MUTED = 0xffc8cacf;
    private static final int LIVE = 0xffa7e1d4;
    private static final int PROGRAMME = 0xff91bdb5;
    private static final int SHEET = 0xf216191b;
    private static final int ROW_COUNT = 5;
    private static final int MAX_LOGO_BYTES = 512 * 1024;
    private static final String PROJECT_LOGOS = "https://cdn.jsdelivr.net/gh/shwhy1998/IPTV@main/logo/";
    private static final boolean MI_TV_4 = "xmen".equals(Build.DEVICE) && "amlogic".equals(Build.HARDWARE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService logoRequests = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> logos = new LruCache<String, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Set<String> loadingLogos = new HashSet<>();
    private final Set<String> failedLogos = new HashSet<>();
    private FrameLayout root;
    private SurfaceView video;
    private LinearLayout drawer, info, tools;
    private TextView groupLabel, status;
    private ImageView infoLogo;
    private TextView infoChannel, infoDivider;
    private ProgrammeLine currentLine, nextLine;
    private final List<ChannelRow> channelRows = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private ExoPlayer player;
    private SharedPreferences prefs;
    private Playlist playlist;
    private Playlist.Channel playing;
    private Playlist.Line playingLine;
    private String activeSource;
    private CatalogueCache catalogueCache;
    private long lastListRefresh, lastGuideRefresh, tuneStarted;
    private final Runnable refreshClock = this::checkRefresh;
    private int toolIndex;
    private final List<LinearLayout> toolItems = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final List<Playlist.Channel> visible = new ArrayList<>();
    private int group, focused, offset, request;
    private boolean loading, drawerOpen, infoOpen;
    private final Runnable hideInfo = () -> {
        infoOpen = false;
        info.setVisibility(View.GONE);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        prefs = getSharedPreferences("iptv", MODE_PRIVATE);
        catalogueCache = new CatalogueCache(getCacheDir());
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new SurfaceView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        status = text("", 19, INK);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        buildOverlays();
        buildTools();
        setContentView(root);
        String source = getIntent().getStringExtra("playlist_url");
        activeSource = source == null ? prefs.getString("source", DEFAULT_SOURCE) : source;
        if (activeSource.isEmpty()) status.setText("按确认键添加直播源");
        else {
            boolean refreshOnLaunch = prefs.getBoolean("refresh_on_launch", true);
            refresh(refreshOnLaunch, refreshOnLaunch, true);
        }
    }

    private void buildOverlays() {
        int width = getResources().getDisplayMetrics().widthPixels;
        drawer = column();
        drawer.setPadding(dp(10), dp(12), dp(10), dp(15));
        drawer.setBackground(panel(0xee151719, DIVIDER, 9));
        int height = getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams drawerPosition = new FrameLayout.LayoutParams(
            Math.round(width * .36f), Math.round(height * .89f), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        drawerPosition.leftMargin = dp(20);
        root.addView(drawer, drawerPosition);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        header.addView(text("‹", 22, MUTED));
        groupLabel = text("", 19, INK);
        groupLabel.setGravity(Gravity.CENTER);
        groupLabel.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(groupLabel, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(text("›", 22, MUTED));
        drawer.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));
        addDivider(drawer);
        LinearLayout rows = column();
        drawer.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < ROW_COUNT; i++) channelRows.add(new ChannelRow(rows));
        drawer.setVisibility(View.GONE);

        info = column();
        info.setPadding(dp(30), dp(38), Math.round(width * .30f), dp(27));
        info.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00000000, 0x87000000, 0xe6000000}));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
            -1, -2, Gravity.BOTTOM | Gravity.LEFT);
        root.addView(info, position);
        LinearLayout station = new LinearLayout(this);
        station.setGravity(Gravity.CENTER_VERTICAL);
        infoLogo = newLogo();
        infoLogo.setBackground(null);
        infoLogo.setPadding(0, 0, 0, 0);
        infoLogo.setScaleType(ImageView.ScaleType.FIT_START);
        infoLogo.setAdjustViewBounds(true);
        infoLogo.setMaxWidth(dp(142));
        station.addView(infoLogo, new LinearLayout.LayoutParams(-2, dp(41)));
        infoDivider = broadcastText("│", 17);
        infoDivider.setTextColor(MUTED);
        infoDivider.setGravity(Gravity.CENTER);
        station.addView(infoDivider, new LinearLayout.LayoutParams(dp(25), -2));
        infoChannel = broadcastText("", 29);
        infoChannel.setTypeface(Typeface.DEFAULT_BOLD);
        station.addView(infoChannel, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams headerPosition = new LinearLayout.LayoutParams(-1, -2);
        headerPosition.bottomMargin = dp(8);
        info.addView(station, headerPosition);
        currentLine = new ProgrammeLine("正在播出");
        nextLine = new ProgrammeLine("下一节目");
        info.setVisibility(View.GONE);
    }

    private TextView broadcastText(String value, int size) {
        TextView label = text(value, size, Color.WHITE);
        label.setIncludeFontPadding(false);
        label.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        return label;
    }

    private final class ProgrammeLine {
        final LinearLayout row = new LinearLayout(MainActivity.this);
        final TextView range = broadcastText("", 16);
        final TextView title = broadcastText("", 18);

        ProgrammeLine(String label) {
            TextView caption = broadcastText(label, 15);
            caption.setTextColor(MUTED);
            range.setTextColor(MUTED);
            range.setFontFeatureSettings("tnum");
            for (TextView value : new TextView[]{caption, range, title}) value.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(caption, new LinearLayout.LayoutParams(dp(76), -1));
            row.addView(range, new LinearLayout.LayoutParams(dp(107), -1));
            row.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
            info.addView(row, new LinearLayout.LayoutParams(-1, dp(26)));
        }

        void bind(Playlist.Programme programme) {
            range.setVisibility(programme == null ? View.GONE : View.VISIBLE);
            range.setText(programme == null ? "" : time(programme.start) + "–" + time(programme.end));
            title.setText(programme == null ? "暂无节目单" : programme.title);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        openPlayer();
        handler.postDelayed(refreshClock, 60_000);
    }

    private void openPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        renderers.setMediaCodecSelector((mime, secure, tunneling) -> {
            List<MediaCodecInfo> codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
            if (!mime.startsWith("video/")) return codecs;
            List<MediaCodecInfo> hardware = new ArrayList<>();
            for (MediaCodecInfo codec : codecs) {
                if (codec.hardwareAccelerated) hardware.add(codec);
            }
            return hardware;
        });
        ExoPlayer.Builder builder = new ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.DEFAULT, true);
        if (MI_TV_4) {
            builder.setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 9000, 350, 1200)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .build());
        }
        player = builder.build();
        player.setVideoSurfaceView(video);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) status.setText("");
            }
            @Override public void onPlayerError(PlaybackException error) {
                android.util.Log.w("IptvPlayback", "Playback failed: " + playing.name, error);
                status.setText("频道无法播放，按菜单键切换线路");
            }
            @Override public void onRenderedFirstFrame() {
                android.util.Log.i("IptvPlayback", "First frame in " + (SystemClock.elapsedRealtime() - tuneStarted) + " ms");
            }
            @Override public void onVideoSizeChanged(VideoSize size) {
                videoSize = size;
                fitVideo();
            }
        });
        if (playing != null) startPlayback();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refreshClock);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        logoRequests.shutdownNow();
        super.onDestroy();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void fitVideo() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width == 0 || height == 0 || videoSize.width == 0 || videoSize.height == 0) return;
        float aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height;
        int fittedWidth = Math.min(width, Math.round(height * aspect));
        int fittedHeight = Math.min(height, Math.round(width / aspect));
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (layout.width != fittedWidth || layout.height != fittedHeight) {
            layout.width = fittedWidth;
            layout.height = fittedHeight;
            video.setLayoutParams(layout);
        }
    }

    private GradientDrawable panel(int fill, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), border);
        return background;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView text(CharSequence value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private void editSource(String previous) {
        Dialog dialog = new Dialog(this);
        LinearLayout body = sheetBody(null);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(INK);
        address.setHintTextColor(MUTED);
        address.setTextSize(16);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setBackground(focusBackground());
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("http://网关地址:5140/playlist.m3u");
        address.setText(previous == null ? "" : previous);
        body.addView(address, new LinearLayout.LayoutParams(-1, dp(48)));
        addSheetAction(body, "保存", () -> {
            String url = address.getText().toString().trim();
            if (!url.contains("://")) url = "http://" + url;
            Uri uri = Uri.parse(url);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                address.setError("请输入有效的 HTTP 地址");
                return;
            }
            if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) {
                url = uri.buildUpon().path("/playlist.m3u").build().toString();
            }
            dialog.dismiss();
            List<String> sources = sources();
            int index = sources.indexOf(previous);
            if (index >= 0) sources.set(index, url);
            else sources.add(url);
            saveSources(sources);
            if (activeSource.isEmpty() || Objects.equals(activeSource, previous)) activateSource(url);
            else manageSources();
        });
        addSheetAction(body, "取消", dialog::dismiss);
        showSheet(dialog, body);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void refresh(boolean refreshList, boolean refreshGuide, boolean readCache) {
        if (loading || activeSource.isEmpty()) return;
        loading = true;
        int id = ++request;
        String source = activeSource;
        Playlist existing = playlist;
        if (existing == null) status.setText("正在读取直播源…");
        long now = SystemClock.elapsedRealtime();
        if (refreshList || existing == null) lastListRefresh = now;
        if (refreshGuide || existing == null) lastGuideRefresh = now;
        network.execute(() -> {
            Playlist latest = readCache ? catalogueCache.read(source) : existing;
            if (readCache && latest != null) publishCatalogue(id, latest);
            boolean changed = false;
            boolean updateGuide = refreshGuide;
            if (refreshList || latest == null) {
                try {
                    Playlist loaded = Playlist.load(source);
                    if (latest != null && Objects.equals(latest.guideUrl, loaded.guideUrl)) {
                        loaded.guide.putAll(latest.guide);
                    }
                    boolean newGuide = latest == null || !Objects.equals(latest.guideUrl, loaded.guideUrl);
                    latest = loaded;
                    changed = true;
                    publishCatalogue(id, latest);
                    updateGuide |= newGuide;
                } catch (IOException failure) {
                    android.util.Log.w("IptvCatalogue", "Playlist refresh failed", failure);
                }
            }
            if (latest != null) {
                if (updateGuide) fetchGuide(source, id, latest);
                else if (changed) catalogueCache.write(source, latest);
            }
            handler.post(() -> {
                if (isDestroyed() || id != request) return;
                loading = false;
                if (playlist == null) status.setText("无法读取直播源，按确认键查看设置");
            });
        });
    }

    private void fetchGuide(String source, int id, Playlist base) {
        try {
            Playlist updated = new Playlist();
            updated.channels.addAll(base.channels);
            updated.guideUrl = base.guideUrl;
            updated.guide.putAll(base.loadGuide());
            catalogueCache.write(source, updated);
            publishCatalogue(id, updated);
        } catch (Exception failure) {
            android.util.Log.w("IptvCatalogue", "EPG refresh failed", failure);
            catalogueCache.write(source, base);
        }
    }

    private void publishCatalogue(int id, Playlist loaded) {
        handler.post(() -> {
            if (isDestroyed() || id != request) return;
            String previous = playingLine == null ? prefs.getString("channel_url", "") : playingLine.url;
            Playlist.Channel channel = loaded.channels.get(0);
            Playlist.Line line = channel.lines.get(0);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (Playlist.Channel entry : loaded.channels) {
                names.add(entry.group);
                for (Playlist.Line candidate : entry.lines) {
                    if (previous.equals(candidate.url)) {
                        channel = entry;
                        line = candidate;
                    }
                }
            }
            String previousGroup = drawerOpen ? groups.get(group) : null;
            int previousFocus = focused;
            playlist = loaded;
            groups.clear();
            groups.addAll(names);
            if (playingLine == null || !playingLine.url.equals(line.url)) tune(channel, line);
            else {
                playing = channel;
                playingLine = line;
                if (infoOpen) renderInfo();
            }
            if (drawerOpen) {
                hideInfo.run();
                group = Math.max(0, groups.indexOf(previousGroup));
                selectGroup();
                focused = Math.min(previousFocus, visible.size() - 1);
                renderDrawer();
            }
        });
    }

    private void checkRefresh() {
        long now = SystemClock.elapsedRealtime();
        int hours = prefs.getInt("refresh_interval", 0);
        long last = Math.min(lastListRefresh, lastGuideRefresh);
        if (hours > 0 && (last == 0 || now - last >= hours * 3_600_000L)) refresh(true, true, false);
        handler.postDelayed(refreshClock, 60_000);
    }

    private List<String> sources() {
        List<String> result = new ArrayList<>();
        String saved = prefs.getString("sources", null);
        if (saved == null) {
            if (!activeSource.isEmpty()) result.add(activeSource);
            return result;
        }
        try {
            JSONArray entries = new JSONArray(saved);
            for (int i = 0; i < entries.length(); i++) result.add(entries.getString(i));
        } catch (org.json.JSONException failure) {
            android.util.Log.w("IptvCatalogue", "Cannot read sources", failure);
        }
        return result;
    }

    private void saveSources(List<String> sources) {
        prefs.edit().putString("sources", new JSONArray(new LinkedHashSet<>(sources)).toString()).apply();
    }

    private void activateSource(String source) {
        prefs.edit().putString("source", source).apply();
        if (source.equals(activeSource) && playlist != null) return;
        activeSource = source;
        request++;
        loading = false;
        lastListRefresh = lastGuideRefresh = 0;
        closeDrawer();
        hideInfo.run();
        playlist = null;
        playing = null;
        playingLine = null;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        if (source.isEmpty()) {
            network.execute(catalogueCache::clear);
            status.setText("按确认键添加直播源");
        } else {
            refresh(true, true, true);
        }
    }

    private void settings() {
        boolean launch = prefs.getBoolean("refresh_on_launch", true);
        int hours = prefs.getInt("refresh_interval", 0);
        choose(null, new CharSequence[]{"直播源",
            switchLabel(hours == 0 ? "定时刷新" : "定时刷新   每 " + hours + " 小时", hours > 0),
            switchLabel("启动刷新", launch), "立即刷新"}, -1, (dialog, index) -> {
            switch (index) {
                case 0: manageSources(); break;
                case 1: chooseInterval(); break;
                case 2:
                    choose(null, new CharSequence[]{switchLabel("", true), switchLabel("", false)}, launch ? 0 : 1,
                        (choice, value) -> {
                            prefs.edit().putBoolean("refresh_on_launch", value == 0).apply();
                            settings();
                        });
                    break;
                case 3:
                    if (activeSource.isEmpty()) showSheetMessage(null, "请先添加直播源");
                    else {
                        boolean wasLoading = loading;
                        refresh(true, true, false);
                        Toast.makeText(this, wasLoading ? "正在刷新" : "已开始刷新", Toast.LENGTH_SHORT).show();
                    }
                    break;
                default: break;
            }
        });
    }

    private static final int[] REFRESH_HOURS = {0, 1, 3, 6, 12, 24};

    private CharSequence switchLabel(String label, boolean on) {
        String prefix = label.isEmpty() ? "" : label + "   ";
        SpannableString result = new SpannableString(prefix + (on ? "ON" : "OFF"));
        result.setSpan(new ForegroundColorSpan(on ? 0xff9cddae : 0xffffa5a5),
            prefix.length(), result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private void chooseInterval() {
        CharSequence[] labels = new CharSequence[REFRESH_HOURS.length];
        int selected = 0;
        for (int i = 0; i < labels.length; i++) {
            int hours = REFRESH_HOURS[i];
            labels[i] = hours == 0 ? switchLabel("", false) : switchLabel("每 " + hours + " 小时", true);
            if (hours == prefs.getInt("refresh_interval", 0)) selected = i;
        }
        choose(null, labels, selected, (dialog, index) -> {
            prefs.edit().putInt("refresh_interval", REFRESH_HOURS[index]).apply();
            settings();
        });
    }

    private void manageSources() {
        List<String> sources = sources();
        String[] labels = new String[sources.size() + 1];
        labels[0] = "添加直播源";
        for (int i = 0; i < sources.size(); i++) labels[i + 1] = sources.get(i);
        int active = sources.indexOf(activeSource);
        choose(null, labels, active < 0 ? -1 : active + 1, (dialog, index) -> {
            if (index == 0) {
                editSource(null);
                return;
            }
            String source = sources.get(index - 1);
            choose(null, new String[]{"使用此源", "查看地址", "编辑地址", "删除此源"}, -1, (actions, action) -> {
                switch (action) {
                    case 0: activateSource(source); break;
                    case 1: showSheetMessage(null, source); break;
                    case 2: editSource(source); break;
                    case 3:
                        choose(null, new String[]{"确认删除直播源"}, -1, (confirm, ignored) -> {
                            sources.remove(source);
                            saveSources(sources);
                            if (source.equals(activeSource)) activateSource(sources.isEmpty() ? "" : sources.get(0));
                            manageSources();
                        });
                        break;
                    default: break;
                }
            });
        });
    }

    private void tune(Playlist.Channel channel) {
        tune(channel, channel == playing ? playingLine : channel.lines.get(0));
    }

    private void tune(Playlist.Channel channel, Playlist.Line line) {
        playing = channel;
        playingLine = line;
        prefs.edit().putString("channel_url", line.url).apply();
        startPlayback();
        showInfo();
    }

    private void startPlayback() {
        if (player == null) return;
        tuneStarted = SystemClock.elapsedRealtime();
        status.setText("正在播放 " + playing.name + "…");
        player.setMediaItem(MediaItem.fromUri(playingLine.url));
        player.prepare();
        player.play();
    }

    private void openDrawer() {
        if (playlist == null) { if (!loading) manageSources(); return; }
        hideInfo.run();
        drawerOpen = true;
        group = Math.max(0, groups.indexOf(playing.group));
        selectGroup();
        focused = Math.max(0, visible.indexOf(playing));
        renderDrawer();
        drawer.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.setVisibility(View.GONE);
    }

    private void selectGroup() {
        visible.clear();
        for (Playlist.Channel entry : playlist.channels) {
            if (entry.group.equals(groups.get(group))) visible.add(entry);
        }
        focused = 0;
        offset = 0;
    }

    private void renderDrawer() {
        int count = Math.min(ROW_COUNT, visible.size());
        offset = Math.max(0, Math.min(offset, focused));
        if (focused >= offset + count) offset = focused - count + 1;
        groupLabel.setText(groups.get(group));
        for (int slot = 0; slot < ROW_COUNT; slot++) {
            ChannelRow row = channelRows.get(slot);
            row.container.setVisibility(slot < count ? View.VISIBLE : View.INVISIBLE);
            if (slot < count) row.bind(visible.get(offset + slot), offset + slot == focused);
        }
    }

    private final class ChannelRow {
        final LinearLayout container = new LinearLayout(MainActivity.this);
        final ImageView logo = newLogo();
        final TextView name = text("", 18, INK);
        final TextView title = programmeText(14, MUTED, 3);
        final TextView live = text("在播", 12, LIVE);
        final GradientDrawable selected = focusFrame();

        ChannelRow(LinearLayout parent) {
            container.setGravity(Gravity.CENTER_VERTICAL);
            container.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, 0, 1);
            position.topMargin = dp(3);
            parent.addView(container, position);
            container.addView(logo, new LinearLayout.LayoutParams(dp(53), dp(30)));
            LinearLayout labels = column();
            labels.setPadding(dp(9), 0, dp(6), 0);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setIncludeFontPadding(false);
            title.setIncludeFontPadding(false);
            labels.addView(name);
            LinearLayout.LayoutParams titlePosition = new LinearLayout.LayoutParams(-1, -2);
            titlePosition.topMargin = dp(3);
            labels.addView(title, titlePosition);
            container.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            container.addView(live);
        }

        void bind(Playlist.Channel channel, boolean focused) {
            container.setSelected(focused);
            container.setBackground(focused ? selected : null);
            name.setText(channel.name);
            Playlist.Programme programme = playlist.programme(channel, false);
            title.setText(programme == null ? "暂无节目单" : programme.title);
            title.setTextColor(programme == null ? MUTED : focused ? LIVE : PROGRAMME);
            live.setVisibility(channel == playing ? View.VISIBLE : View.GONE);
            bindLogo(logo, channel);
        }
    }

    private ImageView newLogo() {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return view;
    }

    private TextView programmeText(int size, int color, int lines) {
        TextView label = text("", size, color);
        label.setSingleLine(false);
        label.setMaxLines(lines);
        label.setIncludeFontPadding(false);
        return label;
    }

    private void bindLogo(ImageView view, Playlist.Channel channel) {
        String url = channel.logo;
        view.setTag(url);
        Bitmap cached = logos.get(url);
        view.setImageBitmap(cached);
        view.setVisibility(cached == null ? View.INVISIBLE : View.VISIBLE);
        if (cached == null && !url.isEmpty() && !failedLogos.contains(url) && loadingLogos.add(url)) {
            logoRequests.execute(() -> {
                Bitmap picture = null;
                try {
                    picture = decodeLogo(url);
                } catch (Exception ignored) {}
                Bitmap result = picture;
                handler.post(() -> {
                    if (isDestroyed()) return;
                    loadingLogos.remove(url);
                    if (result == null) {
                        failedLogos.add(url);
                        return;
                    }
                    logos.put(url, result);
                    for (ChannelRow row : channelRows) updateLogo(row.logo, url, result);
                    updateLogo(infoLogo, url, result);
                    if (url.equals(infoLogo.getTag())) infoDivider.setVisibility(View.VISIBLE);
                });
            });
        }
    }

    private void updateLogo(ImageView view, String url, Bitmap bitmap) {
        if (url.equals(view.getTag())) {
            view.setImageBitmap(bitmap);
            view.setVisibility(View.VISIBLE);
        }
    }

    private Bitmap decodeLogo(String url) throws Exception {
        byte[] data;
        try (InputStream stream = openLogo(url); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int length; (length = stream.read(buffer)) != -1;) {
                if (bytes.size() + length > MAX_LOGO_BYTES) return null;
                bytes.write(buffer, 0, length);
            }
            data = bytes.toByteArray();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > dp(96)) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private InputStream openLogo(String url) throws IOException {
        if (url.startsWith(PROJECT_LOGOS)) {
            String name = Uri.decode(url.substring(PROJECT_LOGOS.length()));
            if (name.endsWith(".png") && !name.contains("/") && !name.contains("\\")) {
                try {
                    return getAssets().open("channel-logos/" + name);
                } catch (IOException ignored) {
                    // The list can reference logos newer than the installed APK.
                }
            }
        }
        return Playlist.open(url);
    }

    private String time(long milliseconds) {
        return clock.format(new Date(milliseconds));
    }

    private void showInfo() {
        showInfo(true);
    }

    private void showInfo(boolean autoHide) {
        if (playing == null) return;
        infoOpen = true;
        renderInfo();
        info.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        if (autoHide) handler.postDelayed(hideInfo, 5500);
    }

    private void renderInfo() {
        bindLogo(infoLogo, playing);
        int logoVisibility = infoLogo.getDrawable() == null ? View.GONE : View.VISIBLE;
        infoLogo.setVisibility(logoVisibility);
        infoDivider.setVisibility(logoVisibility);
        infoChannel.setText(playing.name);
        Playlist.Programme current = playlist.programme(playing, false);
        Playlist.Programme next = playlist.programme(playing, true);
        currentLine.bind(current);
        nextLine.bind(next);
        nextLine.row.setVisibility(next == null ? View.GONE : View.VISIBLE);
    }

    private void buildTools() {
        tools = new LinearLayout(this);
        tools.setPadding(dp(8), dp(6), dp(8), dp(6));
        tools.setBackground(panel(0xe216191b, DIVIDER, 12));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
            dp(252), dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        position.bottomMargin = dp(24);
        root.addView(tools, position);
        addTool("线路", android.R.drawable.ic_menu_directions);
        addTool("设置", android.R.drawable.ic_menu_preferences);
        tools.setVisibility(View.GONE);
    }

    private void addTool(String label, int icon) {
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        ImageView picture = new ImageView(this);
        picture.setImageResource(icon);
        picture.setColorFilter(INK);
        item.addView(picture, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = text(label, 13, INK);
        LinearLayout.LayoutParams labelPosition = new LinearLayout.LayoutParams(-2, -2);
        labelPosition.topMargin = dp(3);
        item.addView(name, labelPosition);
        tools.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        toolItems.add(item);
    }

    private void highlightTool() {
        for (int i = 0; i < toolItems.size(); i++) {
            LinearLayout item = toolItems.get(i);
            item.setSelected(i == toolIndex);
            item.setBackground(i == toolIndex ? focusFrame() : null);
        }
    }

    private GradientDrawable focusFrame() {
        return panel(0x33ffffff, 0xffdde8e6, 7);
    }

    private StateListDrawable focusBackground() {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focusFrame());
        background.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return background;
    }

    private LinearLayout sheetBody(String heading) {
        LinearLayout body = column();
        body.setPadding(dp(16), dp(12), dp(16), dp(10));
        body.setBackground(panel(SHEET, DIVIDER, 12));
        if (heading != null) {
            TextView title = text(heading, 21, INK);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setPadding(dp(10), 0, 0, 0);
            body.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));
            addDivider(body);
        }
        return body;
    }

    private void showSheet(Dialog dialog, LinearLayout body) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(body);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams position = window.getAttributes();
        position.y = dp(102);
        window.setAttributes(position);
        window.getDecorView().setSystemUiVisibility(root.getSystemUiVisibility() | 5894);
        dialog.show();
        window.setLayout(Math.min(dp(460), root.getResources().getDisplayMetrics().widthPixels - dp(40)), -2);
    }

    private TextView addSheetAction(LinearLayout body, String label, Runnable action) {
        TextView button = text(label, 16, INK);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(focusBackground());
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        body.addView(button, new LinearLayout.LayoutParams(-1, dp(42)));
        return button;
    }

    private void choose(String title, CharSequence[] labels, int selected, DialogInterface.OnClickListener action) {
        Dialog dialog = new Dialog(this);
        LinearLayout body = sheetBody(title);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        LinearLayout rows = column();
        scroll.addView(rows);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(Math.min(labels.length, 5) * 46)));
        View initial = null;
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(10), 0);
            row.setBackground(focusBackground());
            row.setFocusable(true);
            row.setSelected(i == selected);
            TextView label = text(labels[i], 17, i == selected ? LIVE : INK);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == selected) row.addView(text("已选", 13, LIVE));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                action.onClick(dialog, index);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
            if (initial == null || i == selected) initial = row;
        }
        addDivider(body);
        TextView close = addSheetAction(body, "取消", dialog::dismiss);
        showSheet(dialog, body);
        (initial == null ? close : initial).requestFocus();
    }

    private void showSheetMessage(String heading, String message) {
        Dialog dialog = new Dialog(this);
        LinearLayout body = sheetBody(heading);
        TextView text = programmeText(16, MUTED, 3);
        text.setText(message);
        text.setPadding(dp(10), dp(12), dp(10), dp(12));
        body.addView(text);
        TextView close = addSheetAction(body, "关闭", dialog::dismiss);
        showSheet(dialog, body);
        close.requestFocus();
    }

    private boolean handleTools(int key, KeyEvent event) {
        switch (key) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0) tools.setVisibility(View.GONE);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                toolIndex = (toolIndex + step + toolItems.size()) % toolItems.size();
                highlightTool();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) {
                    switch (toolIndex) {
                        case 0: chooseLine(); break;
                        case 1: settings(); break;
                        default: break;
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private void chooseLine() {
        if (playing == null) {
            showSheetMessage("线路", "请先选择频道");
            return;
        }
        Playlist.Channel channel = playing;
        String[] labels = new String[channel.lines.size()];
        for (int i = 0; i < labels.length; i++) {
            String label = channel.lines.get(i).label;
            labels[i] = label.isEmpty() ? "线路 " + (i + 1) : label;
        }
        choose(null, labels, channel.lines.indexOf(playingLine), (dialog, index) -> {
                tools.setVisibility(View.GONE);
                tune(channel, channel.lines.get(index));
        });
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (tools.getVisibility() == View.VISIBLE && handleTools(key, event)) return true;
        switch (key) {
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    closeDrawer();
                    hideInfo.run();
                    highlightTool();
                    tools.setVisibility(View.VISIBLE);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() != 0) return true;
                if (drawerOpen) {
                    Playlist.Channel channel = visible.get(focused);
                    closeDrawer();
                    tune(channel);
                } else if (infoOpen) {
                    handler.removeCallbacks(hideInfo);
                    hideInfo.run();
                } else showInfo(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (drawerOpen) {
                    int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    group = (group + step + groups.size()) % groups.size();
                    selectGroup();
                    renderDrawer();
                } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) openDrawer();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                int step = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                if (drawerOpen) {
                    focused = Math.max(0, Math.min(visible.size() - 1, focused + step));
                    renderDrawer();
                } else if (playing != null && event.getRepeatCount() == 0) {
                    int index = playlist.channels.indexOf(playing);
                    int count = playlist.channels.size();
                    tune(playlist.channels.get((index + step + count) % count));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (drawerOpen) { closeDrawer(); return true; }
                if (event.getRepeatCount() == 0) {
                    choose("退出 IPTV？", new String[]{"退出"}, -1, (dialog, index) -> finish());
                }
                return true;
            default:
                break;
        }
        return super.onKeyDown(key, event);
    }
}
