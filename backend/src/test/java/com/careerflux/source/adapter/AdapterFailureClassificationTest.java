package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * What the adapter decides a failure was.
 *
 * <p>Separate from the health-service tests, which start from an already
 * classified result. These start from the wire: a canned status or a transport
 * that refuses to connect, driven through a real adapter. The distinction
 * matters — a mutation that made transport failures permanent survived the
 * health tests entirely, because those never went through this code path.
 */
class AdapterFailureClassificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SourceRateLimiter permissive = new SourceRateLimiter();

    private SourceConfiguration configuration() {
        // A generous rate so pacing never interferes with what is under test.
        return new SourceConfiguration(UUID.randomUUID(), "Test board",
                "https://boards-api.greenhouse.io/v1/boards/test/jobs", "test",
                GreenhouseAdapter.KEY, 6000, null, 200);
    }

    private GreenhouseAdapter adapterOver(ClientHttpRequestFactory factory) {
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return new GreenhouseAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
    }

    /** A transport that never connects. */
    private static ClientHttpRequestFactory refusingTransport(IOException failure) {
        return (uri, method) -> {
            throw failure;
        };
    }

    /** A transport that answers with a canned status, headers and body. */
    private static ClientHttpRequestFactory cannedResponse(int status, HttpHeaders headers, String body) {
        return (uri, method) -> new StubRequest(uri, method, status, headers, body);
    }

    private AdapterException failureFrom(ClientHttpRequestFactory factory) {
        GreenhouseAdapter adapter = adapterOver(factory);
        return (AdapterException) org.assertj.core.api.Assertions
                .catchThrowable(() -> adapter.fetchJobs(configuration()));
    }

    @Nested
    @DisplayName("the transport itself fails")
    class Transport {

        @Test
        @DisplayName("a connection failure is transient, not a dead source")
        void connectionFailure() {
            AdapterException failure = failureFrom(
                    refusingTransport(new java.net.ConnectException("Connection refused")));

            assertThat(failure.getClassification())
                    .describedAs("a host that refused one connection has not gone away")
                    .isEqualTo(FailureClassification.UPSTREAM_TRANSIENT);
        }

        @Test
        @DisplayName("a read timeout is transient")
        void readTimeout() {
            AdapterException failure = failureFrom(
                    refusingTransport(new java.net.SocketTimeoutException("Read timed out")));

            assertThat(failure.getClassification()).isEqualTo(FailureClassification.UPSTREAM_TRANSIENT);
        }

        @Test
        @DisplayName("an unknown host is transient too, and carries no status")
        void unknownHost() {
            AdapterException failure = failureFrom(
                    refusingTransport(new java.net.UnknownHostException("no such host")));

            assertThat(failure.getClassification()).isEqualTo(FailureClassification.UPSTREAM_TRANSIENT);
            assertThat(failure.getHttpStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("the source answers with a status")
    class Statuses {

        @Test
        @DisplayName("429 is throttling, and the stated wait is read")
        void throttled() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", "90");
            AdapterException failure = failureFrom(cannedResponse(429, headers, "slow down"));

            assertThat(failure.getClassification()).isEqualTo(FailureClassification.UPSTREAM_THROTTLED);
            assertThat(failure.getRetryAfter()).isEqualTo(java.time.Duration.ofSeconds(90));
        }

        @Test
        @DisplayName("429 without a Retry-After still backs off by a bounded default")
        void throttledWithoutHeader() {
            AdapterException failure = failureFrom(cannedResponse(429, new HttpHeaders(), "slow down"));

            assertThat(failure.getClassification()).isEqualTo(FailureClassification.UPSTREAM_THROTTLED);
            assertThat(failure.getRetryAfter()).isEqualTo(RetryAfterParser.DEFAULT_BACKOFF);
        }

        @Test
        @DisplayName("429 with an absurd Retry-After is capped, never obeyed")
        void throttledWithAbsurdHeader() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", "31536000");
            AdapterException failure = failureFrom(cannedResponse(429, headers, "slow down"));

            assertThat(failure.getRetryAfter())
                    .describedAs("a source must not be able to switch itself off")
                    .isEqualTo(RetryAfterParser.MAX_RETRY_AFTER);
        }

        @Test
        @DisplayName("429 with a malformed Retry-After does not throw")
        void throttledWithMalformedHeader() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", "whenever you like");
            AdapterException failure = failureFrom(cannedResponse(429, headers, "slow down"));

            assertThat(failure.getClassification()).isEqualTo(FailureClassification.UPSTREAM_THROTTLED);
            assertThat(failure.getRetryAfter()).isEqualTo(RetryAfterParser.DEFAULT_BACKOFF);
        }

        @Test
        @DisplayName("5xx is transient")
        void serverErrors() {
            for (int status : new int[] {500, 502, 503, 504}) {
                assertThat(failureFrom(cannedResponse(status, new HttpHeaders(), "boom"))
                        .getClassification())
                        .describedAs("HTTP %d", status)
                        .isEqualTo(FailureClassification.UPSTREAM_TRANSIENT);
            }
        }

        @Test
        @DisplayName("408 is transient, because it is a timeout the server noticed first")
        void requestTimeout() {
            assertThat(failureFrom(cannedResponse(408, new HttpHeaders(), "too slow"))
                    .getClassification())
                    .isEqualTo(FailureClassification.UPSTREAM_TRANSIENT);
        }

        @Test
        @DisplayName("the permanent 4xx are permanent")
        void permanentClientErrors() {
            for (int status : new int[] {400, 401, 403, 404, 409, 410}) {
                assertThat(failureFrom(cannedResponse(status, new HttpHeaders(), "no"))
                        .getClassification())
                        .describedAs("HTTP %d", status)
                        .isEqualTo(FailureClassification.UPSTREAM_PERMANENT);
            }
        }
    }

    @Nested
    @DisplayName("the body is unusable")
    class Bodies {

        @Test
        @DisplayName("an empty 200 is not a success")
        void emptyBody() {
            assertThat(failureFrom(cannedResponse(200, new HttpHeaders(), ""))
                    .getClassification())
                    .isEqualTo(FailureClassification.EMPTY_RESPONSE);
        }

        @Test
        @DisplayName("a 200 that is not JSON is a provider problem, not a transient one")
        void notJson() {
            // Permanent on purpose: a schema change does not fix itself, and
            // retrying forever hides that an adapter needs updating.
            assertThat(failureFrom(cannedResponse(200, new HttpHeaders(), "<html>nope</html>"))
                    .getClassification())
                    .isEqualTo(FailureClassification.MALFORMED_RESPONSE);
        }
    }

    @Nested
    @DisplayName("our own pacing")
    class Pacing {

        @Test
        @DisplayName("a saturated limiter is not attempted, not a failure")
        void saturated() {
            SourceRateLimiter limiter = new SourceRateLimiter();
            UUID sourceId = UUID.randomUUID();
            // One permit per minute, consumed, so the next call is refused
            // rather than waited out.
            limiter.acquire(sourceId, 1, null);
            SourceConfiguration paced = new SourceConfiguration(sourceId, "Test board",
                    "https://boards-api.greenhouse.io/v1/boards/test/jobs", "test",
                    GreenhouseAdapter.KEY, 1, null, 200);

            GreenhouseAdapter adapter = new GreenhouseAdapter(
                    RestClient.builder().requestFactory(refusingTransport(
                            new IOException("should never be reached"))).build(),
                    limiter, new SafeUrlValidator(), objectMapper);

            assertThatThrownBy(() -> adapter.fetchJobs(paced))
                    .isInstanceOfSatisfying(AdapterException.class, ex ->
                            assertThat(ex.getClassification())
                                    .describedAs("declining to call says nothing about the source")
                                    .isEqualTo(FailureClassification.NOT_ATTEMPTED));
        }
    }

    // ------------------------------------------------------------- the stub

    /** Minimal request/response pair; enough for the adapter's read path. */
    private static final class StubRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final int status;
        private final HttpHeaders responseHeaders;
        private final String body;
        private final HttpHeaders requestHeaders = new HttpHeaders();

        StubRequest(URI uri, HttpMethod method, int status, HttpHeaders responseHeaders, String body) {
            this.uri = uri;
            this.method = method;
            this.status = status;
            this.responseHeaders = responseHeaders;
            this.body = body;
        }

        @Override
        public ClientHttpResponse execute() {
            return new ClientHttpResponse() {
                @Override
                public HttpStatusCode getStatusCode() {
                    return HttpStatusCode.valueOf(status);
                }

                @Override
                public String getStatusText() {
                    return String.valueOf(status);
                }

                @Override
                public void close() {
                }

                @Override
                public InputStream getBody() {
                    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public HttpHeaders getHeaders() {
                    return responseHeaders;
                }
            };
        }

        @Override
        public java.io.OutputStream getBody() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return requestHeaders;
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return new java.util.HashMap<>();
        }
    }
}
