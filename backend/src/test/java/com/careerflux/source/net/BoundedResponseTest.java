package com.careerflux.source.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

import com.careerflux.support.StreamingHttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The response ceiling is enforced while reading, not after (Phase 1).
 *
 * <p>The regression these guard against is specific: a body read completely
 * into memory and only then measured. So the tests use a stream that never ends
 * and a real chunked response with no Content-Length, where "complained
 * afterwards" and "stopped reading" come out differently.
 */
class BoundedResponseTest {

    private static final int LIMIT = 1024 * 1024;

    /** A stream with no end, counting what was taken from it. */
    private static final class EndlessStream extends InputStream {
        long served;

        @Override
        public int read() {
            served++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'x');
            served += length;
            return length;
        }
    }

    @Nested
    @DisplayName("reading a stream")
    class Reading {

        @Test
        @DisplayName("a body under the ceiling is returned whole")
        void underTheCeiling() throws IOException {
            byte[] body = BoundedResponse.readAtMost(new ByteArrayInputStream(new byte[1000]), LIMIT);
            assertThat(body).hasSize(1000);
        }

        @Test
        @DisplayName("a body exactly at the ceiling is returned whole")
        void exactlyAtTheCeiling() throws IOException {
            byte[] body = BoundedResponse.readAtMost(new ByteArrayInputStream(new byte[LIMIT]), LIMIT);
            assertThat(body).hasSize(LIMIT);
        }

        @Test
        @DisplayName("one byte over the ceiling is refused")
        void oneByteOver() {
            assertThatThrownBy(() -> BoundedResponse.readAtMost(new ByteArrayInputStream(new byte[LIMIT + 1]), LIMIT))
                    .isInstanceOf(BoundedResponse.BodyTooLargeException.class);
        }

        @Test
        @DisplayName("an endless stream is abandoned one byte past the ceiling, never read to its end")
        void endlessStreamIsAbandoned() {
            EndlessStream endless = new EndlessStream();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(() -> BoundedResponse.readAtMost(endless, LIMIT))
                            .isInstanceOf(BoundedResponse.BodyTooLargeException.class));
            assertThat(endless.served).isEqualTo(LIMIT + 1L);
        }
    }

    @Nested
    @DisplayName("over HTTP")
    class OverHttp {

        private StreamingHttpServer server;
        private RestClient client;

        @BeforeEach
        void start() throws IOException {
            server = new StreamingHttpServer();
            // The production transport, without the address checks a loopback test
            // server would fail, and like production it never follows a redirect itself.
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                        throws IOException {
                    super.prepareConnection(connection, method);
                    connection.setInstanceFollowRedirects(false);
                }
            };
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(5));
            client = RestClient.builder().requestFactory(factory).build();
        }

        @AfterEach
        void stop() {
            server.close();
        }

        @Test
        @DisplayName("a small body with a Content-Length comes back whole")
        void smallFixedBody() {
            server.fixed("/small", 200, "{\"jobs\":[]}".getBytes());
            BoundedResponse.Response response = BoundedResponse.get(client, server.url("/small"), LIMIT);
            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body())).isEqualTo("{\"jobs\":[]}");
        }

        @Test
        @DisplayName("a chunked body exactly at the ceiling comes back whole")
        void chunkedAtTheCeiling() {
            server.chunked("/exact", LIMIT);
            assertThat(BoundedResponse.get(client, server.url("/exact"), LIMIT).body()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("a chunked body with no Content-Length, one byte over, is refused")
        void chunkedJustOver() {
            server.chunked("/over", LIMIT + 1L);
            assertThatThrownBy(() -> BoundedResponse.get(client, server.url("/over"), LIMIT))
                    .isInstanceOf(BoundedResponse.BodyTooLargeException.class);
        }

        @Test
        @DisplayName("an endless chunked body is cut off quickly, and the server is stopped from sending")
        void endlessChunkedBody() {
            server.endless("/endless");
            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                    assertThatThrownBy(() -> BoundedResponse.get(client, server.url("/endless"), LIMIT))
                            .isInstanceOf(BoundedResponse.BodyTooLargeException.class));
            // Socket buffers let the server get somewhat ahead of the reader, but
            // nowhere near the 128 MiB it would send to a client that kept reading.
            assertThat(server.bytesSent("/endless")).isLessThan(64L * 1024 * 1024);
        }

        @Test
        @DisplayName("a declared Content-Length over the ceiling is refused")
        void declaredLengthOver() {
            server.fixed("/declared", 200, new byte[LIMIT + 10]);
            assertThatThrownBy(() -> BoundedResponse.get(client, server.url("/declared"), LIMIT))
                    .isInstanceOf(BoundedResponse.BodyTooLargeException.class);
        }

        @Test
        @DisplayName("a gzip body is counted as sent and never inflated, so it cannot expand past the ceiling")
        void compressedBodyIsNotInflated() throws IOException {
            // 16 MiB of zeros compress to a few kilobytes.
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(new byte[16 * 1024 * 1024]);
            }
            byte[] bomb = compressed.toByteArray();
            server.fixed("/gzip", 200, bomb, "Content-Encoding", "gzip");

            byte[] body = BoundedResponse.get(client, server.url("/gzip"), LIMIT).body();
            assertThat(body).isEqualTo(bomb);
            assertThat(body.length).isLessThan(LIMIT);
        }

        @Test
        @DisplayName("an error status is returned without reading its body, even an endless one")
        void errorBodyIsNotRead() {
            server.endlessError("/error", 500);
            BoundedResponse.Response response = assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> BoundedResponse.get(client, server.url("/error"), LIMIT));
            assertThat(response.isError()).isTrue();
            assertThat(response.body()).isEmpty();
            assertThat(server.bytesSent("/error")).isLessThan(64L * 1024 * 1024);
        }

        @Test
        @DisplayName("a redirect is handed back, not followed, so the caller can validate the hop")
        void redirectIsReturned() {
            server.fixed("/moved", 302, new byte[0], "Location", "https://example.com/elsewhere");
            BoundedResponse.Response response = BoundedResponse.get(client, server.url("/moved"), LIMIT);
            assertThat(response.isRedirect()).isTrue();
            assertThat(response.headers().getFirst("Location")).isEqualTo("https://example.com/elsewhere");
        }
    }

    /**
     * Keeping the start of a large body instead of refusing it (Phase 1 follow-up),
     * for robots.txt and the home-page check. The ceiling holds just the same: one
     * byte past it shows the body goes on, and the rest is never read.
     */
    @Nested
    @DisplayName("keeping only the start")
    class KeepingTheStart {

        /** Far past the ceiling, and still small enough that no failure could fill a disk. */
        private static final long LARGE = 32L * 1024 * 1024;

        /** How far socket buffers let a server write ahead of a reader that has stopped. */
        private static final long BUFFER_SLACK = 4L * 1024 * 1024;

        private StreamingHttpServer server;
        private RestClient client;

        @BeforeEach
        void start() throws IOException {
            server = new StreamingHttpServer();
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                        throws IOException {
                    super.prepareConnection(connection, method);
                    connection.setInstanceFollowRedirects(false);
                }
            };
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(5));
            client = RestClient.builder().requestFactory(factory).build();
        }

        @AfterEach
        void stop() {
            server.close();
        }

        private BoundedResponse.Prefix get(String path) {
            return assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> BoundedResponse.getPrefix(client, server.url(path), LIMIT));
        }

        @Test
        @DisplayName("a stream is never asked for more than the limit it was given")
        void readPrefixStopsAtItsLimit() throws IOException {
            EndlessStream endless = new EndlessStream();
            byte[] read = BoundedResponse.readPrefix(endless, LIMIT + 1);
            assertThat(read.length).isEqualTo(LIMIT + 1);
            assertThat(endless.served).isEqualTo(LIMIT + 1L);
        }

        @Test
        @DisplayName("a body under the ceiling comes back whole and is not marked truncated")
        void underTheCeiling() {
            server.chunked("/under", LIMIT - 1L);
            BoundedResponse.Prefix prefix = get("/under");
            assertThat(prefix.body().length).isEqualTo(LIMIT - 1);
            assertThat(prefix.truncated()).isFalse();
        }

        @Test
        @DisplayName("a body exactly at the ceiling comes back whole and is not marked truncated")
        void exactlyAtTheCeiling() {
            server.chunked("/exact", LIMIT);
            BoundedResponse.Prefix prefix = get("/exact");
            assertThat(prefix.body().length).isEqualTo(LIMIT);
            assertThat(prefix.truncated()).isFalse();
        }

        @Test
        @DisplayName("one byte over: exactly the ceiling is kept, and the body is marked truncated")
        void oneByteOver() {
            server.chunked("/over", LIMIT + 1L);
            BoundedResponse.Prefix prefix = get("/over");
            assertThat(prefix.body().length).isEqualTo(LIMIT);
            assertThat(prefix.truncated()).isTrue();
        }

        @Test
        @DisplayName("an endless chunked body is cut at the ceiling and the connection closed at once")
        void endlessBody() throws InterruptedException {
            server.page("/endless", 200, new byte[0], LARGE);
            BoundedResponse.Prefix prefix = get("/endless");
            assertThat(prefix.body().length).isEqualTo(LIMIT);
            assertThat(prefix.truncated()).isTrue();
            assertThat(server.awaitFinished("/endless", Duration.ofSeconds(10))).describedAs("handler finished").isTrue();
            assertThat(server.sentEverything("/endless")).describedAs("server sent all 32 MiB").isFalse();
            assertThat(server.bytesSent("/endless")).describedAs("bytes sent").isLessThan(LIMIT + BUFFER_SLACK);
        }

        @Test
        @DisplayName("an error status is returned without reading its body, however long it runs")
        void errorBodyIsNotRead() throws InterruptedException {
            server.page("/error", 500, new byte[0], LARGE);
            BoundedResponse.Prefix prefix = get("/error");
            assertThat(prefix.status()).isEqualTo(500);
            assertThat(prefix.body()).isEmpty();
            assertThat(prefix.truncated()).isFalse();
            assertThat(server.awaitFinished("/error", Duration.ofSeconds(10))).describedAs("handler finished").isTrue();
            assertThat(server.sentEverything("/error")).describedAs("server sent the whole error body").isFalse();
        }

        @Test
        @DisplayName("a redirect is handed back with its body, and not followed")
        void redirectBodyIsKept() {
            server.fixed("/moved", 301, "Moved".getBytes(), "Location", "https://example.com/elsewhere");
            BoundedResponse.Prefix prefix = get("/moved");
            assertThat(prefix.status()).isEqualTo(301);
            assertThat(prefix.headers().getFirst("Location")).isEqualTo("https://example.com/elsewhere");
            assertThat(new String(prefix.body())).isEqualTo("Moved");
        }
    }
}
