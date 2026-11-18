package com.iptv.tv;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

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

    private static final Pattern ATTRIBUTE = Pattern.compile("(?:^|\\s)([a-zA-Z0-9_-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern LINE_LABEL = Pattern.compile("[^/?#&=]+");

    static InputStream open(String url) throws IOException {
        return HttpStreams.open(new URL(url), 8000, 12000, null);
    }

    private static Map<String, String> attributes(String line) {
        Map<String, String> values = new HashMap<>();
        Matcher match = ATTRIBUTE.matcher(line);
        while (match.find()) values.put(match.group(1).toLowerCase(Locale.ROOT), match.group(2).trim());
        return values;
    }

    private static String attribute(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? "" : value;
    }

    static Playlist load(String source) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(source), StandardCharsets.UTF_8))) {
            return parse(reader, new URL(source));
        }
    }

    static Playlist parse(BufferedReader reader, URL base) throws IOException {
        Playlist result = new Playlist();
        Channel item = null;
        for (String line; (line = reader.readLine()) != null;) {
            line = line.replace("\uFEFF", "").trim();
            if (line.startsWith("#EXTM3U")) {
                Map<String, String> header = attributes(line);
                String epg = attribute(header, "x-tvg-url");
                if (epg.isEmpty()) epg = attribute(header, "url-tvg");
                String guide = resolveHttp(base, epg.split(",", 2)[0].trim());
                if (!guide.isEmpty()) result.guideUrl = guide;
            } else if (line.startsWith("#EXTINF:")) {
                item = new Channel();
                int separator = metadataEnd(line);
                Map<String, String> fields = attributes(line.substring(0, separator));
                item.id = attribute(fields, "tvg-id");
                item.group = attribute(fields, "group-title");
                if (item.group.isEmpty()) item.group = "未分组";
                item.logo = resolveHttp(base, attribute(fields, "tvg-logo"));
                item.name = separator < line.length() ? line.substring(separator + 1).trim() : "";
                if (item.name.isEmpty()) item.name = attribute(fields, "tvg-name");
            } else if (item != null && !line.startsWith("#") && !line.isEmpty()) {
                int suffix = line.lastIndexOf('$');
                String label = "";
                if (suffix >= 0 && LINE_LABEL.matcher(line.substring(suffix + 1)).matches()) {
                    String encoded = line.substring(suffix + 1);
                    try {
                        label = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8");
                    } catch (IllegalArgumentException malformedLabel) {
                        label = encoded;
                    }
                    line = line.substring(0, suffix);
                }
                String url = resolveHttp(base, line);
                if (url.isEmpty()) continue;
                if (item.lines.isEmpty()) {
                    if (item.name.isEmpty()) item.name = "频道 " + (result.channels.size() + 1);
                    result.channels.add(item);
                }
                item.lines.add(new Line(url, label));
            }
        }
        if (result.channels.isEmpty()) throw new IOException("清单中没有 HTTP 播放频道");
        return result;
    }

    private static int metadataEnd(String line) {
        boolean quoted = false;
        for (int i = 8; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) return i;
        }
        return line.length();
    }

    private static String resolveHttp(URL base, String value) {
        if (value.isEmpty()) return "";
        try {
            URL url = new URL(base, value.replace(" ", "%20"));
            if ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())) {
                return url.toURI().toASCIIString();
            }
        } catch (MalformedURLException | URISyntaxException ignored) {
            // Ignore malformed optional addresses without dropping the other channels.
        }
        return "";
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
