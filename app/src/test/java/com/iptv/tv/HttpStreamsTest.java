package com.iptv.tv;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public final class HttpStreamsTest {
    @Test public void opensPlainAndGzipResponsesAndReportsHttpFailures() throws Exception {
        byte[] plain = "频道节目单".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }

        try (ServerSocket server = new ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))) {
            FutureTask<Void> serve = new FutureTask<>(() -> {
                respond(server, "200 OK", plain);
                respond(server, "200 OK", compressed.toByteArray());
                respond(server, "404 Not Found", new byte[0]);
                return null;
            });
            Thread worker = new Thread(serve, "local-http-fixture");
            worker.setDaemon(true);
            worker.start();
            URL base = new URL("http://127.0.0.1:" + server.getLocalPort());
            for (String path : new String[]{"/plain", "/gzip"}) {
                try (InputStream stream = HttpStreams.open(new URL(base, path), 1500, 1500, null)) {
                    assertArrayEquals(plain, stream.readAllBytes());
                }
            }
            try {
                HttpStreams.open(new URL(base, "/missing"), 1500, 1500, null);
                fail("Expected HTTP 404");
            } catch (IOException failure) {
                assertEquals("HTTP 404", failure.getMessage());
            }
            serve.get(3, TimeUnit.SECONDS);
        }
    }

    private static void respond(ServerSocket server, String status, byte[] body) throws IOException {
        try (Socket client = server.accept()) {
            client.setSoTimeout(1500);
            BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            for (String line; (line = request.readLine()) != null && !line.isEmpty();) {
                // Consume the request headers before writing the response.
            }
            OutputStream response = client.getOutputStream();
            response.write(("HTTP/1.1 " + status + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            response.write(body);
        }
    }
}
