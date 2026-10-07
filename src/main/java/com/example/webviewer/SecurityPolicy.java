package com.example.webviewer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SecurityPolicy {

    private final Set<String> allowedDomains;

    public SecurityPolicy(
            @Value("${viewer.allowed-domains:wikipedia.org,example.com,developer.mozilla.org}") String allowedDomains) {
        this.allowedDomains = Arrays.stream(allowedDomains.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public URI validate(String rawUrl) throws Exception {
        URI uri = normalize(rawUrl);

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Somente URLs http/https são permitidas.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL inválida.");
        }

        final String finalHost = host.toLowerCase();

        boolean allowed = allowedDomains.stream()
                .anyMatch(domain -> finalHost.equals(domain) || finalHost.endsWith("." + domain));

        if (!allowed) {
            throw new IllegalArgumentException(
                    "Domínio não permitido neste protótipo: " + finalHost +
                    ". Edite viewer.allowed-domains para adicionar sites autorizados."
            );
        }

        // Defesa contra SSRF: mesmo um domínio permitido não pode resolver para rede local.
        for (InetAddress address : InetAddress.getAllByName(finalHost)) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("O endereço resolve para uma rede local/privada e foi bloqueado.");
            }
        }

        return uri;
    }

    private URI normalize(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Digite um endereço.");
        }

        if (!value.matches("(?i)^https?://.*")) {
            value = "https://" + value;
        }

        return URI.create(value);
    }

    public boolean isAllowed(URI uri) {
        try {
            validate(uri.toString());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
