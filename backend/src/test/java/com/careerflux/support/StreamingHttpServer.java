package com.careerflux.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A real HTTP server on the loopback interface, for tests that need a response
 * to arrive the way it would over a network: chunked, with no Content-Length,
 * and for as long as the client keeps reading.
 *
 * <p>A stubbed request factory hands back a finished byte array, which is
 * exactly the shape that hid the unbounded read in the first place. This one
 * streams, and counts what it sent, so a test can tell "stopped reading" from
 * "read everything and then complained".
 *
 * <p>It also records when each handler finished and whether it managed to send
 * everything, so a test can tell a client that hung up early from one that kept
 * the connection open or drained it to the end.
 */
public final class StreamingHttpServer implements AutoCloseable {

    private static final int CHUNK = 64 * 1024;

    private final HttpServer server;
    private final Map<String, AtomicLong> sent = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> finished = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sentEverything = new ConcurrentHashMap<>();
    private final Map<String, Headers> requests = new ConcurrentHashMap<>();

    public StreamingHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Bytes the handler for {@code path} managed to write before the client stopped reading. */
    public long bytesSent(String path) {
        return sent.getOrDefault(path, new AtomicLong()).get();
    }

    /**
     * Waits for the handler for {@code path} to finish, whether it sent everything
     * or the client hung up. A client that neither reads nor closes never lets it finish.
     */
    public boolean awaitFinished(String path, Duration timeout) throws InterruptedException {
        CountDownLatch latch = finished.get(path);
        return latch != null && latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** True when the handler for {@code path} wrote its whole body; false when the client hung up first. */
    public boolean sentEverything(String path) {
        return sentEverything.getOrDefault(path, false);
    }

    /** A header of the last request made to {@code path}, or null. */
    public String requestHeader(String path, String name) {
        Headers headers = requests.get(path);
        return headers == null ? null : headers.getFirst(name);
    }

    /** A body of known length, sent with a Content-Length. */
    public StreamingHttpServer fixed(String path, int status, byte[] body, String... headers) {
        return respond(path, exchange -> {
            addHeaders(exchange, headers);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
                counter(path).addAndGet(body.length);
            }
        });
    }

    /** A body sent chunked, with no Content-Length, of exactly {@code length} bytes. */
    public StreamingHttpServer chunked(String path, long length) {
        return stream(path, 200, new byte[0], length);
    }

    /** A chunked body that runs until the client stops reading, capped at 128 MiB: well past every ceiling under test. */
    public StreamingHttpServer endless(String path) {
        return stream(path, 200, new byte[0], 128L * 1024 * 1024);
    }

    /** An error status whose chunked body would never end. */
    public StreamingHttpServer endlessError(String path, int status) {
        return stream(path, status, new byte[0], 128L * 1024 * 1024);
    }

    /**
     * A chunked body, with no Content-Length, that starts with {@code head} and is
     * padded with {@code x} to exactly {@code length} bytes: real content where a
     * reader looks for it, then as much filler as the test wants to offer.
     */
    public StreamingHttpServer page(String path, int status, byte[] head, long length, String... headers) {
        return stream(path, status, head, length, headers);
    }

    private StreamingHttpServer stream(String path, int status, byte[] head, long length, String... headers) {
        return respond(path, exchange -> {
            addHeaders(exchange, headers);
            // Length 0 means chunked transfer encoding: no Content-Length at all.
            exchange.sendResponseHeaders(status, 0);
            byte[] chunk = new byte[CHUNK];
            java.util.Arrays.fill(chunk, (byte) 'x');
            try (OutputStream out = exchange.getResponseBody()) {
                int headBytes = (int) Math.min(head.length, length);
                out.write(head, 0, headBytes);
                counter(path).addAndGet(headBytes);
                long remaining = length - headBytes;
                while (remaining > 0) {
                    int n = (int) Math.min(chunk.length, remaining);
                    out.write(chunk, 0, n);
                    counter(path).addAndGet(n);
                    remaining -= n;
                }
            }
        });
    }

    private interface Body {
        void write(HttpExchange exchange) throws IOException;
    }

    private StreamingHttpServer respond(String path, Body body) {
        CountDownLatch done = new CountDownLatch(1);
        finished.put(path, done);
        server.createContext(path, exchange -> {
            Headers copy = new Headers();
            copy.putAll(exchange.getRequestHeaders());
            requests.put(path, copy);
            try {
                body.write(exchange);
                sentEverything.put(path, true);
            } catch (IOException clientWentAway) {
                // The client closed the connection: exactly what a bounded read should cause.
                sentEverything.put(path, false);
            } finally {
                exchange.close();
                done.countDown();
            }
        });
        return this;
    }

    private static void addHeaders(HttpExchange exchange, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
        }
    }

    private AtomicLong counter(String path) {
        return sent.computeIfAbsent(path, key -> new AtomicLong());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
