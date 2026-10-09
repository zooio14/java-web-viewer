package com.example.webviewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated loopback proxy. All outgoing sockets use vetted literal IPs. */
public final class EgressProxy implements AutoCloseable {
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Set<String> HOP_HEADERS = Set.of("connection", "proxy-connection",
            "proxy-authorization", "proxy-authenticate", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length");

    private final SecurityPolicy policy;
    private final Connector connector;
    private final ServerSocket server;
    private final String username = randomCredential();
    private final String password = randomCredential();
    private final byte[] credentials = (username + ":" + password).getBytes(StandardCharsets.US_ASCII);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore connections = new Semaphore(16);
    // CONNECT requires two workers per connection; both connection count and workers are bounded.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "viewer-egress");
                thread.setDaemon(true);
                return thread;
            });
    private final Thread acceptor;

    public EgressProxy(SecurityPolicy policy) throws IOException {
        this(policy, (address, port, timeout) -> {
            Socket socket = new Socket();
            try {
                // InetSocketAddress(InetAddress, ...) cannot perform a second DNS lookup.
                socket.connect(new InetSocketAddress(address, port), timeout);
                return socket;
            } catch (IOException ex) {
                socket.close();
                throw ex;
            }
        });
    }

    EgressProxy(SecurityPolicy policy, Connector connector) throws IOException {
        this.policy = policy;
        this.connector = connector;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 16);
        this.acceptor = new Thread(this::accept, "viewer-egress-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean isRunning() {
        return !closed.get() && !server.isClosed() && acceptor.isAlive();
    }

    private void accept() {
        try {
            while (!closed.get()) {
                Socket client = server.accept();
                if (!connections.tryAcquire()) {
                    client.close();
                    continue;
                }
                sockets.add(client);
                if (closed.get()) {
                    finish(client);
                    continue;
                }
                try {
                    workers.execute(() -> handle(client));
                } catch (RejectedExecutionException ex) {
                    finish(client);
                }
            }
        } catch (IOException ex) {
            if (!closed.get()) {
                close();
            }
        }
    }

    private void handle(Socket client) {
        boolean started = false;
        try {
            client.setSoTimeout(5_000);
            Request request = readRequest(client.getInputStream());
            if (!authenticated(request)) {
                respond(client, 407, "Proxy Authentication Required", true);
                return;
            }
            if (request.method().equals("CONNECT")) {
                URI target = connectTarget(request);
                Socket upstream = connect(target.getHost(), 443);
                try (upstream) {
                    // A CONNECT request has no HTTP entity or nested request headers.
                    client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    started = true;
                    tunnel(client, upstream);
                } finally {
                    closeSocket(upstream);
                }
            } else {
                URI target = policy.validateRequest(request.target());
                if (!target.getScheme().equalsIgnoreCase("http") || target.getRawFragment() != null) {
                    throw new IllegalArgumentException("Destino HTTP inválido.");
                }
                int bodyLength = contentLength(request);
                byte[] body = readBody(client.getInputStream(), bodyLength);
                Socket upstream = connect(target.getHost(), 80);
                try (upstream) {
                    forwardRequest(upstream.getOutputStream(), request, target, body);
                    client.setSoTimeout(READ_TIMEOUT_MS);
                    started = true;
                    // Exactly one parsed request is forwarded. Pipelined bytes are never forwarded.
                    upstream.getInputStream().transferTo(client.getOutputStream());
                } finally {
                    closeSocket(upstream);
                }
            }
        } catch (IllegalArgumentException ex) {
            if (!started) {
                respondQuietly(client, 403, "Forbidden");
            }
        } catch (IOException | RejectedExecutionException ex) {
            if (!started) {
                respondQuietly(client, 502, "Bad Gateway");
            }
        } finally {
            finish(client);
        }
    }

    private Socket connect(String host, int port) throws IOException {
        List<InetAddress> addresses = policy.resolvePublic(host);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
        IOException failure = null;
        for (InetAddress address : addresses) {
            if (closed.get()) {
                throw new IOException("Proxy fechado.");
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                throw new SocketTimeoutException("Tempo de conexão excedido.");
            }
            Socket upstream = null;
            try {
                upstream = connector.connect(address, port, (int) Math.max(1, remaining));
                upstream.setSoTimeout(READ_TIMEOUT_MS);
                sockets.add(upstream);
                if (closed.get()) {
                    upstream.close();
                    throw new IOException("Proxy fechado.");
                }
                return upstream;
            } catch (IOException ex) {
                if (upstream != null) {
                    closeSocket(upstream);
                }
                failure = ex;
            }
        }
        throw failure == null ? new IOException("DNS vazio.") : failure;
    }

    private URI connectTarget(Request request) {
        if (!request.target().matches("[a-zA-Z0-9.-]+:443") || contentLength(request) != 0) {
            throw new IllegalArgumentException("CONNECT exige domínio e porta 443, sem corpo.");
        }
        return policy.validateRequest("https://" + request.target() + "/");
    }

    private boolean authenticated(Request request) {
        List<String> values = request.values("proxy-authorization");
        if (values.size() != 1 || !values.get(0).regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            return MessageDigest.isEqual(credentials,
                    Base64.getDecoder().decode(values.get(0).substring(6)));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static int contentLength(Request request) {
        if (!request.values("transfer-encoding").isEmpty() || !request.values("upgrade").isEmpty()) {
            throw new IllegalArgumentException("Framing ou upgrade não permitido.");
        }
        List<String> values = request.values("content-length");
        if (values.size() > 1 || !values.isEmpty() && !values.get(0).matches("[0-9]{1,7}")) {
            throw new IllegalArgumentException("Content-Length inválido.");
        }
        int length = values.isEmpty() ? 0 : Integer.parseInt(values.get(0));
        if (length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Corpo excede o limite.");
        }
        return length;
    }

    private static Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int matched = 0;
        byte[] terminator = {13, 10, 13, 10};
        while (matched != 4) {
            if (bytes.size() >= MAX_HEADER_BYTES || System.nanoTime() > deadline) {
                throw new IllegalArgumentException("Cabeçalhos excedem o limite.");
            }
            int value = input.read();
            if (value < 0) {
                throw new IOException("Requisição incompleta.");
            }
            if (value != 9 && value != 10 && value != 13 && (value < 32 || value > 126)) {
                throw new IllegalArgumentException("Cabeçalho inválido.");
            }
            bytes.write(value);
            matched = value == terminator[matched] ? matched + 1 : value == 13 ? 1 : 0;
        }
        String[] lines = bytes.toString(StandardCharsets.US_ASCII).split("\r\n", -1);
        if (lines.length > MAX_HEADERS + 3) {
            throw new IllegalArgumentException("Muitos cabeçalhos.");
        }
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[0].matches("[A-Z]{1,20}")
                || !Set.of("HTTP/1.0", "HTTP/1.1").contains(first[2])) {
            throw new IllegalArgumentException("Linha de requisição inválida.");
        }
        List<Header> headers = new ArrayList<>();
        for (int i = 1; i < lines.length - 2; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0 || !lines[i].substring(0, colon).matches("[!#$%&'*+.^_`|~A-Za-z0-9-]+")) {
                throw new IllegalArgumentException("Cabeçalho inválido.");
            }
            String value = lines[i].substring(colon + 1).trim();
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Cabeçalho inválido.");
            }
            headers.add(new Header(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), value));
        }
        Request request = new Request(first[0], first[1], List.copyOf(headers));
        contentLength(request);
        if (request.values("host").size() > 1) {
            throw new IllegalArgumentException("Host duplicado.");
        }
        return request;
    }

    private static byte[] readBody(InputStream input, int length) throws IOException {
        byte[] body = input.readNBytes(length);
        if (body.length != length) {
            throw new IOException("Corpo incompleto.");
        }
        return body;
    }

    private static void forwardRequest(OutputStream output, Request request, URI target, byte[] body)
            throws IOException {
        Set<String> excluded = new HashSet<>(HOP_HEADERS);
        for (String connection : request.values("connection")) {
            for (String value : connection.split(",")) {
                excluded.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        if (target.getRawQuery() != null) {
            path += "?" + target.getRawQuery();
        }
        StringBuilder head = new StringBuilder(request.method()).append(' ').append(path)
                .append(" HTTP/1.1\r\nHost: ").append(target.getHost()).append("\r\n");
        for (Header header : request.headers()) {
            if (!excluded.contains(header.name())) {
                head.append(header.name()).append(": ").append(header.value()).append("\r\n");
            }
        }
        if (body.length > 0 || !request.values("content-length").isEmpty()) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private void tunnel(Socket client, Socket upstream) throws IOException {
        client.setSoTimeout(READ_TIMEOUT_MS);
        // The second worker copies only tunnel bytes after an authorized CONNECT.
        workers.execute(() -> {
            try {
                client.getInputStream().transferTo(upstream.getOutputStream());
                upstream.shutdownOutput();
            } catch (IOException ex) {
                closeSocket(upstream);
                closeSocket(client);
            }
        });
        try {
            upstream.getInputStream().transferTo(client.getOutputStream());
        } finally {
            closeSocket(upstream);
            closeSocket(client);
        }
    }

    private static void respond(Socket client, int status, String reason, boolean auth) throws IOException {
        String body = status + " " + reason + "\n";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length() + "\r\n"
                + (auth ? "Proxy-Authenticate: Basic realm=\"viewer\"\r\n" : "") + "\r\n";
        client.getOutputStream().write((headers + body).getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    private static void respondQuietly(Socket client, int status, String reason) {
        try {
            respond(client, status, reason, false);
        } catch (IOException ignored) {
            // The client may have already closed the socket.
        }
    }

    private void finish(Socket client) {
        closeSocket(client);
        connections.release();
    }

    private void closeSocket(Socket socket) {
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best-effort, including application shutdown.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // The acceptor also closes the proxy if the listener fails.
        }
        for (Socket socket : sockets) {
            closeSocket(socket);
        }
        workers.shutdownNow();
    }

    private static String randomCredential() {
        byte[] value = new byte[24];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @FunctionalInterface
    interface Connector {
        Socket connect(InetAddress address, int port, int timeoutMillis) throws IOException;
    }

    private record Header(String name, String value) { }

    private record Request(String method, String target, List<Header> headers) {
        List<String> values(String name) {
            return headers.stream().filter(header -> header.name().equals(name)).map(Header::value).toList();
        }
    }
}
