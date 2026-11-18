package com.iptv.tv;

import static org.junit.Assert.*;

import java.net.URL;
import java.util.List;
import org.junit.Test;

public final class LogoUrlsTest {
    @Test public void preservesTheSourceAndMapsTheSameFileToFallbacks() throws Exception {
        URL source = new URL("https://cdn.jsdelivr.net/gh/example/IPTV@main/logo/CCTV-5+.png");
        List<URL> options = LogoUrls.candidates(source);
        assertEquals(3, options.size());
        assertSame(source, options.get(0));
        assertEquals("https://fastly.jsdelivr.net/gh/example/IPTV@main/logo/CCTV-5+.png", options.get(1).toString());
        assertEquals("https://raw.githubusercontent.com/example/IPTV/main/logo/CCTV-5+.png", options.get(2).toString());
    }

    @Test public void leavesOrdinaryAndSignedLogoUrlsAlone() throws Exception {
        URL source = new URL("http://example.test/logo.png?key=abc%2B1");
        assertEquals(1, LogoUrls.candidates(source).size());
        assertFalse(LogoUrls.supportsLegacyTrust(source));
        URL lookalike = new URL("https://cdn.jsdelivr.net.example.test/gh/example/IPTV@main/logo.png");
        assertEquals(1, LogoUrls.candidates(lookalike).size());
        assertFalse(LogoUrls.supportsLegacyTrust(lookalike));
    }
}
