package com.iptv.tv;

import android.util.Xml;
import android.net.Uri;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

final class Playlist implements Serializable {
    private static final long serialVersionUID = 1L;
    static final class Channel implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, name, group, logo;
        final List<Line> lines = new ArrayList<>();
    }

    static final class Line implements Serializable {
        private static final long serialVersionUID = 1L;
        final String url, label;

        Line(String url, String label) {
            this.url = url;
            this.label = label;
        }
    }

    static final class Programme implements Serializable {
        private static final long serialVersionUID = 1L;
        final long start, end;
        final String title;
        Programme(long start, long end, String title) {
            this.start = start;
            this.end = end;
            this.title = title;
        }
    }

    final List<Channel> channels = new ArrayList<>();
    final Map<String, List<Programme>> guide = new HashMap<>();
    String guideUrl;

    static InputStream open(String url) throws IOException {
        URL target = new URL(url);
        if (!target.getProtocol().matches("https?")) throw new IOException("Expected an HTTP URL");
        URLConnection connection = target.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        PushbackInputStream stream = new PushbackInputStream(connection.getInputStream(), 2);
        int first = stream.read(), second = stream.read();
        if (second >= 0) stream.unread(second);
        if (first >= 0) stream.unread(first);
        return first == 31 && second == 139 ? new GZIPInputStream(stream) : stream;
    }

    static String attribute(String line, String key) {
        Matcher match = Pattern.compile("(?:^|\\s)" + key + "=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(line);
        return match.find() ? match.group(1).trim() : "";
    }

    static Playlist load(String source) throws IOException {
        Playlist result = new Playlist();
        URL base = new URL(source);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(source), StandardCharsets.UTF_8))) {
            Channel item = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.startsWith("#EXTM3U")) {
                    String epg = attribute(line, "x-tvg-url");
                    if (epg.isEmpty()) epg = attribute(line, "url-tvg");
                    if (!epg.isEmpty()) result.guideUrl = new URL(base, epg.split(",")[0].trim()).toString();
                } else if (line.startsWith("#EXTINF:")) {
                    item = new Channel();
                    item.id = attribute(line, "tvg-id");
                    item.group = attribute(line, "group-title");
                    if (item.group.isEmpty()) item.group = "未分组";
                    item.logo = attribute(line, "tvg-logo");
                    if (!item.logo.isEmpty()) item.logo = new URL(base, item.logo).toString();
                    boolean quoted = false;
                    for (int i = 8; i < line.length(); i++) {
                        char c = line.charAt(i);
                        if (c == '"') quoted = !quoted;
                        if (c == ',' && !quoted) {
                            item.name = line.substring(i + 1).trim();
                            break;
                        }
                    }
                    if (item.name == null || item.name.isEmpty()) item.name = attribute(line, "tvg-name");
                } else if (item != null && !line.startsWith("#") && !line.isEmpty()) {
                    int suffix = line.lastIndexOf('$');
                    String label = "";
                    if (suffix >= 0 && suffix + 1 < line.length()
                        && line.substring(suffix + 1).matches("[^/?#&=]+")) {
                        label = Uri.decode(line.substring(suffix + 1));
                        line = line.substring(0, suffix);
                    }
                    URL url = new URL(base, line);
                    if ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())) {
                        if (item.lines.isEmpty()) {
                            if (item.name.isEmpty()) item.name = "频道 " + (result.channels.size() + 1);
                            result.channels.add(item);
                        }
                        item.lines.add(new Line(url.toString(), label));
                    }
                }
            }
        }
        if (result.channels.isEmpty()) throw new IOException("清单中没有 HTTP 播放频道");
        return result;
    }

    Map<String, List<Programme>> loadGuide() throws Exception {
        Map<String, List<Programme>> result = new HashMap<>();
        if (guideUrl == null) return result;
        Set<String> ids = new HashSet<>();
        for (Channel channel : channels) if (!channel.id.isEmpty()) ids.add(channel.id);
        SimpleDateFormat date = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);
        date.setLenient(false);
        long now = System.currentTimeMillis();
        try (InputStream stream = open(guideUrl)) {
            XmlPullParser xml = Xml.newPullParser();
            xml.setInput(stream, null);
            String id = null, title = null;
            long start = 0, end = 0;
            for (int event = xml.getEventType(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event == XmlPullParser.START_TAG && "programme".equals(xml.getName())) {
                    id = xml.getAttributeValue(null, "channel");
                    title = null;
                    try {
                        start = date.parse(xml.getAttributeValue(null, "start")).getTime();
                        end = date.parse(xml.getAttributeValue(null, "stop")).getTime();
                    } catch (Exception ignored) {
                        id = null;
                    }
                    if (!ids.contains(id) || end <= now || start > now + 172800000L) id = null;
                } else if (id != null && event == XmlPullParser.START_TAG && "title".equals(xml.getName())) {
                    title = xml.nextText();
                } else if (id != null && event == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                    if (title != null) {
                        List<Programme> entries = result.get(id);
                        if (entries == null) {
                            entries = new ArrayList<>();
                            result.put(id, entries);
                        }
                        entries.add(new Programme(start, end, title));
                    }
                    id = null;
                }
            }
        }
        for (List<Programme> entries : result.values()) {
            Collections.sort(entries, (first, second) -> Long.compare(first.start, second.start));
        }
        return result;
    }

    Programme programme(Channel channel, boolean upcoming) {
        long now = System.currentTimeMillis();
        List<Programme> entries = guide.get(channel.id);
        if (entries == null) return null;
        for (Programme p : entries) {
            if (upcoming ? p.start > now : p.start <= now && p.end > now) return p;
        }
        return null;
    }
}
