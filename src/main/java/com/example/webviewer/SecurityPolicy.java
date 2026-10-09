package com.example.webviewer;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The same destination policy is applied to navigation, browser requests and actual egress. */
@Component
public class SecurityPolicy implements AutoCloseable {
    private static final int MAX_URL_LENGTH = 8192;
    private final Set<String> allowedDomains;
    private final Resolver resolver;
    private final ThreadPoolExecutor dnsWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "viewer-dns");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public SecurityPolicy(@Value("${viewer.allowed-domains:wikipedia.org,example.com,developer.mozilla.org}")
                          String configuredDomains) {
        this(configuredDomains, InetAddress::getAllByName);
    }

    SecurityPolicy(String configuredDomains, Resolver resolver) {
        this.resolver = resolver;
        Set<String> domains = new LinkedHashSet<>();
        for (String value : (configuredDomains == null ? "" : configuredDomains).split(",", -1)) {
            domains.add(requireDomain(value.trim().toLowerCase(Locale.ROOT)));
        }
        if (domains.isEmpty()) {
            throw new IllegalArgumentException("Configure ao menos um domínio autorizado.");
        }
        this.allowedDomains = Set.copyOf(domains);
    }

    /** Address-bar input may omit the scheme; browser/network requests must not. */
    public URI validate(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Digite um endereço.");
        }
        if (!value.contains("://")) {
            if (value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")
                    && !value.matches("(?i)^[a-z0-9.-]+:(80|443)([/#?].*)?$")) {
                throw new IllegalArgumentException("Somente URLs http/https são permitidas.");
            }
            value = "https://" + value;
        }
        return validateRequest(value);
    }

    /** Strict URL and allowlist validation. DNS is checked at the actual egress boundary. */
    public URI validateRequest(String rawUrl) {
        return validateStructure(rawUrl);
    }

    // Egress follows this with exactly one DNS resolution and connects to a literal vetted address.
    URI validateStructure(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > MAX_URL_LENGTH
                || rawUrl.indexOf('\\') >= 0 || rawUrl.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw new IllegalArgumentException("URL inválida.");
        }
        final URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("URL inválida.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Somente URLs http/https são permitidas.");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawAuthority() == null
                || uri.getRawAuthority().contains("%") || uri.getRawAuthority().contains("@")) {
            throw new IllegalArgumentException("Use um domínio válido, sem credenciais na URL.");
        }
        int expectedPort = scheme.equals("https") ? 443 : 80;
        if (uri.getPort() != -1 && uri.getPort() != expectedPort) {
            throw new IllegalArgumentException("Use HTTP na porta 80 ou HTTPS na porta 443.");
        }
        requireAllowedHost(uri.getHost());
        return uri;
    }

    public Set<String> getAllowedDomains() {
        return allowedDomains;
    }

    /** Callers must connect to these addresses without resolving the hostname again. */
    public List<InetAddress> resolvePublic(String hostname) {
        String host = requireAllowedHost(hostname);
        Future<InetAddress[]> lookup;
        try {
            lookup = dnsWorkers.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException ex) {
            throw new IllegalArgumentException("Verificação DNS temporariamente indisponível.");
        }
        final InetAddress[] addresses;
        try {
            addresses = lookup.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Verificação DNS interrompida.");
        } catch (ExecutionException | TimeoutException ex) {
            lookup.cancel(true);
            throw new IllegalArgumentException("Não foi possível verificar o DNS deste domínio.");
        }
        if (addresses == null || addresses.length == 0 || addresses.length > 64
                || Arrays.stream(addresses).anyMatch(address -> !isPublicAddress(address))) {
            throw new IllegalArgumentException("O domínio resolve para um endereço local, privado ou reservado.");
        }
        return List.copyOf(Arrays.asList(addresses));
    }

    public boolean isAllowed(URI uri) {
        try {
            validateRequest(uri == null ? null : uri.toString());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String requireAllowedHost(String hostname) {
        String host = requireDomain(hostname == null ? "" : hostname.toLowerCase(Locale.ROOT));
        if (allowedDomains.stream().noneMatch(domain -> host.equals(domain) || host.endsWith("." + domain))) {
            throw new IllegalArgumentException("Domínio não autorizado: " + host);
        }
        return host;
    }

    private static String requireDomain(String domain) {
        if (domain.length() > 253 || domain.endsWith(".") || domain.indexOf('*') >= 0
                || !domain.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+") || InetAddresses.isInetAddress(domain)) {
            throw new IllegalArgumentException("Configure apenas domínios completos, sem curingas, URLs ou IPs.");
        }
        final InternetDomainName parsed;
        try {
            parsed = InternetDomainName.from(domain);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Domínio inválido: " + domain);
        }
        // Guava's PSL includes private/shared suffixes, preventing com.br/github.io allowlists.
        if (!parsed.hasPublicSuffix() || parsed.isPublicSuffix()
                || domain.endsWith(".localhost") || domain.endsWith(".local")
                || domain.endsWith(".internal") || domain.endsWith(".home.arpa")) {
            throw new IllegalArgumentException("A allowlist exige domínios específicos com sufixo público válido.");
        }
        return domain;
    }

    /** Conservative IANA special-purpose exclusions, stricter than RFC1918 checks alone. */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || a == 100 && b >= 64 && b <= 127
                    || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31
                    || a == 192 && b == 0 && (c == 0 || c == 2)
                    || a == 192 && b == 88 && c == 99
                    || a == 192 && b == 168
                    || a == 198 && (b == 18 || b == 19)
                    || a == 198 && b == 51 && c == 100
                    || a == 203 && b == 0 && c == 113
                    // Azure's virtual platform endpoint is outside private/link-local ranges.
                    || a == 168 && b == 63 && c == 129 && d == 16);
        }
        if (bytes.length != 16 || (bytes[0] & 0xe0) != 0x20) {
            // Native global-unicast 2000::/3 only: excludes mapped, NAT64, ULA/local prefixes.
            return false;
        }
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
        return !(a == 0x20 && b == 0x01 && c <= 1 // 2001::/23 protocol/transition assignments
                || a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8 // documentation
                || a == 0x20 && b == 0x02 // 6to4 with embedded IPv4
                || a == 0x3f && b == 0xff && (c & 0xf0) == 0); // 3fff::/20 documentation
    }

    @PreDestroy
    @Override
    public void close() {
        dnsWorkers.shutdownNow();
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String hostname) throws UnknownHostException;
    }
}
