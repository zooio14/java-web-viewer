package com.example.webviewer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthTest {
    private static final String TOKEN = "test-token-with-at-least-thirty-two-characters";
    static ViewerSettings settings(String token) {
        return new ViewerSettings(token, 2, 300, 1800, 8, 70,
                !"false".equals(System.getenv("VIEWER_CHROMIUM_SANDBOX")));
    }

    @Test void unconfiguredAndWrongTokensFailClosed() {
        SessionAuth missing = new SessionAuth(settings(""));
        assertEquals(503, assertThrows(SessionAuth.AuthException.class, () -> missing.issue(TOKEN, "client")).status);
        SessionAuth auth = new SessionAuth(settings(TOKEN));
        assertEquals(401, assertThrows(SessionAuth.AuthException.class, () -> auth.issue("wrong", "client")).status);
        assertFalse(auth.claim("invented"));
    }

    @Test void grantIsRandomSingleUseAndRevocable() {
        SessionAuth auth = new SessionAuth(settings(TOKEN));
        String first = auth.issue(TOKEN, "client");
        String second = auth.issue(TOKEN, "client");
        assertNotEquals(first, second);
        assertNotEquals(first, TOKEN);
        assertTrue(auth.valid(first));
        assertTrue(auth.claim(first));
        assertFalse(auth.claim(first));
        boolean[] closed = {false};
        auth.onClose(first, () -> closed[0] = true);
        auth.revoke(first);
        assertFalse(auth.valid(first));
        assertTrue(closed[0]);
        assertTrue(auth.valid(second));
    }

    @Test void wrongTokensAreRateLimited() {
        SessionAuth auth = new SessionAuth(settings(TOKEN));
        for (int i = 0; i < 20; i++) {
            assertEquals(401, assertThrows(SessionAuth.AuthException.class, () -> auth.issue("wrong", "client")).status);
        }
        assertEquals(429, assertThrows(SessionAuth.AuthException.class, () -> auth.issue("wrong", "client")).status);
    }

    @Test void originRequiresMatchingSchemeHostAndPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https"); request.setServerName("viewer.example.com"); request.setServerPort(443);
        assertFalse(RequestSecurity.sameOrigin(request));
        request.addHeader("Origin", "https://viewer.example.com");
        assertTrue(RequestSecurity.sameOrigin(request));
        for (String origin : new String[]{"null", "http://viewer.example.com", "https://attacker.example.com",
                "https://viewer.example.com:8443", "https://viewer.example.com/path", "https://user@viewer.example.com", "https://viewer.example.com?x=1"}) {
            request.removeHeader("Origin"); request.addHeader("Origin", origin);
            assertFalse(RequestSecurity.sameOrigin(request), origin);
        }
    }

    @Test void cspAllowsOnlyTheViewerWebsocketOriginWithoutWeakeningScripts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setScheme("https"); request.setSecure(true);
        request.setServerName("viewer.example.com"); request.setServerPort(443);
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        new RequestSecurity().doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        String csp = response.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("connect-src 'self' wss://viewer.example.com:443;"));
        assertTrue(csp.contains("script-src 'self';"));
        assertFalse(csp.contains("unsafe-eval"));
        assertFalse(csp.contains("unsafe-inline"));
    }
}
