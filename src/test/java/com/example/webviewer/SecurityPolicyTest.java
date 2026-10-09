package com.example.webviewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {
    @Test
    void normalizesAddressBarAndAllowsOnlyDomainBoundariesWithoutDns() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        try (SecurityPolicy policy = new SecurityPolicy("example.com,developer.mozilla.org", host -> {
            lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        })) {
            assertEquals("allowlist", policy.getDestinationMode());
            assertEquals("https://example.com/path", policy.validate(" example.com/path ").toString());
            assertEquals("https://example.com:443/", policy.validate("example.com:443/").toString());
            assertDoesNotThrow(() -> policy.validateRequest("HTTP://www.Example.com/path?x=1"));
            assertDoesNotThrow(() -> policy.validateRequest("https://developer.mozilla.org/"));
            assertEquals(0, lookups.get(), "URI checks must not introduce a DNS-to-socket race");
            assertEquals("93.184.216.34", policy.resolvePublic("example.com").get(0).getHostAddress());
            assertEquals(1, lookups.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/", "http://127.0.0.1/", "http://10.0.0.1/", "http://[::1]/",
            "http://2130706433/", "http://0x7f000001/", "http://127.1/", "http://example.com:8080/",
            "https://example.com:80/", "http://example.com:443/", "https://example.com.evil.com/",
            "https://evil-example.com/", "https://example.com@evil.com/", "https://user@example.com/",
            "https://example.com./", "https://%65xample.com/", "https://example.com\\@evil.com/",
            "https://example.com/\r\nHeader: injected", "file:///etc/passwd", "ftp://example.com/",
            "javascript:alert(1)", "data:text/html,test", "about:blank", "example.com/path", ""
    })
    void rejectsUnsafeOrNonAllowlistedRequestUrls(String value) {
        try (SecurityPolicy policy = new SecurityPolicy("example.com")) {
            assertThrows(IllegalArgumentException.class, () -> policy.validateRequest(value));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "127.0.0.1", "*.example.com", "https://example.com",
            "example.com:443", "com", "com.br", "github.io", "example.local", "foo.internal",
            "example.com.", "example.com,", "-bad.example.com", "example.invalid"})
    void rejectsBroadInvalidOrLocalAllowlistConfiguration(String value) {
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy(value));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy(value, "public"));
    }

    @Test
    void publicModeAllowsRealDomainsOutsideAllowlistWhilePreservingDnsBoundary() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        try (SecurityPolicy policy = new SecurityPolicy("example.com", "public", hostname -> {
            lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        })) {
            assertEquals("public", policy.getDestinationMode());
            assertEquals("https://example.net/", policy.validate("example.net/").toString());
            assertDoesNotThrow(() -> policy.validateRequest("https://www.mozilla.org/"));
            assertDoesNotThrow(() -> policy.validateRequest("https://user.github.io/"));
            assertDoesNotThrow(() -> policy.validateRequest("https://github.io/"));
            assertEquals(0, lookups.get());
            assertEquals("8.8.8.8", policy.resolvePublic("example.net").get(0).getHostAddress());
            assertEquals(1, lookups.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/", "http://127.0.0.1/", "http://8.8.8.8/",
            "http://[2001:4860:4860::8888]/", "http://2130706433/", "http://0x7f000001/",
            "https://example.invalid/", "https://com/", "https://example.local/", "https://foo.internal/",
            "https://home.arpa/", "https://foo.home.arpa/", "https://user@example.net/", "https://example.net:8080/",
            "https://example.net./", "https://%65xample.net/", "file:///etc/passwd", "data:text/html,test"})
    void publicModeStillRejectsLiteralIpsLocalInvalidDomainsAndUnsafeUrls(String value) {
        try (SecurityPolicy policy = new SecurityPolicy("example.com", "public")) {
            assertThrows(IllegalArgumentException.class, () -> policy.validateRequest(value));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "open", "", "PUBLICLY", "http"})
    void invalidDestinationModeFailsStartup(String mode) {
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("example.com", mode));
    }

    @Test
    void publicModeFailsClosedOnPrivateOrMixedDnsForDomainOutsideAllowlist() throws Exception {
        try (SecurityPolicy privateDns = new SecurityPolicy("example.com", "public", hostname ->
                new InetAddress[]{InetAddress.getByName("10.0.0.1")});
             SecurityPolicy mixedDns = new SecurityPolicy("example.com", "public", hostname ->
                     new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("::1")})) {
            assertDoesNotThrow(() -> privateDns.validateRequest("https://example.net/"));
            assertThrows(IllegalArgumentException.class, () -> privateDns.resolvePublic("example.net"));
            assertThrows(IllegalArgumentException.class, () -> mixedDns.resolvePublic("example.net"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "0.8.8.8", "10.0.0.1", "100.64.0.1", "100.127.255.254",
            "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.254", "192.168.1.1",
            "192.0.0.9", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "168.63.129.16", "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1",
            "ff02::1", "64:ff9b::a00:1", "2001:db8::1", "2001:0::1", "2001:20::1",
            "2002:7f00:1::1", "3fff::1"})
    void rejectsEveryPrivateLocalAndReservedAddressAtDnsBoundary(String value) throws Exception {
        InetAddress address = InetAddress.getByName(value);
        assertFalse(SecurityPolicy.isPublicAddress(address));
        try (SecurityPolicy policy = new SecurityPolicy("example.com", host -> new InetAddress[]{address})) {
            assertThrows(IllegalArgumentException.class, () -> policy.resolvePublic("example.com"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "100.128.0.1",
            "172.32.0.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
    void permitsPublicAddresses(String value) throws Exception {
        assertTrue(SecurityPolicy.isPublicAddress(InetAddress.getByName(value)));
    }

    @Test
    void rejectsWholeDnsAnswerIfEvenOneRecordIsPrivateAndFailsClosedOnDnsErrors() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.216.34");
        InetAddress privateIp = InetAddress.getByName("10.0.0.1");
        try (SecurityPolicy mixed = new SecurityPolicy("example.com", host -> new InetAddress[]{publicIp, privateIp});
             SecurityPolicy empty = new SecurityPolicy("example.com", host -> new InetAddress[0]);
             SecurityPolicy failure = new SecurityPolicy("example.com", host -> { throw new UnknownHostException(); })) {
            assertThrows(IllegalArgumentException.class, () -> mixed.resolvePublic("example.com"));
            assertThrows(IllegalArgumentException.class, () -> empty.resolvePublic("example.com"));
            assertThrows(IllegalArgumentException.class, () -> failure.resolvePublic("example.com"));
            assertThrows(IllegalArgumentException.class, () -> mixed.resolvePublic("evil.com"));
        }
    }
}
