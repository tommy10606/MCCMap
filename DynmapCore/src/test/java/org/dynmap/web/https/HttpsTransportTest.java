package org.dynmap.web.https;

import static org.junit.jupiter.api.Assertions.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.eclipse.jetty.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpsTransportTest {
    @TempDir Path temp;
    private final AtomicInteger mapCalls = new AtomicInteger();

    private final class Site implements AutoCloseable {
        final NativeHttps https;
        final Server server = new Server();
        final int port;
        Site(int publicPort, boolean redirect) throws Exception {
            var settings = new HttpsSettings("map.example.com", publicPort, false, false, "", "127.0.0.1", 0, redirect);
            var data = temp.resolve("site-" + publicPort + "-" + redirect);
            var store = new TlsStore(data, data.resolve("web"), settings);
            var material = Certificates.create(settings.domain(), 1, Instant.now().minusSeconds(60), Instant.now().plusSeconds(86400));
            store.install(settings.domain(), material.key(), material.chain());
            https = new NativeHttps(settings, store, null);
            var connector = https.connector(server);
            connector.setHost("127.0.0.1"); connector.setPort(0); connector.setIdleTimeout(3000);
            server.addConnector(connector);
            server.setHandler(https.protect(new Handler.Abstract() {
                @Override public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) {
                    mapCalls.incrementAndGet(); response.setStatus(200);
                    byte[] bytes = "encrypted map content".getBytes(StandardCharsets.US_ASCII);
                    response.getHeaders().put("Content-Length", bytes.length);
                    response.write(true, java.nio.ByteBuffer.wrap(bytes), callback); return true;
                }
            }));
            https.start(server, () -> {try {server.start();} catch(Exception failure) {throw new IllegalStateException(failure);}}, false);
            port = connector.getLocalPort();
        }
        String plain(String request) throws Exception {
            try(var socket = new Socket("127.0.0.1", port)) {return exchange(socket, request);}
        }
        String tls(String protocol, String request) throws Exception {
            var trust = new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {return new java.security.cert.X509Certificate[0];}
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            };
            var context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, null);
            try(var raw = new Socket("127.0.0.1", port);
                var socket = (SSLSocket)context.getSocketFactory().createSocket(raw, "map.example.com", port, true)) {
                socket.setEnabledProtocols(new String[]{protocol}); return exchange(socket, request);
            }
        }
        @Override public void close() throws Exception {https.close(); server.stop();}
    }
    private String exchange(Socket socket, String request) throws Exception {
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
    private String request(String method, String target, String headers) {
        return method + " " + target + " HTTP/1.1\r\nHost: map.example.com\r\nConnection: close\r\n" + headers + "\r\n";
    }
    @Test void httpRedirectsOnTheTlsPortAndPreservesMapPathAndQuery() throws Exception {
        for(int publicPort : new int[]{8123, 443}) try(var site = new Site(publicPort, true)) {
            String path = "/tiles/world/flat/0_0/0_0.png?worldname=world&zoom=3";
            for(String method : new String[]{"GET", "HEAD"}) {
                String response = site.plain(request(method, path, ""));
                assertTrue(response.startsWith("HTTP/1.1 308"), response);
                String target = "https://map.example.com" + (publicPort == 443 ? "" : ":8123") + path;
                assertTrue(response.toLowerCase(java.util.Locale.ROOT).contains("location: " + target), response);
                assertTrue(response.endsWith("\r\n\r\n")); assertFalse(response.contains("encrypted map content"));
            }
            assertEquals(0, mapCalls.get());
        }
    }
    @Test void tls12And13StillServeTheMapWithoutRedirectLoops() throws Exception {
        try(var site = new Site(8123, true)) {
            for(String protocol : new String[]{"TLSv1.2", "TLSv1.3"}) {
                String response = site.tls(protocol, request("GET", "/up/configuration", ""));
                assertTrue(response.startsWith("HTTP/1.1 200"), response);
                assertTrue(response.endsWith("encrypted map content"));
            }
            assertEquals(2, mapCalls.get());
        }
    }
    @Test void forgedSchemesAndProxyHeadersCannotExposeTheMapOrChangeRedirectAuthority() throws Exception {
        try(var site = new Site(8123, true)) {
            for(String target : new String[]{"/up/configuration", "https://evil.example/up/configuration", "http://evil.example/"}) {
                String response = site.plain("GET " + target + " HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\nX-Forwarded-Proto: https\r\nForwarded: proto=https;host=evil.example\r\n\r\n");
                assertTrue(response.startsWith("HTTP/1.1 308"), response);
                assertTrue(response.contains("https://map.example.com:8123/"), response);
                assertFalse(response.contains("evil.example")); assertFalse(response.contains("encrypted map content"));
            }
            assertEquals(0, mapCalls.get());
        }
    }
    @Test void plaintextWritesAndMalformedTargetsNeverReachTheMap() throws Exception {
        try(var site = new Site(8123, true)) {
            for(String method : new String[]{"POST", "PUT", "OPTIONS"}) {
                String response = site.plain(request(method, "/up/configuration", "Content-Length: 0\r\n"));
                assertTrue(response.startsWith("HTTP/1.1 405"), response);
            }
            for(String target : new String[]{"//evil.example/", "/bad\\path", "/%0d%0aX-Injected:%20yes"}) {
                String response = site.plain(request("GET", target, ""));
                assertFalse(response.startsWith("HTTP/1.1 200"));
                assertFalse(response.contains("\r\nX-Injected:")); assertFalse(response.contains("encrypted map content"));
            }
            assertEquals(0, mapCalls.get());
        }
    }
    @Test void disablingRedirectRetainsTlsOnlyAndStillServesHttps() throws Exception {
        try(var site = new Site(8123, false)) {
            try {
                String response = site.plain(request("GET", "/", ""));
                assertFalse(response.contains("308")); assertFalse(response.contains("encrypted map content"));
            } catch(java.net.SocketException closed) { /* A TLS-only connector may reset a plaintext connection. */ }
            assertEquals(0, mapCalls.get());
            assertTrue(site.tls("TLSv1.3", request("GET", "/", "")).startsWith("HTTP/1.1 200"));
        }
    }
    @Test void tlsHostnameChecksRemainEnabled() throws Exception {
        try(var site = new Site(8123, true)) {
            String response = site.tls("TLSv1.3", "GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 400"), response); assertEquals(0, mapCalls.get());
        }
    }
}
