package com.iptv.tv;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LogoUrls {
    private static final Pattern JSDELIVR_GITHUB =
        Pattern.compile("^/gh/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([A-Za-z0-9_.-]+)/(.+)$");

    static List<URL> candidates(URL source) throws java.net.MalformedURLException {
        List<URL> result = new ArrayList<>();
        result.add(source);
        if (!"https".equals(source.getProtocol()) || !"cdn.jsdelivr.net".equals(source.getHost())) return result;
        Matcher match = JSDELIVR_GITHUB.matcher(source.getPath());
        if (match.matches()) {
            result.add(new URL("https://fastly.jsdelivr.net" + source.getFile()));
            result.add(new URL("https://raw.githubusercontent.com/" + match.group(1) + "/"
                + match.group(2) + "/" + match.group(3)));
        }
        return result;
    }

    static boolean supportsLegacyTrust(URL url) {
        String host = url.getHost();
        return "cdn.jsdelivr.net".equals(host) || "fastly.jsdelivr.net".equals(host)
            || "raw.githubusercontent.com".equals(host);
    }
}
