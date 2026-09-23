package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.careerflux.common.error.UnsafeUrlException;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.SourceRateLimiter;
import com.careerflux.support.StreamingHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fetching a web page through the adapter transport (Phase 2, stage 3).
 *
 * <p>{@code getPage} shares everything with {@code getJson} but the Accept header
 * and the ceiling, so these tests are about the things that can only be shown over
 * a real connection: that the 2 MiB ceiling holds while the body arrives, that an
 * error body is not read, and that every redirect is validated before it is taken.
 *
 * <p>Nothing here prints a body: assertions are on lengths, counts, flags and
 * classifications, so a failure cannot flood the test output.
 */
class AdapterPageFetchTest {

    /** The production page ceiling. */
    private static final int LIMIT = 2 * 1024 * 1024;

    /** Far past the ceiling, and small enough that no failure could fill a disk. */
    private static final long LARGE = 32L * 1024 * 1024;

    /** How far socket buffers let a server write ahead of a reader that has stopped. */
    private static final long BUFFER_SLACK = 4L * 1024 * 1024;

    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    private static final String HOST = "pages.test.example";

    private StreamingHttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    /** Just enough of an adapter to reach the shared fetch path. */
    private static final class ProbeAdapter extends AbstractHttpJobAdapter {

        ProbeAdapter(RestClient client, SafeUrlValidator validator, SourceRateLimiter limiter) {
            super(client, limiter, validator, new ObjectMapper());
        }

        FetchedPage page(String url) {
            return getPage(configuration(url), url);
        }

        FetchedJson json(String url) {
            return getJson(configuration(url), url);
        }

        FetchedPage pageFor(SourceConfiguration configuration) {
            return getPage(configuration, configuration.baseUrl());
        }

        private static SourceConfiguration configuration(String url) {
            return new SourceConfiguration(UUID.randomUUID(), "Test board", url, "test", "test", 6000, null, 200);
        }

        @Override
        public SourceMetadata getMetadata() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean supports(SourceConfiguration configuration) {
            return false;
        }

        @Override
        public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
            return List.of();
        }

        @Override
        public SourceHealthResult checkHealth(SourceConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected String endpointFor(SourceConfiguration configuration) {
            return configuration.baseUrl();
        }

        @Override
        protected int countPostings(com.fasterxml.jackson.databind.JsonNode body) {
            return 0;
        }
    }

    /**
     * The real validator resolves hosts through DNS, which a test must not need.
     * This one allows exactly one https host, and refuses anything else the way the
     * real one refuses a private address or a plain-http hop.
     */
    private static final class OnlyTheTestHost extends SafeUrlValidator {
        @Override
        public URI validate(String rawUrl) {
            URI uri = URI.create(rawUrl);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
                throw new UnsafeUrlException("Refused in this test: " + rawUrl);
            }
            return uri;
        }
    }

    private SimpleClientHttpRequestFactory loopback;

    @BeforeEach
    void start() throws IOException {
        server = new StreamingHttpServer();
        loopback = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                    throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        loopback.setConnectTimeout(Duration.ofSeconds(5));
        loopback.setReadTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** An adapter whose requests go straight to the loopback server, with the real validator. */
    private ProbeAdapter direct() {
        return new ProbeAdapter(RestClient.builder().requestFactory(loopback).build(),
                new SafeUrlValidator(), new SourceRateLimiter());
    }

    /**
     * An adapter that believes it is talking to {@code https://pages.test.example},
     * so redirects are resolved and validated as they would be in production, while
     * the bytes come from the loopback server.
     */
    private ProbeAdapter routed() {
        ClientHttpRequestFactory routing = (uri, method) -> {
            requested.add(uri.toString());
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return loopback.createRequest(URI.create(server.url(path)), method);
        };
        return new ProbeAdapter(RestClient.builder().requestFactory(routing).build(),
                new OnlyTheTestHost(), new SourceRateLimiter());
    }

    private static byte[] html(String body) {
        return ("<!doctype html><html><head><title>Careers</title></head><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("an ordinary page")
    class Ordinary {

        @Test
        @DisplayName("comes back as text, with the status and nothing parsed")
        void plainPage() {
            server.fixed("/jobs/1", 200, html("<h1>Platform Engineer</h1><script type=\"application/ld+json\">{}</script>"));

            AbstractHttpJobAdapter.FetchedPage page = direct().page(server.url("/jobs/1"));

            assertThat(page.httpStatus()).isEqualTo(200);
            assertThat(page.html()).contains("<h1>Platform Engineer</h1>", "application/ld+json");
            assertThat(page.latencyMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("is asked for as a web page, while a feed is still asked for as one")
        void acceptHeaders() {
            server.fixed("/page", 200, html("<p>hello</p>"));
            server.fixed("/feed", 200, "{\"jobs\":[]}".getBytes(StandardCharsets.UTF_8),
                    "Content-Type", "application/json");
            ProbeAdapter adapter = direct();

            adapter.page(server.url("/page"));
            adapter.json(server.url("/feed"));

            assertThat(server.requestHeader("/page", "Accept")).isEqualTo("text/html, */*");
            assertThat(server.requestHeader("/feed", "Accept")).doesNotContain("text/html");
        }

        @Test
        @DisplayName("sent chunked, with no Content-Length, is read the same way")
        void chunkedPage() {
            byte[] body = html("<p>chunked</p>");
            server.page("/chunked", 200, body, body.length);

            assertThat(direct().page(server.url("/chunked")).html()).contains("chunked");
        }

        @Test
        @DisplayName("is decoded by the charset it declares, and as UTF-8 when it declares none")
        void charsets() {
            byte[] latin1 = "<html><body>café</body></html>".getBytes(StandardCharsets.ISO_8859_1);
            server.fixed("/latin1", 200, latin1, "Content-Type", "text/html; charset=ISO-8859-1");
            server.fixed("/utf8", 200, "<html><body>café</body></html>".getBytes(StandardCharsets.UTF_8),
                    "Content-Type", "text/html");

            assertThat(direct().page(server.url("/latin1")).html()).contains("café");
            assertThat(direct().page(server.url("/utf8")).html()).contains("café");
        }

        @Test
        @DisplayName("that answers with nothing is an empty response, not an empty page")
        void emptyPage() {
            server.fixed("/empty", 200, new byte[0]);

            assertThatThrownBy(() -> direct().page(server.url("/empty")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.EMPTY_RESPONSE));
        }
    }

    @Nested
    @DisplayName("the 2 MiB ceiling")
    class Ceiling {

        @Test
        @DisplayName("a page of exactly 2 MiB is read whole")
        void exactlyAtTheCeiling() {
            server.chunked("/exact", LIMIT);

            AbstractHttpJobAdapter.FetchedPage page = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> direct().page(server.url("/exact")));

            assertThat(page.html().length()).isEqualTo(LIMIT);
        }

        @Test
        @DisplayName("one byte over is refused as a malformed response")
        void oneByteOver() {
            server.chunked("/over", LIMIT + 1L);

            assertThatThrownBy(() -> direct().page(server.url("/over")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.MALFORMED_RESPONSE));
        }

        @Test
        @DisplayName("a declared Content-Length over the ceiling is refused before the body is read")
        void declaredOverTheCeiling() throws InterruptedException {
            server.fixed("/declared", 200, new byte[LIMIT + 1024]);

            assertThatThrownBy(() -> direct().page(server.url("/declared")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.MALFORMED_RESPONSE));
            assertThat(server.awaitFinished("/declared", PROMPTLY)).describedAs("handler finished").isTrue();
            assertThat(server.sentEverything("/declared")).describedAs("server sent the whole body").isFalse();
        }

        @Test
        @DisplayName("a page that will not end is cut off while it arrives, and the connection closed")
        void endlessPage() throws InterruptedException {
            server.page("/endless", 200, html("<p>start</p>"), LARGE);

            assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                    assertThatThrownBy(() -> direct().page(server.url("/endless")))
                            .isInstanceOf(AdapterException.class)
                            .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                                    .isEqualTo(FailureClassification.MALFORMED_RESPONSE)));

            assertThat(server.awaitFinished("/endless", PROMPTLY)).describedAs("handler finished").isTrue();
            assertThat(server.sentEverything("/endless")).describedAs("server sent all 32 MiB").isFalse();
            assertThat(server.bytesSent("/endless")).describedAs("bytes the server managed to send")
                    .isLessThan(LIMIT + BUFFER_SLACK);
        }

        @Test
        @DisplayName("an error page is classified from its status, with its body left unread")
        void hugeErrorBody() throws InterruptedException {
            server.page("/error", 503, html("<p>sorry</p>"), LARGE);

            assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                    assertThatThrownBy(() -> direct().page(server.url("/error")))
                            .isInstanceOf(AdapterException.class)
                            .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                                    .isEqualTo(FailureClassification.UPSTREAM_TRANSIENT)));

            assertThat(server.awaitFinished("/error", PROMPTLY)).describedAs("handler finished").isTrue();
            assertThat(server.sentEverything("/error")).describedAs("server sent the whole error body").isFalse();
            assertThat(server.bytesSent("/error")).describedAs("bytes the server managed to send")
                    .isLessThan((long) LIMIT);
        }

        @Test
        @DisplayName("a 404 is a permanent failure, as it is for a feed")
        void notFound() {
            server.fixed("/missing", 404, html("<p>no such job</p>"));

            assertThatThrownBy(() -> direct().page(server.url("/missing")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.UPSTREAM_PERMANENT));
        }
    }

    @Nested
    @DisplayName("redirects")
    class Redirects {

        @Test
        @DisplayName("three validated hops are followed")
        void threeHops() {
            server.fixed("/one", 301, new byte[0], "Location", "https://" + HOST + "/two");
            server.fixed("/two", 302, new byte[0], "Location", "https://" + HOST + "/three");
            server.fixed("/three", 307, new byte[0], "Location", "https://" + HOST + "/final");
            server.fixed("/final", 200, html("<p>arrived</p>"));

            assertThat(routed().page("https://" + HOST + "/one").html()).contains("arrived");
            assertThat(requested).hasSize(4);
        }

        @Test
        @DisplayName("a fourth is refused rather than followed")
        void fourthHop() {
            server.fixed("/one", 301, new byte[0], "Location", "https://" + HOST + "/two");
            server.fixed("/two", 301, new byte[0], "Location", "https://" + HOST + "/three");
            server.fixed("/three", 301, new byte[0], "Location", "https://" + HOST + "/four");
            server.fixed("/four", 301, new byte[0], "Location", "https://" + HOST + "/five");

            assertThatThrownBy(() -> routed().page("https://" + HOST + "/one"))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.UPSTREAM_PERMANENT));
            assertThat(requested).hasSize(4);
        }

        @Test
        @DisplayName("a hop to another host is refused, and that host is never asked")
        void crossHost() {
            server.fixed("/one", 302, new byte[0], "Location", "https://elsewhere.test.example/jobs");

            assertThatThrownBy(() -> routed().page("https://" + HOST + "/one"))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.POLICY_BLOCK));
            assertThat(requested).containsExactly("https://" + HOST + "/one");
        }

        @Test
        @DisplayName("a hop to a private address is refused by the real validator")
        void privateAddress() {
            server.fixed("/one", 302, new byte[0], "Location", "https://127.0.0.1:9/secret");

            assertThatThrownBy(() -> direct().page(server.url("/one")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.POLICY_BLOCK));
        }

        @Test
        @DisplayName("a hop down to plain http is refused by the real validator")
        void httpsDowngrade() {
            server.fixed("/one", 302, new byte[0], "Location", "http://" + HOST + "/insecure");

            assertThatThrownBy(() -> direct().page(server.url("/one")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.POLICY_BLOCK));
        }

        @Test
        @DisplayName("a redirect that says nowhere is a permanent failure")
        void withoutLocation() {
            server.fixed("/one", 302, new byte[0]);

            assertThatThrownBy(() -> direct().page(server.url("/one")))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.UPSTREAM_PERMANENT));
        }
    }

    @Nested
    @DisplayName("pacing")
    class Pacing {

        @Test
        @DisplayName("a page request goes through the source's rate limiter, as a feed request does")
        void rateLimited() {
            server.fixed("/paced", 200, html("<p>first</p>"));
            SourceRateLimiter limiter = new SourceRateLimiter();
            RestClient client = RestClient.builder().requestFactory(loopback).build();
            ProbeAdapter adapter = new ProbeAdapter(client, new SafeUrlValidator(), limiter);
            UUID sourceId = UUID.randomUUID();
            SourceConfiguration once = new SourceConfiguration(sourceId, "Paced", server.url("/paced"),
                    "test", "test", 1, null, 200);

            // One request a minute: the first goes, the second is declined rather than sent.
            assertThat(adapter.pageFor(once)).isNotNull();
            assertThatThrownBy(() -> adapter.pageFor(once))
                    .isInstanceOf(AdapterException.class)
                    .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                            .isEqualTo(FailureClassification.NOT_ATTEMPTED));
            assertThat(server.requestCount("/paced")).isEqualTo(1);
        }
    }
}
