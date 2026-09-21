package com.careerflux.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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
 */
public final class StreamingHttpServer implements AutoCloseable {

    private static final int CHUNK = 64 * 1024;

    private final HttpServer server;
    private final Map<String, AtomicLong> sent = new ConcurrentHashMap<>();

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

    /** A body of known length, sent with a Content-Length. */
    public StreamingHttpServer fixed(String path, int status, byte[] body, String... headers) {
        server.createContext(path, exchange -> {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
                counter(path).addAndGet(body.length);
            } catch (IOException clientWentAway) {
                // Expected when the client refuses the body.
            }
        });
        return this;
    }

    /** A body sent chunked, with no Content-Length, of exactly {@code length} bytes. */
    public StreamingHttpServer chunked(String path, long length) {
        return stream(path, 200, length);
    }

    /** A chunked body that runs until the client stops reading, capped at 128 MiB: well past every ceiling under test. */
    public StreamingHttpServer endless(String path) {
        return stream(path, 200, 128L * 1024 * 1024);
    }

    /** An error status whose chunked body would never end. */
    public StreamingHttpServer endlessError(String path, int status) {
        return stream(path, status, 128L * 1024 * 1024);
    }

    private StreamingHttpServer stream(String path, int status, long length) {
        server.createContext(path, exchange -> {
            // Length 0 means chunked transfer encoding: no Content-Length at all.
            exchange.sendResponseHeaders(status, 0);
            byte[] chunk = new byte[CHUNK];
            java.util.Arrays.fill(chunk, (byte) 'x');
            try (OutputStream out = exchange.getResponseBody()) {
                long remaining = length;
                while (remaining > 0) {
                    int n = (int) Math.min(chunk.length, remaining);
                    out.write(chunk, 0, n);
                    counter(path).addAndGet(n);
                    remaining -= n;
                }
            } catch (IOException clientWentAway) {
                // The client closed the connection: exactly what a bounded read should cause.
            }
        });
        return this;
    }

    private AtomicLong counter(String path) {
        return sent.computeIfAbsent(path, key -> new AtomicLong());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
