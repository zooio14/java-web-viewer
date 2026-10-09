package com.example.webviewer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in integration tests use the shipped Chromium and controlled content, without bypassing production policy. */
@EnabledIfSystemProperty(named = "viewer.browser-tests", matches = "true")
class BrowserSmokeTest {
    private static final String TOKEN = "test-only-token-never-use-in-deployment-123456";

    @Test void realBrowserStreamsJpegAndReceivesMouseKeyboardWithGuardedEgress() throws Exception {
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        AtomicInteger upstreamConnections = new AtomicInteger();
        fixture.createContext("/fixture", exchange -> {
            String html = """
                <!doctype html><html><meta charset="utf-8"><style>
                body{background:white;margin:0}input{position:absolute;left:20px;top:20px;width:300px;height:50px}
                button{position:absolute;left:20px;top:100px;width:200px;height:50px}
                </style><input id="text"><button id="send">Enviar</button>
                <script>send.onclick=async()=>{document.body.style.background='#00aa55';
                  await fetch('/result?value='+encodeURIComponent(text.value));
                  await fetch('http://127.0.0.1/forbidden').catch(()=>{});
                };</script></html>
                """;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        fixture.createContext("/result", exchange -> {
            result.add(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        fixture.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://blocked.example.net/secret");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        fixture.start();
        try (SecurityPolicy policy = new SecurityPolicy("example.com", "public", hostname -> new InetAddress[]{
                    InetAddress.getByName(hostname.startsWith("blocked.") ? "127.0.0.1" : "93.184.215.14")});
                EgressProxy egress = new EgressProxy(policy, (address, port, timeout) -> {
                    assertEquals("93.184.215.14", address.getHostAddress()); assertEquals(80, port);
                    upstreamConnections.incrementAndGet();
                    // The test connector maps an already validated public address to a controlled server.
                    return new Socket("127.0.0.1", fixture.getAddress().getPort());
                })) {
            ViewerSettings settings = SessionAuthTest.settings(TOKEN);
            SessionAuth auth = new SessionAuth(settings);
            String grant = auth.issue(TOKEN, "test");
            WebSocketSession socket = mock(WebSocketSession.class);
            AtomicBoolean open = new AtomicBoolean(true);
            when(socket.getId()).thenReturn("smoke"); when(socket.isOpen()).thenAnswer(ignored -> open.get());
            BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
            BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
            AtomicReference<BrowserSession> ref = new AtomicReference<>();
            doAnswer(invocation -> {
                WebSocketMessage<?> message = invocation.getArgument(0);
                if (message instanceof TextMessage text) messages.add(JsonParser.parseString(text.getPayload()).getAsJsonObject());
                if (message instanceof BinaryMessage binary) {
                    byte[] bytes = new byte[binary.getPayload().remaining()]; binary.getPayload().get(bytes); frames.add(bytes);
                    ref.get().receive("{\"type\":\"frameAck\"}");
                }
                return null;
            }).when(socket).sendMessage(any());
            doAnswer(ignored -> { open.set(false); return null; }).when(socket).close(any());
            CountDownLatch closed = new CountDownLatch(1);
            BrowserSession session = new BrowserSession(socket, grant, settings, policy, auth, egress, closed::countDown);
            ref.set(session); session.start();
            try {
                await(messages, "ready");
                byte[] first = frames.poll(10, TimeUnit.SECONDS); assertNotNull(first, "Initial JPEG missing");
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(first));
                assertNotNull(image); assertEquals(1280, image.getWidth()); assertEquals(720, image.getHeight());
                session.receive("{\"type\":\"navigate\",\"url\":\"http://example.net/fixture\"}");
                awaitLocation(messages, "http://example.net/fixture");
                assertNotNull(frames.poll(10, TimeUnit.SECONDS));
                pointer(session, "down", 50, 45); pointer(session, "up", 50, 45);
                session.receive("{\"type\":\"text\",\"text\":\"Teste remoto\"}");
                session.receive("{\"type\":\"key\",\"action\":\"down\",\"key\":\"Backspace\"}");
                session.receive("{\"type\":\"key\",\"action\":\"up\",\"key\":\"Backspace\"}");
                session.receive("{\"type\":\"text\",\"text\":\"o\"}");
                pointer(session, "down", 60, 120); pointer(session, "up", 60, 120);
                assertEquals("value=Teste%20remoto", result.poll(10, TimeUnit.SECONDS));
                await(messages, "error"); // The page's attempt to fetch localhost was blocked.
                frames.clear();
                session.receive("{\"type\":\"wheel\",\"deltaX\":0,\"deltaY\":100}");
                session.receive("{\"type\":\"navigate\",\"url\":\"http://127.0.0.1/\"}");
                assertTrue(await(messages, "error").get("message").getAsString().length() > 0);
                session.receive("{\"type\":\"navigate\",\"url\":\"http://example.com/redirect\"}");
                awaitLocation(messages, "http://blocked.example.net/secret");
                assertTrue(upstreamConnections.get() >= 3);
                session.receive("{\"type\":\"home\"}");
                awaitLocation(messages, "about:blank");
                session.receive("{\"type\":\"pointer\",\"action\":\"down\",\"x\":-1,\"y\":0,\"button\":0}");
                assertTrue(closed.await(10, TimeUnit.SECONDS), "Invalid input must close session");
                assertFalse(auth.valid(grant));
            } finally { session.stop(); session.await(18000); }
        } finally { fixture.stop(0); }
    }

    @Test void realHttpLoginWebsocketOriginCookieLogoutAndHealth() throws Exception {
        SpringApplication application = new SpringApplication(WebViewerApplication.class);
        try (ConfigurableApplicationContext context = application.run("--server.port=0", "--viewer.access-token=" + TOKEN,
                "--viewer.destination-mode=public", "--spring.main.banner-mode=off")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String origin = "http://localhost:" + port;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(origin + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, post(client, origin, "/api/session", "{\"token\":\"" + TOKEN + "\"}", "http://attacker.example.com", null).statusCode());
            assertEquals(403, post(client, origin, "/api/session;v=1", "{\"token\":\"" + TOKEN + "\"}", "http://attacker.example.com", null).statusCode());
            assertEquals(403, post(client, origin, "/api/%73ession", "{\"token\":\"" + TOKEN + "\"}", "http://attacker.example.com", null).statusCode());
            assertEquals(413, post(client, origin, "/api/session;v=1", "x".repeat(9000), origin, null).statusCode());
            assertEquals(401, post(client, origin, "/api/session", "{\"token\":\"wrong\"}", origin, null).statusCode());
            HttpResponse<String> login = post(client, origin, "/api/session", "{\"token\":\"" + TOKEN + "\"}", origin, null);
            assertEquals(200, login.statusCode());
            String setCookie = login.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(setCookie.contains("HttpOnly")); assertTrue(setCookie.contains("SameSite=Strict")); assertFalse(setCookie.contains(TOKEN));
            String cookie = setCookie.split(";", 2)[0];
            URI socketUrl = URI.create("ws://localhost:" + port + "/ws/browser");
            assertThrows(CompletionException.class, () -> client.newWebSocketBuilder().header("Origin", origin).buildAsync(socketUrl, new SocketEvents()).join());
            assertThrows(CompletionException.class, () -> client.newWebSocketBuilder().header("Origin", "http://attacker.example.com").header("Cookie", cookie).buildAsync(socketUrl, new SocketEvents()).join());
            SocketEvents listener = new SocketEvents();
            java.net.http.WebSocket ws = client.newWebSocketBuilder().header("Origin", origin).header("Cookie", cookie).buildAsync(socketUrl, listener).get(10, TimeUnit.SECONDS);
            assertNotNull(listener.frames.poll(15, TimeUnit.SECONDS));
            assertThrows(CompletionException.class, () -> client.newWebSocketBuilder().header("Origin", origin).header("Cookie", cookie).buildAsync(socketUrl, new SocketEvents()).join());
            assertEquals(410, client.send(HttpRequest.newBuilder(URI.create(origin + "/api/view?url=http://localhost")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, post(client, origin, "/api/logout", "", origin, cookie).statusCode());
            assertTrue(listener.closed.await(10, TimeUnit.SECONDS));
            ws.abort();
            // Exercise the delivered frontend, including its CSP, cookie login, WebSocket and canvas.
            try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(BrowserManager.driverEnvironment()));
                    Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                            .setChannel("chromium").setChromiumSandbox(SessionAuthTest.settings(TOKEN).chromiumSandbox));
                    BrowserContext frontend = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 900))) {
                Page page = frontend.newPage();
                List<String> errors = new java.util.ArrayList<>();
                page.onPageError(errors::add);
                page.navigate(origin);
                page.locator("#token").fill(TOKEN);
                page.locator("#connect").click();
                page.locator("#screen").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(20000));
                assertEquals("Sessão conectada", page.locator("#connection-label").textContent());
                assertEquals("Sites públicos habilitados", page.locator("#domains-label").textContent());
                assertEquals(1280, ((Number) page.locator("#screen").evaluate("canvas => canvas.width")).intValue());
                page.locator("#address").fill("http://127.0.0.1/");
                page.locator("#address-form button").click();
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#status"))
                        .hasClass(java.util.regex.Pattern.compile(".*error.*"));
                page.setViewportSize(390, 844);
                assertTrue((Boolean) page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"), "Mobile overflow");
                page.locator("#disconnect").click();
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#connection-label"))
                        .hasText("Desconectado");
                assertFalse(page.locator("#screen").isVisible());
                assertTrue(errors.isEmpty(), errors.toString());
            }
        }
    }

    private static HttpResponse<String> post(HttpClient client, String origin, String path, String body, String sender, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path)).header("Content-Type", "application/json")
                .header("Origin", sender).POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void pointer(BrowserSession session, String action, int x, int y) {
        session.receive("{\"type\":\"pointer\",\"action\":\"" + action + "\",\"x\":" + x + ",\"y\":" + y + ",\"button\":0}");
    }
    private static JsonObject await(BlockingQueue<JsonObject> messages, String type) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            JsonObject message = messages.poll(1, TimeUnit.SECONDS);
            if (message != null && type.equals(message.get("type").getAsString())) return message;
        }
        fail("Message missing: " + type); return null;
    }
    private static void awaitLocation(BlockingQueue<JsonObject> messages, String url) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            JsonObject message = messages.poll(1, TimeUnit.SECONDS);
            if (message != null && "location".equals(message.get("type").getAsString()) && url.equals(message.get("url").getAsString())) return;
        }
        fail("Location missing: " + url);
    }
    private static class SocketEvents implements java.net.http.WebSocket.Listener {
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onBinary(java.net.http.WebSocket socket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); fragments.writeBytes(bytes);
            if (last) { frames.add(fragments.toByteArray()); fragments.reset(); socket.sendText("{\"type\":\"frameAck\"}", true); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onText(java.net.http.WebSocket socket, CharSequence text, boolean last) { socket.request(1); return null; }
        @Override public CompletionStage<?> onClose(java.net.http.WebSocket socket, int code, String reason) { closed.countDown(); return null; }
        @Override public void onError(java.net.http.WebSocket socket, Throwable error) { closed.countDown(); }
    }
}
