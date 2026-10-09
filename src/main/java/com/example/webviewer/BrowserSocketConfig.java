package com.example.webviewer;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
public class BrowserSocketConfig implements WebSocketConfigurer {
    private final BrowserManager browsers;
    private final SessionAuth auth;
    public BrowserSocketConfig(BrowserManager browsers, SessionAuth auth) {
        this.browsers = browsers;
        this.auth = auth;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.setTextMessageSizeLimit(8192);
                session.setBinaryMessageSizeLimit(1);
                browsers.open(session, (String) session.getAttributes().get("grant"));
            }
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) { browsers.receive(session, message.getPayload()); }
            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (java.io.IOException ignored) { }
            }
            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { browsers.close(session); }
            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) { browsers.close(session); }
        }, "/ws/browser").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                    Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet) || !RequestSecurity.sameOrigin(servlet.getServletRequest())) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return false;
                }
                String id = SessionAuth.cookie(servlet.getServletRequest());
                if (!auth.claim(id)) { response.setStatusCode(HttpStatus.UNAUTHORIZED); return false; }
                attributes.put("grant", id);
                return true;
            }
            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
                if (exception != null && request instanceof ServletServerHttpRequest servlet) auth.revoke(SessionAuth.cookie(servlet.getServletRequest()));
            }
        });
    }
}
