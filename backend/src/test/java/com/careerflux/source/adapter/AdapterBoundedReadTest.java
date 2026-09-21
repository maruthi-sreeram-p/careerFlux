package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.SourceRateLimiter;
import com.careerflux.support.StreamingHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The adapter read path over a real streaming connection (Phase 1).
 *
 * <p>Every adapter reads through {@code getJson}, so this is where the ceiling
 * has to hold for sync as well as discovery. An oversized board is classified as
 * it always was — a malformed response — but is now refused while it is still
 * arriving.
 */
class AdapterBoundedReadTest {

    /** Just enough of an adapter to call the shared read path. */
    private static final class ProbeAdapter extends AbstractHttpJobAdapter {

        ProbeAdapter(RestClient client) {
            super(client, new SourceRateLimiter(), new SafeUrlValidator(), new ObjectMapper());
        }

        FetchedJson read(String url) {
            return getJson(new SourceConfiguration(UUID.randomUUID(), "Test board", url, "test",
                    "test", 6000, null, 200), url);
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
            return body.path("jobs").size();
        }
    }

    private StreamingHttpServer server;
    private ProbeAdapter adapter;

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
        adapter = new ProbeAdapter(RestClient.builder().requestFactory(factory).build());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    @DisplayName("an ordinary JSON board is read and parsed as before")
    void ordinaryJson() {
        server.fixed("/jobs", 200, "{\"jobs\":[{\"id\":1},{\"id\":2}]}".getBytes());

        AbstractHttpJobAdapter.FetchedJson fetched = adapter.read(server.url("/jobs"));

        assertThat(fetched.httpStatus()).isEqualTo(200);
        assertThat(fetched.body().path("jobs").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("an endless chunked body is refused while arriving, and classified as a malformed response")
    void endlessBodyIsRefused() {
        server.endless("/jobs");

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThatThrownBy(() -> adapter.read(server.url("/jobs")))
                        .isInstanceOf(AdapterException.class)
                        .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                                .isEqualTo(FailureClassification.MALFORMED_RESPONSE)));
        // The ceiling is 8 MiB; a reader that kept going would be sent up to 128 MiB.
        assertThat(server.bytesSent("/jobs")).isLessThan(8L * 1024 * 1024 + 64L * 1024 * 1024);
    }

    @Test
    @DisplayName("a declared Content-Length over the ceiling is refused as before")
    void declaredOversizeIsRefused() {
        server.fixed("/jobs", 200, new byte[8 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> adapter.read(server.url("/jobs")))
                .isInstanceOf(AdapterException.class)
                .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                        .isEqualTo(FailureClassification.MALFORMED_RESPONSE));
    }

    @Test
    @DisplayName("an error status is still classified from the status, without reading its body")
    void errorStatusIsClassified() {
        server.endlessError("/jobs", 503);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThatThrownBy(() -> adapter.read(server.url("/jobs")))
                        .isInstanceOf(AdapterException.class)
                        .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                                .isEqualTo(FailureClassification.UPSTREAM_TRANSIENT)));
    }
}
