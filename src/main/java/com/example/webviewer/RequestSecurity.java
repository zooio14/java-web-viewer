package com.example.webviewer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

@Component
public class RequestSecurity extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // Explicit same-origin WS source supports browsers that do not map 'self' to ws/wss.
        String websocketSource = "";
        try {
            websocketSource = " " + new URI(request.isSecure() ? "wss" : "ws", null,
                    request.getServerName(), request.getServerPort(), null, null, null).toASCIIString();
        } catch (java.net.URISyntaxException ignored) { }
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'"
                + websocketSource + "; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        response.setHeader("Cache-Control", "no-store");
        // Guard mutations regardless of path decoding or matrix parameters used by MVC routing.
        if ("POST".equals(request.getMethod())) {
            if (!sameOrigin(request)) {
                response.sendError(403, "Origem não autorizada.");
                return;
            }
            if (request.getContentLengthLong() > 8192) {
                response.sendError(413);
                return;
            }
            // Reject streaming bodies before MVC buffers them; this small API never needs them.
            if ("POST".equals(request.getMethod()) && request.getContentLengthLong() < 0) {
                response.sendError(411);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public static boolean sameOrigin(HttpServletRequest request) {
        try {
            String value = request.getHeader("Origin");
            if (value == null || value.equals("null")) return false;
            URI origin = URI.create(value);
            int port = origin.getPort() < 0 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
            return origin.getRawUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && request.getScheme().equals(origin.getScheme())
                    && request.getServerName().equalsIgnoreCase(origin.getHost()) && request.getServerPort() == port;
        } catch (IllegalArgumentException ex) { return false; }
    }
}
