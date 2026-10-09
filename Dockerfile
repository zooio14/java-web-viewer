# The Playwright library in pom.xml and browser image must have the same version.
FROM maven:3.10.0-eclipse-temurin-21-noble AS build
WORKDIR /build
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress verify

# Contains the matching Chromium, its system libraries and the Playwright driver.
# This official development image uses JDK 25; the application targets Java 17.
FROM mcr.microsoft.com/playwright/java:v1.63.0-noble AS runtime
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends tini \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o pwuser -g pwuser /app
WORKDIR /app
COPY --from=build --chown=pwuser:pwuser /build/target/java-web-viewer-0.0.1-SNAPSHOT.jar /app/app.jar
ENV HOME=/home/pwuser \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError" \
    VIEWER_CHROMIUM_SANDBOX=true
USER pwuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --timeout=4 --output-document=/dev/null "http://127.0.0.1:${PORT:-8080}/health" || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/app/app.jar"]
