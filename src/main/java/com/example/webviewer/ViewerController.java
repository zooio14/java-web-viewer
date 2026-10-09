package com.example.webviewer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class ViewerController {
    private final SessionAuth auth;
    private final ViewerSettings settings;
    private final SecurityPolicy policy;
    private final BrowserManager browsers;

    public ViewerController(SessionAuth auth, ViewerSettings settings, SecurityPolicy policy, BrowserManager browsers) {
        this.auth = auth;
        this.settings = settings;
        this.policy = policy;
        this.browsers = browsers;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ready = browsers.ready();
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "UP" : "DOWN"));
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of("width", settings.width, "height", settings.height, "allowedDomains", policy.getAllowedDomains(),
                "destinationMode", policy.getDestinationMode());
    }

    @PostMapping("/api/session")
    public ResponseEntity<Map<String, Object>> session(@RequestBody AccessRequest body, HttpServletRequest request) {
        if (!browsers.ready()) return ResponseEntity.status(503).body(Map.of("message", "Chromium indisponível. Verifique a instalação no servidor."));
        String previous = SessionAuth.cookie(request);
        String id = auth.issue(body.token(), request.getRemoteAddr());
        auth.revoke(previous);
        return ResponseEntity.ok().header("Set-Cookie", cookie(id, request.isSecure(), settings.sessionMaxSeconds))
                .body(Map.of("width", settings.width, "height", settings.height,
                        "allowedDomains", policy.getAllowedDomains(), "destinationMode", policy.getDestinationMode(),
                        "idleTimeoutSeconds", settings.idleTimeoutSeconds));
    }

    @PostMapping("/api/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        auth.revoke(SessionAuth.cookie(request));
        return ResponseEntity.ok().header("Set-Cookie", cookie("", request.isSecure(), 0)).body(Map.of("status", "closed"));
    }

    @GetMapping("/api/view")
    public ResponseEntity<Map<String, Object>> legacyView() {
        return ResponseEntity.status(410).body(Map.of("message", "O visualizador foi substituído pela sessão remota autenticada na página inicial."));
    }

    @ExceptionHandler(SessionAuth.AuthException.class)
    public ResponseEntity<Map<String, Object>> authError(SessionAuth.AuthException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
    }

    private static String cookie(String value, boolean secure, int maxAge) {
        return ResponseCookie.from(SessionAuth.COOKIE, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(maxAge).build().toString();
    }
    public record AccessRequest(String token) {}
}
