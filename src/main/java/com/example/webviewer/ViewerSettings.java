package com.example.webviewer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ViewerSettings {
    public final String accessToken;
    public final int width = 1280;
    public final int height = 720;
    public final int maxSessions;
    public final int idleTimeoutSeconds;
    public final int sessionMaxSeconds;
    public final int fps;
    public final int jpegQuality;
    public final boolean chromiumSandbox;

    public ViewerSettings(@Value("${viewer.access-token:}") String accessToken,
            @Value("${viewer.max-sessions:2}") int maxSessions,
            @Value("${viewer.idle-timeout-seconds:300}") int idleTimeoutSeconds,
            @Value("${viewer.session-max-seconds:1800}") int sessionMaxSeconds,
            @Value("${viewer.fps:8}") int fps,
            @Value("${viewer.jpeg-quality:70}") int jpegQuality,
            @Value("${viewer.chromium-sandbox:true}") boolean chromiumSandbox) {
        this.accessToken = accessToken;
        this.maxSessions = bounded(maxSessions, 1, 8, "max-sessions");
        this.idleTimeoutSeconds = bounded(idleTimeoutSeconds, 30, 3600, "idle-timeout-seconds");
        this.sessionMaxSeconds = bounded(sessionMaxSeconds, 60, 7200, "session-max-seconds");
        this.fps = bounded(fps, 1, 15, "fps");
        this.jpegQuality = bounded(jpegQuality, 40, 90, "jpeg-quality");
        this.chromiumSandbox = chromiumSandbox;
    }

    private static int bounded(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("viewer." + name + " fora do limite.");
        return value;
    }

    public boolean configured() { return accessToken.length() >= 32; }
}
