package com.example.webviewer;

import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BrowserManager {
    private static final Logger log = LoggerFactory.getLogger(BrowserManager.class);
    private final ViewerSettings settings;
    private final SecurityPolicy policy;
    private final SessionAuth auth;
    private final EgressProxy egress;
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final boolean runtimeAvailable;
    private volatile boolean stopping;

    public BrowserManager(ViewerSettings settings, SecurityPolicy policy, SessionAuth auth) throws IOException {
        this.settings = settings;
        this.policy = policy;
        this.auth = auth;
        this.egress = new EgressProxy(policy);
        boolean available = false;
        // Check the installed version without downloads or a long-lived browser at startup.
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(driverEnvironment()))) {
            available = Files.isExecutable(Path.of(playwright.chromium().executablePath()));
        } catch (RuntimeException ex) {
            log.warn("Playwright indisponível. Instale Chromium com a CLI da versão declarada no pom.xml.");
        }
        runtimeAvailable = available;
        if (!settings.configured()) log.warn("Acesso remoto fechado: configure VIEWER_ACCESS_TOKEN com pelo menos 32 caracteres.");
    }

    static Map<String, String> driverEnvironment() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return environment;
    }

    public boolean ready() { return !stopping && runtimeAvailable && egress.isRunning(); }

    public synchronized void open(WebSocketSession socket, String grant) throws IOException {
        if (!ready() || sessions.size() >= settings.maxSessions) {
            auth.revoke(grant);
            socket.close(new CloseStatus(1013, "Capacidade indisponível. Tente novamente."));
            return;
        }
        // Tomcat's basic sender has a finite timeout as well as Spring's bounded buffer.
        if (socket instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession standard) {
            standard.getNativeSession().getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5000L);
        }
        WebSocketSession outgoing = new ConcurrentWebSocketSessionDecorator(socket, 5000, 2_000_000);
        BrowserSession session = new BrowserSession(outgoing, grant, settings, policy, auth, egress,
                () -> sessions.remove(socket.getId()));
        sessions.put(socket.getId(), session);
        auth.onClose(grant, session::stop);
        session.start();
    }

    public void receive(WebSocketSession socket, String payload) {
        BrowserSession session = sessions.get(socket.getId());
        if (session != null) session.receive(payload);
    }

    public void close(WebSocketSession socket) {
        BrowserSession session = sessions.get(socket.getId());
        if (session != null) session.stop();
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        for (BrowserSession session : sessions.values()) session.stop();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(18).toNanos();
        for (BrowserSession session : sessions.values()) {
            long remaining = Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
            if (remaining > 0) session.await(remaining);
        }
        egress.close();
    }
}
