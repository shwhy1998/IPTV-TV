package com.iptv.tv;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

final class HttpStreams {
    static InputStream open(URL target, int connectTimeout, int readTimeout, SSLSocketFactory tls)
        throws IOException {
        if (!"http".equals(target.getProtocol()) && !"https".equals(target.getProtocol())) {
            throw new IOException("Expected an HTTP URL");
        }
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        if (tls != null && connection instanceof HttpsURLConnection) {
            connection.setInstanceFollowRedirects(false);
            ((HttpsURLConnection) connection).setSSLSocketFactory(tls);
        }
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            PushbackInputStream stream = new PushbackInputStream(connection.getInputStream(), 2);
            int first = stream.read();
            int second = stream.read();
            if (second >= 0) stream.unread(second);
            if (first >= 0) stream.unread(first);
            InputStream body = first == 31 && second == 139 ? new GZIPInputStream(stream) : stream;
            return new FilterInputStream(body) {
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        connection.disconnect();
                    }
                }
            };
        } catch (IOException failure) {
            connection.disconnect();
            throw failure;
        }
    }
}
