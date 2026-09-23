package com.coremc.core.tebex;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A one-request-per-thread HTTP/1.1 stand-in for the Tebex Plugin API
 * (the sandbox JRE has no jdk.httpserver module). Responses are
 * queued: every incoming request pops the next queued response — or a
 * 404 when the queue is empty. Each request's path, {@code
 * X-Tebex-Secret} header and body are captured for assertions.
 */
public final class MockTebexServer {

    private record Response(int status, String body) {
    }

    private final ServerSocket socket;
    private final Deque<Response> queue = new ArrayDeque<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    public MockTebexServer() throws IOException {
        this.socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final Thread acceptor = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket client = socket.accept();
                    handle(client);
                } catch (final IOException exception) {
                    return; // closed
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void handle(final Socket client) throws IOException {
        try (client) {
            // Parse the request head byte-by-byte (up to the blank line),
            // then read exactly content-length body bytes — no reader
            // buffering ahead of the body.
            final InputStream in = client.getInputStream();
            final StringBuilder head = new StringBuilder();
            int crlf = 0;
            while (crlf < 4) {
                final int b = in.read();
                if (b < 0) {
                    return;
                }
                if (b == '\r' || b == '\n') {
                    crlf = (crlf == 0 && b == '\r') || (crlf == 2 && b == '\r')
                            || (crlf == 1 && b == '\n') || (crlf == 3 && b == '\n') ? crlf + 1 : 0;
                } else {
                    crlf = 0;
                }
                head.append((char) b);
            }
            final String[] headLines = head.toString().split("\r\n");
            final String requestLine = headLines[0];
            String path = requestLine.split(" ")[1];
            String secret = "";
            int contentLength = 0;
            for (int i = 1; i < headLines.length; i++) {
                final String line = headLines[i];
                final String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-tebex-secret:")) {
                    secret = line.substring("x-tebex-secret:".length()).trim();
                }
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(
                            line.substring("content-length:".length()).trim());
                }
            }
            String body = "";
            if (contentLength > 0) {
                final byte[] raw = new byte[contentLength];
                int read = 0;
                while (read < raw.length) {
                    final int chunk = in.read(raw, read, raw.length - read);
                    if (chunk < 0) {
                        break;
                    }
                    read += chunk;
                }
                body = new String(raw, StandardCharsets.UTF_8);
            }
            // strip the query string: only the path is interesting here
            final int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            paths.add(path);
            secrets.add(secret);
            bodies.add(body);
            requests.incrementAndGet();

            final Response response;
            synchronized (queue) {
                response = queue.isEmpty() ? new Response(404, "{\"error_message\": \"Not found\"}")
                        : queue.pop();
            }
            final byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            final PrintWriter out = new PrintWriter(client.getOutputStream(), false);
            out.print("HTTP/1.1 " + response.status() + " "
                    + (response.status() == 200 ? "OK"
                            : response.status() == 201 ? "Created" : "Not Found") + "\r\n");
            out.print("Content-Type: application/json\r\n");
            out.print("Content-Length: " + payload.length + "\r\n");
            out.print("Connection: close\r\n\r\n");
            out.flush();
            client.getOutputStream().write(payload);
            client.getOutputStream().flush();
        }
    }

    /** Queues the next response (served in order, one per request). */
    public void respond(final int status, final String body) {
        synchronized (queue) {
            queue.add(new Response(status, body));
        }
    }

    /** Where this stand-in lives. */
    public String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** The request count served so far. */
    public int requests() {
        return requests.get();
    }

    /** The last request's path. */
    public String lastPath() {
        return paths.isEmpty() ? "" : paths.get(paths.size() - 1);
    }

    /** The last request's X-Tebex-Secret header. */
    public String lastSecret() {
        return secrets.isEmpty() ? "" : secrets.get(secrets.size() - 1);
    }

    /** The last request's body ("" when it had none). */
    public String lastBody() {
        return bodies.isEmpty() ? "" : bodies.get(bodies.size() - 1);
    }

    /** Every request's path, in order. */
    public List<String> paths() {
        return new ArrayList<>(paths);
    }

    /** Every request's body, in order. */
    public List<String> bodies() {
        return new ArrayList<>(bodies);
    }

    /** Shuts the stand-in down. */
    public void close() throws IOException {
        socket.close();
    }
}
