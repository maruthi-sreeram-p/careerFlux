package com.careerflux.source.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;

import com.careerflux.common.error.UnsafeUrlException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/**
 * The outbound guard as a property of the transport, not of the caller.
 *
 * <p>{@code SafeUrlValidatorTest} proves the rules. This proves they are
 * actually reached: that a client built the ordinary way cannot issue a request
 * nobody checked, and that a caller who never heard of the validator is still
 * covered. The distinction matters because the finding this closes was not a
 * wrong rule — it was a correct rule that a future call site could skip.
 */
class OutboundHardeningTest {

    private final SafeUrlValidator validator = new SafeUrlValidator();

    @Nested
    @DisplayName("the validating transport")
    class Transport {

        private final SafeClientHttpRequestFactory factory =
                new SafeClientHttpRequestFactory(validator);

        @ParameterizedTest(name = "{0}")
        @DisplayName("refuses to even create a request to a private address")
        @ValueSource(strings = {
                "https://127.0.0.1/jobs",
                "https://localhost/jobs",
                "https://10.0.0.1/jobs",
                "https://192.168.1.1/jobs",
                "https://169.254.169.254/latest/meta-data/",
                "https://[::1]/jobs",
                "https://[::ffff:127.0.0.1]/jobs",
                "https://[64:ff9b::7f00:1]/jobs",
                "https://[fc00::1]/jobs",
                "https://100.64.0.1/jobs",
                "http://example.com/jobs",
                "https://example.com:8080/jobs",
                "https://example.com@127.0.0.1/jobs",
        })
        void refusesUnsafeTargets(String url) {
            // Failing at createRequest means no socket is opened at all: the
            // check happens before the connection, not after.
            assertThatThrownBy(() -> factory.createRequest(URI.create(url), HttpMethod.GET))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("allows a public https endpoint")
        void allowsPublicHttps() throws IOException {
            var request = factory.createRequest(
                    URI.create("https://boards-api.greenhouse.io/v1/boards/acme/jobs"),
                    HttpMethod.GET);
            assertThat(request).isNotNull();
            assertThat(request.getURI().getHost()).isEqualTo("boards-api.greenhouse.io");
        }

        @Test
        @DisplayName("never follows a redirect on its own")
        void doesNotFollowRedirects() throws Exception {
            // If the connection followed 3xx itself, the hop would be invisible
            // to every check above it — a public host could redirect us straight
            // to a metadata endpoint. Hops are taken explicitly instead.
            var request = factory.createRequest(
                    URI.create("https://boards-api.greenhouse.io/v1/boards/acme/jobs"),
                    HttpMethod.GET);
            var connectionField = request.getClass().getDeclaredField("connection");
            connectionField.setAccessible(true);
            var connection = (java.net.HttpURLConnection) connectionField.get(request);
            assertThat(connection.getInstanceFollowRedirects())
                    .describedAs("the transport must not follow redirects unseen")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("a client built the ordinary way")
    class BuiltByDefault {

        @Test
        @DisplayName("is protected without the author doing anything")
        void customizerProtectsPlainBuilders() {
            // Stands in for the Spring-applied customizer: a component that
            // injects RestClient.Builder and calls build() gets this transport.
            // Before, it would have got an unguarded one and nothing would fail.
            RestClient client = RestClient.builder()
                    .requestFactory(new SafeClientHttpRequestFactory(validator))
                    .build();

            assertThatThrownBy(() -> client.get()
                    .uri("https://169.254.169.254/latest/meta-data/")
                    .retrieve()
                    .toEntity(String.class))
                    .isInstanceOf(UnsafeUrlException.class);
        }
    }

    @Nested
    @DisplayName("redirect hops")
    class Redirects {

        @Test
        @DisplayName("a hop into a private address is refused")
        void privateHopRefused() {
            assertThatThrownBy(() -> SafeRedirects.resolve(
                    "https://example.com/jobs", "https://10.0.0.1/jobs", validator))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("a hop is revalidated even when it stays on the same host")
        void sameHostHopRevalidated() {
            // Relative hops are cheap to wave through and must not be: the DNS
            // answer behind the host can have changed since the first request.
            var target = SafeRedirects.resolve(
                    "https://boards-api.greenhouse.io/v1/boards/acme",
                    "/v1/boards/acme/jobs", validator);
            assertThat(target.toString())
                    .isEqualTo("https://boards-api.greenhouse.io/v1/boards/acme/jobs");
        }
    }

    @Nested
    @DisplayName("the Phase 13 protections, still in force")
    class StillProtected {

        @ParameterizedTest(name = "{0}")
        @DisplayName("every category the guard was built for")
        @ValueSource(strings = {
                "https://127.0.0.1/x",              // loopback
                "https://10.1.2.3/x",               // private
                "https://169.254.169.254/x",        // link-local + metadata
                "https://100.100.100.200/x",        // Alibaba metadata
                "https://[::ffff:10.0.0.1]/x",      // v4-mapped
                "https://[64:ff9b::a00:1]/x",       // NAT64
                "https://[fd12::1]/x",              // unique local
                "http://example.com/x",             // scheme
                "https://example.com:9200/x",       // port
                "https://user:pw@example.com/x",    // credentials
        })
        void categoriesRemainBlocked(String url) {
            assertThat(validator.isSafe(url))
                    .describedAs("%s must remain blocked", url)
                    .isFalse();
        }
    }
}
