package com.example.webviewer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Short-lived server-side capabilities. The deployment token is never a URL or a cookie. */
@Component
public class SessionAuth {
    public static final String COOKIE = "viewer_session";
    private final ViewerSettings settings;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();

    public SessionAuth(ViewerSettings settings) { this.settings = settings; }

    public synchronized String issue(String token, String client) {
        cleanup();
        if (!settings.configured()) throw new AuthException(503, "Configure VIEWER_ACCESS_TOKEN com pelo menos 32 caracteres.");
        long now = System.nanoTime();
        Attempt attempt = attempts.computeIfAbsent(client, ignored -> new Attempt(now));
        if (now - attempt.started > Duration.ofMinutes(1).toNanos()) {
            attempt.started = now;
            attempt.count = 0;
        }
        if (++attempt.count > 20) throw new AuthException(429, "Muitas tentativas. Aguarde um minuto.");
        if (token == null || token.length() > 4096 || !MessageDigest.isEqual(
                settings.accessToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(401, "Token de acesso inválido.");
        }
        if (grants.size() >= settings.maxSessions * 4) throw new AuthException(429, "Limite de sessões. Encerre uma sessão ou aguarde sua expiração.");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(id, new Grant(now + Duration.ofSeconds(settings.sessionMaxSeconds).toNanos()));
        return id;
    }

    public synchronized boolean claim(String id) {
        cleanup();
        Grant grant = grants.get(id);
        if (grant == null || grant.claimed) return false;
        grant.claimed = true;
        return true;
    }

    public synchronized boolean valid(String id) {
        Grant grant = grants.get(id);
        return grant != null && System.nanoTime() < grant.expires;
    }

    public synchronized void onClose(String id, Runnable close) {
        Grant grant = grants.get(id);
        if (grant != null) grant.close = close;
        else close.run();
    }

    public synchronized void revoke(String id) {
        Grant grant = grants.remove(id);
        if (grant != null && grant.close != null) grant.close.run();
    }

    public static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private void cleanup() {
        long now = System.nanoTime();
        Iterator<Grant> iterator = grants.values().iterator();
        while (iterator.hasNext()) {
            Grant grant = iterator.next();
            if (now >= grant.expires) {
                iterator.remove();
                if (grant.close != null) grant.close.run();
            }
        }
        attempts.values().removeIf(attempt -> now - attempt.started > Duration.ofMinutes(2).toNanos());
        if (attempts.size() > 1024) attempts.clear();
    }

    private static class Grant {
        final long expires;
        boolean claimed;
        Runnable close;
        Grant(long expires) { this.expires = expires; }
    }
    private static class Attempt {
        long started;
        int count;
        Attempt(long started) { this.started = started; }
    }
    public static class AuthException extends RuntimeException {
        public final int status;
        AuthException(int status, String message) { super(message); this.status = status; }
    }
}
