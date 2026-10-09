package com.example.webviewer;

import com.google.gson.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Every Playwright call and event runs on this session's one owning thread. */
final class BrowserSession implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);
    private static final Gson JSON = new Gson();
    private static final Set<String> KEYS = Set.of("Control", "Alt", "Shift", "Meta", "Enter", "Tab", "Backspace",
            "Escape", "Delete", "Insert", "Home", "End", "PageUp", "PageDown", "ArrowUp", "ArrowDown", "ArrowLeft",
            "ArrowRight", "CapsLock", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12");
    private final WebSocketSession socket;
    private final String grant;
    private final ViewerSettings settings;
    private final SecurityPolicy policy;
    private final SessionAuth auth;
    private final EgressProxy egress;
    private final Runnable finished;
    private final ArrayBlockingQueue<JsonObject> commands = new ArrayBlockingQueue<>(128);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean frameOutstanding = new AtomicBoolean();
    private final AtomicReference<String> latestFrame = new AtomicReference<>();
    private final Thread thread;
    private volatile long lastActivity = System.nanoTime();
    private volatile long lastFrame;
    private long frameSent;
    private long lastPing;
    private long lastBlock;
    private long rateWindow = System.nanoTime();
    private int rateCount;

    BrowserSession(WebSocketSession socket, String grant, ViewerSettings settings, SecurityPolicy policy,
            SessionAuth auth, EgressProxy egress, Runnable finished) {
        this.socket = socket;
        this.grant = grant;
        this.settings = settings;
        this.policy = policy;
        this.auth = auth;
        this.egress = egress;
        this.finished = finished;
        this.thread = new Thread(this, "viewer-browser-" + socket.getId());
        thread.setDaemon(true);
    }

    void start() { thread.start(); }
    void stop() { stopped.set(true); commands.clear(); }
    void await(long millis) {
        try { thread.join(millis); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    synchronized void receive(String payload) {
        if (stopped.get()) return;
        try {
            long now = System.nanoTime();
            if (now - rateWindow > Duration.ofSeconds(1).toNanos()) { rateWindow = now; rateCount = 0; }
            if (++rateCount > 200 || payload.length() > 8192) throw new IllegalArgumentException();
            JsonObject command = JsonParser.parseString(payload).getAsJsonObject();
            String type = string(command, "type", 24);
            if (type.equals("frameAck")) { frameOutstanding.set(false); return; }
            if (type.equals("pong")) return;
            switch (type) {
                case "navigate" -> string(command, "url", 4096);
                case "back", "forward", "reload", "home" -> { }
                case "pointer" -> {
                    String action = string(command, "action", 8);
                    if (!Set.of("move", "down", "up").contains(action)) throw new IllegalArgumentException();
                    number(command, "x", 0, settings.width - 1);
                    number(command, "y", 0, settings.height - 1);
                    int button = integer(command, "button", 0, 2);
                }
                case "wheel" -> { number(command, "deltaX", -2000, 2000); number(command, "deltaY", -2000, 2000); }
                case "key" -> {
                    if (!Set.of("down", "up").contains(string(command, "action", 4))) throw new IllegalArgumentException();
                    String key = string(command, "key", 20);
                    if (!KEYS.contains(key) && (key.codePointCount(0, key.length()) != 1 || key.codePointAt(0) < 32)) {
                        throw new IllegalArgumentException();
                    }
                }
                case "text" -> string(command, "text", 1024);
                default -> throw new IllegalArgumentException();
            }
            if (!commands.offer(command)) throw new IllegalArgumentException();
            lastActivity = now;
        } catch (RuntimeException ex) { stop(); }
    }

    @Override
    public void run() {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(BrowserManager.driverEnvironment()));
                Browser browser = launch(playwright, settings, egress);
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(settings.width, settings.height).setDeviceScaleFactor(1)
                        .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            configure(context, policy, this::blocked);
            Page page = context.newPage();
            context.onPage(popup -> { if (popup != page) popup.close(); });
            page.onDialog(Dialog::dismiss);
            page.onDownload(Download::cancel);
            page.onFileChooser(chooser -> text("status", "Envio de arquivos desativado neste protótipo."));
            page.onFrameNavigated(frame -> {
                if (frame == page.mainFrame()) location(page);
            });
            page.setDefaultTimeout(5000);
            page.setDefaultNavigationTimeout(15000);
            home(page);
            CDPSession cdp = context.newCDPSession(page);
            cdp.on("Page.screencastFrame", event -> {
                JsonObject ack = new JsonObject();
                ack.add("sessionId", event.get("sessionId"));
                cdp.send("Page.screencastFrameAck", ack);
                String data = event.get("data").getAsString();
                if (data.length() > 2_000_000) { stop(); return; }
                // Keep the newest image while the client decodes its previous frame.
                latestFrame.set(data);
            });
            text(Map.of("type", "ready", "width", settings.width, "height", settings.height,
                    "allowedDomains", policy.getAllowedDomains(), "destinationMode", policy.getDestinationMode()));
            JsonObject stream = new JsonObject();
            stream.addProperty("format", "jpeg");
            stream.addProperty("quality", settings.jpegQuality);
            stream.addProperty("maxWidth", settings.width);
            stream.addProperty("maxHeight", settings.height);
            cdp.send("Page.startScreencast", stream);
            lastPing = System.nanoTime();
            while (!stopped.get() && socket.isOpen() && auth.valid(grant)) {
                long now = System.nanoTime();
                if (now - lastActivity > Duration.ofSeconds(settings.idleTimeoutSeconds).toNanos()) {
                    text("status", "Sessão encerrada por inatividade.");
                    break;
                }
                if (frameOutstanding.get() && now - frameSent > Duration.ofSeconds(15).toNanos()) break;
                if (!frameOutstanding.get() && now - lastFrame >= 1_000_000_000L / settings.fps) {
                    String encoded = latestFrame.getAndSet(null);
                    if (encoded != null) {
                        byte[] data = Base64.getDecoder().decode(encoded);
                        frameOutstanding.set(true);
                        frameSent = now;
                        socket.sendMessage(new BinaryMessage(data));
                        lastFrame = now;
                    }
                }
                if (now - lastPing > Duration.ofSeconds(15).toNanos()) {
                    text(Map.of("type", "ping"));
                    lastPing = now;
                }
                for (int i = 0; i < 20; i++) {
                    JsonObject command = commands.poll();
                    if (command == null || stopped.get() || !auth.valid(grant)
                            || System.nanoTime() - lastActivity > Duration.ofSeconds(settings.idleTimeoutSeconds).toNanos()
                            || frameOutstanding.get() && System.nanoTime() - frameSent > Duration.ofSeconds(15).toNanos()) break;
                    try { execute(page, command); }
                    catch (IllegalArgumentException ex) { text("error", ex.getMessage()); }
                    catch (PlaywrightException ex) { text("error", "A página não respondeu ou a conexão foi recusada."); }
                }
                // Pumps network, dialogs and screencast callbacks on Playwright's owning thread.
                page.waitForTimeout(20);
            }
        } catch (RuntimeException | IOException ex) {
            log.warn("Sessão remota encerrada por falha do navegador ({}).", ex.getClass().getSimpleName());
            text("error", "Não foi possível iniciar ou manter Chromium. Verifique instalação, recursos e sandbox no servidor.");
        } finally {
            stopped.set(true);
            try { if (socket.isOpen()) socket.close(CloseStatus.NORMAL); } catch (IOException ignored) { }
            auth.revoke(grant);
            finished.run();
        }
    }

    static Browser launch(Playwright playwright, ViewerSettings settings, EgressProxy egress) {
        return playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setChannel("chromium")
                .setChromiumSandbox(settings.chromiumSandbox).setTimeout(30000)
                .setProxy(new Proxy(egress.endpoint()).setUsername(egress.username()).setPassword(egress.password())
                        .setBypass("<-loopback>"))
                .setArgs(List.of("--disable-dev-shm-usage", "--disable-quic", "--disable-http2",
                        "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1",
                        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp")));
    }

    static void configure(BrowserContext context, SecurityPolicy policy, java.util.function.Consumer<String> blocked) {
        context.route("**/*", route -> {
            try {
                policy.validateRequest(route.request().url());
                // The gateway also vets new HTTP requests/CONNECTs, including redirect destinations.
                route.resume();
            } catch (IllegalArgumentException ex) {
                route.abort("blockedbyclient");
                blocked.accept(ex.getMessage());
            }
        });
        // No connectToServer call: upstream WebSockets cannot escape the HTTP policy.
        context.routeWebSocket("**/*", route -> route.close());
        context.addInitScript("""
            for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'WebTransport']) {
              Object.defineProperty(globalThis, name, {value: undefined, configurable: false});
            }
            """);
    }

    private void execute(Page page, JsonObject command) {
        switch (command.get("type").getAsString()) {
            case "navigate" -> {
                String url = policy.validate(command.get("url").getAsString()).toString();
                text("status", "Abrindo página…");
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                location(page);
            }
            case "back" -> { page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); location(page); }
            case "forward" -> { page.goForward(new Page.GoForwardOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); location(page); }
            case "reload" -> { page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); location(page); }
            case "home" -> home(page);
            case "pointer" -> {
                page.mouse().move(command.get("x").getAsDouble(), command.get("y").getAsDouble());
                MouseButton button = switch (command.get("button").getAsInt()) {
                    case 1 -> MouseButton.MIDDLE;
                    case 2 -> MouseButton.RIGHT;
                    default -> MouseButton.LEFT;
                };
                switch (command.get("action").getAsString()) {
                    case "down" -> page.mouse().down(new Mouse.DownOptions().setButton(button));
                    case "up" -> page.mouse().up(new Mouse.UpOptions().setButton(button));
                    default -> { }
                }
            }
            case "wheel" -> page.mouse().wheel(command.get("deltaX").getAsDouble(), command.get("deltaY").getAsDouble());
            case "key" -> {
                String key = command.get("key").getAsString();
                if (command.get("action").getAsString().equals("down")) page.keyboard().down(key);
                else page.keyboard().up(key);
            }
            case "text" -> page.keyboard().insertText(command.get("text").getAsString());
            default -> { }
        }
    }

    private void home(Page page) {
        page.navigate("about:blank");
        String description = policy.getDestinationMode().equals("public")
                ? "Digite o endereço de um site público na barra acima."
                : "Digite um domínio autorizado na barra de endereço.";
        page.setContent("""
            <!doctype html><html lang="pt-BR"><meta charset="utf-8">
            <style>body{margin:0;background:#101a2d;color:#edf2ff;font:20px system-ui;display:grid;place-content:center;height:100vh;text-align:center}
            p{color:#a8bad2;font-size:17px}</style><h1>Navegador remoto</h1><p>%s</p></html>
            """.formatted(description));
        location(page);
    }

    private void location(Page page) {
        // Reading the title can re-enter event dispatch; use URL only in navigation callbacks.
        text(Map.of("type", "location", "url", page.url(), "title", "Navegador remoto"));
    }
    private void blocked(String message) {
        long now = System.nanoTime();
        if (now - lastBlock > Duration.ofSeconds(1).toNanos()) {
            text("error", message);
            lastBlock = now;
        }
    }
    private void text(String type, String message) { text(Map.of("type", type, "message", message)); }
    private void text(Map<String, ?> message) {
        try { if (socket.isOpen()) socket.sendMessage(new TextMessage(JSON.toJson(message))); }
        catch (IOException | RuntimeException ex) { stop(); }
    }
    private static String string(JsonObject object, String name, int max) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        String result = value.getAsString();
        if (result.isEmpty() || result.length() > max) throw new IllegalArgumentException();
        return result;
    }
    private static double number(JsonObject object, String name, double min, double max) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result < min || result > max) throw new IllegalArgumentException();
        return result;
    }
    private static int integer(JsonObject object, String name, int min, int max) {
        double value = number(object, name, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException();
        return (int) value;
    }
}
