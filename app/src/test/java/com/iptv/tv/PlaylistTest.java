package com.iptv.tv;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PlaylistTest {
    private Playlist parse(String m3u) throws IOException {
        return Playlist.parse(new BufferedReader(new StringReader(m3u)), new URL("http://example.test/iptv/list.m3u"));
    }

    @Test public void readsAllLinesAndTheirOwnLabels() throws Exception {
        Playlist playlist = parse("#EXTM3U x-tvg-url=\"guide.xml,extra.xml\"\n"
            + "#EXTINF:-1 tvg-id=\"one\" group-title=\"本地,新闻\" tvg-logo=\"台标 A.png\",频道一\n"
            + "one.ts$%E9%AB%98%E6%B8%85\n"
            + "two.ts?token=a%2B1$4K+\n");
        Playlist.Channel channel = playlist.channels.get(0);
        assertEquals("http://example.test/iptv/guide.xml", playlist.guideUrl);
        assertEquals("本地,新闻", channel.group);
        assertEquals("频道一", channel.name);
        assertEquals("one", channel.id);
        assertEquals("http://example.test/iptv/%E5%8F%B0%E6%A0%87%20A.png", channel.logo);
        assertEquals(2, channel.lines.size());
        assertEquals("高清", channel.lines.get(0).label);
        assertEquals("4K+", channel.lines.get(1).label);
        assertEquals("http://example.test/iptv/two.ts?token=a%2B1", channel.lines.get(1).url);
    }

    @Test public void skipsInvalidLinksWithoutDroppingTheChannel() throws Exception {
        Playlist playlist = parse("\uFEFF#EXTM3U URL-TVG=\"guide.xml\"\n"
            + "#EXTINF:-1 TVG-LOGO=\"http://[invalid/png\" TVG-NAME=\"可用频道\"\n"
            + "rtp://239.1.2.3:1234\nhttp://[invalid/line\n/valid.ts\n");
        Playlist.Channel channel = playlist.channels.get(0);
        assertEquals("可用频道", channel.name);
        assertEquals("未分组", channel.group);
        assertEquals("", channel.logo);
        assertEquals("http://example.test/valid.ts", channel.lines.get(0).url);
    }

    @Test(expected = IOException.class) public void rejectsAnEmptyCatalogue() throws Exception {
        parse("#EXTM3U\n#EXTINF:-1,Channel\nrtp://239.1.2.3:1234\n");
    }

    @Test public void findsTheCurrentAndNextProgramme() throws Exception {
        Playlist playlist = parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",Channel\n/valid.ts\n");
        long now = System.currentTimeMillis();
        List<Playlist.Programme> schedule = Arrays.asList(
            new Playlist.Programme(now - 120_000, now - 60_000, "Past"),
            new Playlist.Programme(now - 60_000, now + 60_000, "Current"),
            new Playlist.Programme(now + 60_000, now + 120_000, "Next"));
        playlist.guide.put("one", schedule);
        Playlist.Channel channel = playlist.channels.get(0);
        assertEquals("Current", playlist.programme(channel, false).title);
        assertEquals("Next", playlist.programme(channel, true).title);
    }
}
