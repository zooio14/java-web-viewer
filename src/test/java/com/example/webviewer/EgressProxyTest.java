package com.example.webviewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EgressProxyTest {
    @Test
    void bindsLoopbackAndRequiresCredentialsBeforeLookingUpAnyDestination() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        try (SecurityPolicy policy = new SecurityPolicy("example.com", host -> {
            lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        }); EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
            connections.incrementAndGet();
            throw new IOException("Must not connect without authentication");
        })) {
            assertEquals("127.0.0.1", URI.create(proxy.endpoint()).getHost());
            assertTrue(proxy.isRunning());
            String result = exchange(proxy, "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n");
            assertTrue(result.startsWith("HTTP/1.1 407 "));
            assertTrue(result.contains("Proxy-Authenticate: Basic"));
            assertTrue(exchange(proxy, "GET http://example.com/ HTTP/1.1\r\n"
                    + "Proxy-Authorization: Basic d3Jvbmc6d3Jvbmc=\r\n\r\n").startsWith("HTTP/1.1 407 "));
            assertEquals(0, lookups.get());
            assertEquals(0, connections.get());
            assertTrue(proxy.username().length() >= 32);
            assertTrue(proxy.password().length() >= 32);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET http://localhost/ HTTP/1.1\r\n", "GET http://127.0.0.1/ HTTP/1.1\r\n",
            "GET http://evil.com/ HTTP/1.1\r\n", "GET https://example.com/ HTTP/1.1\r\n",
            "GET http://example.com:8080/ HTTP/1.1\r\n", "CONNECT example.com:80 HTTP/1.1\r\n",
            "GET http://example.com/#fragment HTTP/1.1\r\n",
            "CONNECT example.com:443/path HTTP/1.1\r\n", "CONNECT evil.com:443 HTTP/1.1\r\n",
            "CONNECT 127.0.0.1:443 HTTP/1.1\r\n", "CONNECT example.com:443 HTTP/1.1\r\nContent-Length: 1\r\n",
            "GET http://example.com/ HTTP/1.1\r\nTransfer-Encoding: chunked\r\n",
            "POST http://example.com/ HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 1\r\n",
            "POST http://example.com/ HTTP/1.1\r\nContent-Length: 1, 1\r\n",
            "POST http://example.com/ HTTP/1.1\r\nContent-Length: 1048577\r\n",
            "GET http://example.com/ HTTP/1.1\r\nUpgrade: websocket\r\n",
            "GET http://example.com/ HTTP/1.1\r\n Folded: invalid\r\n",
            "GET http://example.com/ HTTP/1.1\r\nBad Header: invalid\r\n",
            "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nHost: evil.com\r\n"
    })
    void blocksUnsafeDestinationsAndAmbiguousHttpFraming(String request) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (SecurityPolicy policy = publicPolicy();
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 connections.incrementAndGet();
                 throw new IOException("Rejected requests must never connect");
             })) {
            assertTrue(exchange(proxy, request + auth(proxy) + "\r\n").startsWith("HTTP/1.1 403 "));
            assertEquals(0, connections.get());
        }
    }

    @Test
    void rejectsMixedPrivateDnsBeforeOpeningSocket() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (SecurityPolicy policy = new SecurityPolicy("example.com", host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("169.254.169.254")});
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 connections.incrementAndGet();
                 throw new IOException("Private DNS must never connect");
             })) {
            String result = exchange(proxy, "CONNECT example.com:443 HTTP/1.1\r\n" + auth(proxy) + "\r\n");
            assertTrue(result.startsWith("HTTP/1.1 403 "));
            assertEquals(0, connections.get());
        }
    }

    @Test
    void boundsHeaderCountBeforeDnsOrConnection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (SecurityPolicy policy = publicPolicy();
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 connections.incrementAndGet();
                 throw new IOException("Oversized headers must never connect");
             })) {
            String request = "GET http://example.com/ HTTP/1.1\r\n" + auth(proxy)
                    + "X-Test: value\r\n".repeat(101) + "\r\n";
            assertTrue(exchange(proxy, request).startsWith("HTTP/1.1 403 "));
            assertEquals(0, connections.get());
        }
    }

    @Test
    void publicModeRequiresAuthenticationAndPinsPublicIpForDomainOutsideAllowlist() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        try (Upstream upstream = new Upstream();
             SecurityPolicy policy = new SecurityPolicy("example.com", "public", hostname -> {
                 assertEquals("example.net", hostname);
                 lookups.incrementAndGet();
                 return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
             });
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 assertEquals("8.8.8.8", address.getHostAddress());
                 assertEquals(80, port);
                 connections.incrementAndGet();
                 return upstream.connect();
             })) {
            assertEquals("127.0.0.1", URI.create(proxy.endpoint()).getHost());
            assertTrue(exchange(proxy, "GET http://example.net/ HTTP/1.1\r\nHost: example.net\r\n\r\n")
                    .startsWith("HTTP/1.1 407 "));
            assertEquals(0, lookups.get());
            assertEquals(0, connections.get());
            CompletableFuture<Observed> received = upstream.receiveHttp(0);
            assertTrue(exchange(proxy, "GET http://example.net/ HTTP/1.1\r\n" + auth(proxy) + "\r\n")
                    .startsWith("HTTP/1.1 200 "));
            assertTrue(received.get(5, TimeUnit.SECONDS).header().contains("Host: example.net\r\n"));
            assertEquals(1, lookups.get());
            assertEquals(1, connections.get());
        }
    }

    @Test
    void publicModeProxyStillRejectsPrivateDnsAndLiteralIpConnect() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (SecurityPolicy policy = new SecurityPolicy("example.com", "public", hostname ->
                new InetAddress[]{InetAddress.getByName("10.0.0.1")});
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 connections.incrementAndGet();
                 throw new IOException("Public mode must never connect to private addresses");
             })) {
            assertTrue(exchange(proxy, "CONNECT example.net:443 HTTP/1.1\r\n" + auth(proxy) + "\r\n")
                    .startsWith("HTTP/1.1 403 "));
            assertTrue(exchange(proxy, "CONNECT 8.8.8.8:443 HTTP/1.1\r\n" + auth(proxy) + "\r\n")
                    .startsWith("HTTP/1.1 403 "));
            assertEquals(0, connections.get());
        }
    }

    @Test
    void pinsResolvedAddressAndForwardsOneHttpRequestWithoutProxySecretsOrPipelining() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        try (Upstream upstream = new Upstream();
             SecurityPolicy policy = new SecurityPolicy("example.com", host -> new InetAddress[]{
                     InetAddress.getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "127.0.0.1")});
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 connections.incrementAndGet();
                 assertEquals("93.184.216.34", address.getHostAddress());
                 assertEquals(80, port);
                 return upstream.connect();
             })) {
            CompletableFuture<Observed> received = upstream.receiveHttp(4);
            String result = exchange(proxy, "POST http://example.com/a?b=1 HTTP/1.1\r\n"
                    + "Host: localhost\r\nContent-Length: 4\r\nConnection: keep-alive, X-Remove\r\n"
                    + "X-Remove: private-hop\r\n" + auth(proxy) + "\r\ndata"
                    + "GET http://169.254.169.254/ HTTP/1.1\r\nHost: 169.254.169.254\r\n\r\n");
            assertTrue(result.startsWith("HTTP/1.1 200 "));
            assertTrue(result.endsWith("OK"));
            Observed observed = received.get(5, TimeUnit.SECONDS);
            assertTrue(observed.header().startsWith("POST /a?b=1 HTTP/1.1\r\nHost: example.com\r\n"));
            assertTrue(observed.header().contains("Connection: close\r\n"));
            assertFalse(observed.header().toLowerCase().contains("proxy-authorization"));
            assertFalse(observed.header().contains(proxy.password()));
            assertFalse(observed.header().contains("X-Remove"));
            assertEquals("data", observed.body());
            assertFalse(observed.extraBytes(), "A pipelined second request must not reach the upstream");
            assertEquals(1, lookups.get(), "DNS is resolved once and the returned address is pinned");
            assertEquals(1, connections.get());
        }
    }

    @Test
    void tunnelsBidirectionallyOnlyAfterAuthorizedConnectToPublicPort443() throws Exception {
        try (Upstream upstream = new Upstream(); SecurityPolicy policy = publicPolicy();
             EgressProxy proxy = new EgressProxy(policy, (address, port, timeout) -> {
                 assertEquals("93.184.216.34", address.getHostAddress());
                 assertEquals(443, port);
                 return upstream.connect();
             }); Socket client = client(proxy)) {
            CompletableFuture<String> received = upstream.receiveTunnel();
            client.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n"
                    + auth(proxy) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", readHeader(client.getInputStream()));
            client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertEquals("PONG", new String(client.getInputStream().readNBytes(4), StandardCharsets.US_ASCII));
            assertEquals("PING", received.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closesListenerAndCredentialsDifferBetweenProxyInstances() throws Exception {
        try (SecurityPolicy policy = publicPolicy(); EgressProxy first = new EgressProxy(policy);
             EgressProxy second = new EgressProxy(policy)) {
            assertNotEquals(first.password(), second.password());
            URI endpoint = URI.create(first.endpoint());
            first.close();
            first.close();
            assertFalse(first.isRunning());
            try (Socket socket = new Socket()) {
                assertThrows(IOException.class, () -> socket.connect(new InetSocketAddress(
                        endpoint.getHost(), endpoint.getPort()), 1_000));
            }
            assertTrue(second.isRunning());
        }
    }

    private static SecurityPolicy publicPolicy() {
        return new SecurityPolicy("example.com", host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
    }

    private static String auth(EgressProxy proxy) {
        return "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(
                (proxy.username() + ":" + proxy.password()).getBytes(StandardCharsets.US_ASCII)) + "\r\n";
    }

    private static Socket client(EgressProxy proxy) throws IOException {
        URI endpoint = URI.create(proxy.endpoint());
        Socket socket = new Socket(endpoint.getHost(), endpoint.getPort());
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static String exchange(EgressProxy proxy, String request) throws IOException {
        try (Socket client = client(proxy)) {
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            String header = readHeader(client.getInputStream());
            Matcher length = Pattern.compile("(?im)^content-length: ([0-9]+)\\r?$").matcher(header);
            if (!length.find()) {
                throw new IOException("Test upstream must provide a Content-Length response");
            }
            // Closing a socket with an intentionally unread pipelined attack can generate RST.
            // The valid first response is complete at Content-Length, without awaiting that close.
            byte[] body = client.getInputStream().readNBytes(Integer.parseInt(length.group(1)));
            if (body.length != Integer.parseInt(length.group(1))) {
                throw new IOException("Truncated HTTP response body");
            }
            return header + new String(body, StandardCharsets.US_ASCII);
        }
    }

    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int count = 0;
        byte[] end = {13, 10, 13, 10};
        while (count != 4 && result.size() < 32 * 1024) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("Truncated HTTP header");
            }
            result.write(value);
            count = value == end[count] ? count + 1 : value == 13 ? 1 : 0;
        }
        return result.toString(StandardCharsets.US_ASCII);
    }

    private record Observed(String header, String body, boolean extraBytes) { }

    /** Local test upstream; the injected connector still receives only a vetted public address. */
    private static final class Upstream implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();

        Upstream() throws IOException { }

        Socket connect() throws IOException {
            return new Socket(server.getInetAddress(), server.getLocalPort());
        }

        CompletableFuture<Observed> receiveHttp(int bodyLength) {
            return CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    String header = readHeader(socket.getInputStream());
                    String body = new String(socket.getInputStream().readNBytes(bodyLength), StandardCharsets.US_ASCII);
                    socket.setSoTimeout(200);
                    boolean extra = false;
                    try {
                        extra = socket.getInputStream().read() != -1;
                    } catch (SocketTimeoutException expected) {
                        // A second request must not be forwarded on this connection.
                    }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return new Observed(header, body, extra);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }, worker);
        }

        CompletableFuture<String> receiveTunnel() {
            return CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    String received = new String(socket.getInputStream().readNBytes(4), StandardCharsets.US_ASCII);
                    socket.getOutputStream().write("PONG".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return received;
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }, worker);
        }

        @Override
        public void close() throws IOException {
            server.close();
            worker.shutdownNow();
        }
    }
}
