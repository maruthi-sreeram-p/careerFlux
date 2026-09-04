package com.careerflux.source.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careerflux.common.error.UnsafeUrlException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF boundary.
 *
 * <p>This is the one class in CareerFlux where a missed case has a
 * disproportionate consequence: the server fetches operator-supplied URLs, so a
 * gap here is not a wrong number on a screen, it is the platform reading its own
 * private network — a container's neighbours, an orchestrator API, or a cloud
 * metadata endpoint that hands out instance credentials to whoever asks.
 *
 * <p>Tests are written against literal addresses wherever possible so they do
 * not need DNS and cannot pass for the wrong reason. The few that must resolve a
 * name use {@code localhost}, which every machine resolves to a loopback
 * address, giving a genuine "name resolves to a private address" case without
 * depending on the network.
 */
class SafeUrlValidatorTest {

    private final SafeUrlValidator validator = new SafeUrlValidator();

    @Nested
    @DisplayName("addresses that must never be fetched")
    class Refused {

        @ParameterizedTest(name = "{0}")
        @DisplayName("loopback, in every spelling")
        @ValueSource(strings = {
                "https://127.0.0.1/jobs",
                "https://127.0.0.2/jobs",
                "https://127.255.255.254/jobs",
                "https://localhost/jobs",
                "https://[::1]/jobs",
                "https://[0:0:0:0:0:0:0:1]/jobs",
        })
        void loopback(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("private IPv4 ranges")
        @ValueSource(strings = {
                "https://10.0.0.1/jobs",
                "https://10.255.255.255/jobs",
                "https://172.16.0.1/jobs",
                "https://172.31.255.255/jobs",
                "https://192.168.0.1/jobs",
                "https://192.168.1.1/jobs",
        })
        void privateV4(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("private and reserved IPv6")
        @ValueSource(strings = {
                "https://[fc00::1]/jobs",          // unique local
                "https://[fd12:3456:789a::1]/jobs",// unique local, the common form
                "https://[fe80::1]/jobs",          // link-local
                "https://[::]/jobs",               // wildcard
        })
        void privateV6(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("link-local, including the cloud metadata endpoints")
        @ValueSource(strings = {
                "https://169.254.169.254/latest/meta-data/",
                "https://169.254.170.2/v2/credentials/",
                "https://169.254.1.1/jobs",
                "https://100.100.100.200/latest/meta-data/",
                "https://192.0.0.192/opc/v1/instance/",
                "https://[fd00:ec2::254]/latest/meta-data/",
        })
        void metadataAndLinkLocal(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("IPv4 smuggled inside an IPv6 form")
        @ValueSource(strings = {
                "https://[::ffff:127.0.0.1]/jobs",   // v4-mapped loopback
                "https://[::ffff:10.0.0.1]/jobs",    // v4-mapped private
                "https://[::ffff:169.254.169.254]/", // v4-mapped metadata
                "https://[::ffff:7f00:1]/jobs",      // the same, written in hex
                "https://[64:ff9b::7f00:1]/jobs",    // NAT64 wrapping loopback
                "https://[64:ff9b::a00:1]/jobs",     // NAT64 wrapping 10.0.0.1
        })
        void v4InsideV6(String url) {
            // The trick these exercise: a string blocklist sees "no 127.0.0.1
            // here" and lets it through, while the socket goes straight to
            // loopback. Only checking the resolved address catches them.
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("other ranges that are never a job board")
        @ValueSource(strings = {
                "https://0.0.0.0/jobs",
                "https://0.1.2.3/jobs",
                "https://100.64.0.1/jobs",     // carrier-grade NAT
                "https://100.127.255.255/jobs",
                "https://240.0.0.1/jobs",      // reserved
                "https://255.255.255.255/jobs",
                "https://224.0.0.1/jobs",      // multicast
                "https://192.0.2.1/jobs",      // documentation
                "https://198.51.100.1/jobs",
                "https://203.0.113.1/jobs",
        })
        void otherReserved(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("a hostname whose DNS answer is a private address")
        void dnsResolvingToPrivate() {
            // "localhost" is the dependable case: it is a name, not a literal,
            // and every machine answers it with a loopback address. A blocklist
            // of literals would not stop an attacker-controlled name pointed at
            // 127.0.0.1 either, which is the case this stands in for.
            assertThatThrownBy(() -> validator.validate("https://localhost/careers"))
                    .isInstanceOf(UnsafeUrlException.class)
                    .hasMessageContaining("not publicly routable");
        }
    }

    @Nested
    @DisplayName("schemes, ports and shapes")
    class Shape {

        @ParameterizedTest(name = "{0}")
        @DisplayName("only https is fetched")
        @ValueSource(strings = {
                "http://example.com/jobs",
                "file:///etc/passwd",
                "ftp://example.com/jobs",
                "gopher://example.com/",
                "jar:https://example.com/a.jar!/b",
                "data:text/html,<h1>hi</h1>",
                "javascript:alert(1)",
        })
        void unsupportedSchemes(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("only the standard TLS port")
        @ValueSource(strings = {
                "https://example.com:8080/jobs",
                "https://example.com:22/jobs",
                "https://example.com:6379/jobs",
                "https://example.com:5432/jobs",
        })
        void nonStandardPorts(String url) {
            // A public job board is on 443. Everything else is an internal
            // service, and the interesting ones are exactly the ones an SSRF is
            // aimed at: a database, a cache, an admin port.
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("credentials in the URL are refused")
        void embeddedCredentials() {
            // Also the classic parser-confusion payload: everything before the
            // @ is userinfo, so this points at the second host, not the first.
            assertThatThrownBy(() ->
                    validator.validate("https://example.com@127.0.0.1/jobs"))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("malformed input is refused rather than guessed at")
        @ValueSource(strings = {
                "not a url",
                "https://",
                "//example.com/jobs",
                "/careers",
                "https:// example.com/jobs",
                "https://exa mple.com/jobs",
        })
        void malformed(String url) {
            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("null and blank are refused")
        void nullAndBlank() {
            assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(UnsafeUrlException.class);
            assertThatThrownBy(() -> validator.validate("")).isInstanceOf(UnsafeUrlException.class);
            assertThatThrownBy(() -> validator.validate("   ")).isInstanceOf(UnsafeUrlException.class);
        }
    }

    @Nested
    @DisplayName("the sources CareerFlux actually reads")
    class Allowed {

        @ParameterizedTest(name = "{0}")
        @DisplayName("the live ATS endpoints still pass")
        @ValueSource(strings = {
                "https://boards-api.greenhouse.io/v1/boards/acme/jobs",
                "https://api.lever.co/v0/postings/acme?mode=json",
                "https://api.ashbyhq.com/posting-api/job-board/acme",
                "https://example.com/careers",
                "https://example.com:443/careers",
        })
        void publicHttpsIsAllowed(String url) {
            // The point of the whole exercise: the guard must not break the 22
            // sources already in the registry. These resolve to public
            // addresses, so they must pass.
            assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the parsed URL comes back for the caller to use")
        void returnsTheParsedUri() {
            var uri = validator.validate("https://boards-api.greenhouse.io/v1/boards/acme/jobs");
            assertThat(uri.getHost()).isEqualTo("boards-api.greenhouse.io");
            assertThat(uri.getPath()).isEqualTo("/v1/boards/acme/jobs");
        }

        @Test
        @DisplayName("isSafe answers without throwing, for call sites that branch")
        void isSafeDoesNotThrow() {
            assertThat(validator.isSafe("https://example.com/careers")).isTrue();
            assertThat(validator.isSafe("https://127.0.0.1/careers")).isFalse();
            assertThat(validator.isSafe("nonsense")).isFalse();
        }
    }

    @Nested
    @DisplayName("what the refusal tells the caller")
    class Disclosure {

        @Test
        @DisplayName("the category is named but the address is not")
        void doesNotLeakTheResolvedAddress() {
            // Echoing what a hostname resolved to would turn this endpoint into
            // a way to map the network CareerFlux runs in — the SSRF payoff,
            // reached through the error message instead of the fetch.
            assertThatThrownBy(() -> validator.validate("https://localhost/careers"))
                    .isInstanceOf(UnsafeUrlException.class)
                    .hasMessageNotContaining("127.0.0.1")
                    .hasMessageNotContaining("::1");
        }

        @Test
        @DisplayName("an unresolvable host is not distinguished from a refused one in detail")
        void unresolvableHost() {
            assertThatThrownBy(() ->
                    validator.validate("https://this-host-does-not-exist.careerflux-test.invalid/x"))
                    .isInstanceOf(UnsafeUrlException.class)
                    .hasMessageContaining("could not be resolved");
        }
    }

    @Nested
    @DisplayName("a host that answers with more than one address")
    class MultipleRecords {

        private java.net.InetAddress at(String literal) throws Exception {
            return java.net.InetAddress.getByName(literal);
        }

        @Test
        @DisplayName("all-public is allowed")
        void allPublic() throws Exception {
            assertThatCode(() -> validator.requireAllPublic("cdn.example.com",
                    at("93.184.216.34"), at("93.184.216.35")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("one private address among public ones refuses the whole host")
        void mixedAnswerIsRefused() throws Exception {
            // The cheap version of DNS rebinding: answer with a public record so
            // a first-record check passes, and a private one for the connection
            // to land on. Whichever address the socket picks is out of our
            // hands, so a mixed answer has no safe reading.
            assertThatThrownBy(() -> validator.requireAllPublic("rebind.example.com",
                    at("93.184.216.34"), at("127.0.0.1")))
                    .isInstanceOf(UnsafeUrlException.class);
            assertThatThrownBy(() -> validator.requireAllPublic("rebind.example.com",
                    at("93.184.216.34"), at("169.254.169.254")))
                    .isInstanceOf(UnsafeUrlException.class);
            assertThatThrownBy(() -> validator.requireAllPublic("rebind.example.com",
                    at("93.184.216.34"), at("10.1.2.3")))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("the private address is refused wherever it sits in the answer")
        void orderDoesNotMatter() throws Exception {
            // Guards against checking only the first record, which would pass
            // this and fail the case above.
            assertThatThrownBy(() -> validator.requireAllPublic("rebind.example.com",
                    at("127.0.0.1"), at("93.184.216.34")))
                    .isInstanceOf(UnsafeUrlException.class);
            assertThatThrownBy(() -> validator.requireAllPublic("rebind.example.com",
                    at("93.184.216.34"), at("93.184.216.35"), at("192.168.1.1")))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("an empty answer is refused rather than treated as fine")
        void emptyAnswer() {
            assertThatThrownBy(() -> validator.requireAllPublic("nothing.example.com"))
                    .isInstanceOf(UnsafeUrlException.class);
        }
    }

    @Nested
    @DisplayName("redirect hops")
    class Redirects {

        @Test
        @DisplayName("a redirect to a private address is refused")
        void redirectToPrivate() {
            assertThatThrownBy(() -> SafeRedirects.resolve(
                    "https://example.com/careers", "https://169.254.169.254/latest/", validator))
                    .isInstanceOf(UnsafeUrlException.class);
            assertThatThrownBy(() -> SafeRedirects.resolve(
                    "https://example.com/careers", "http://127.0.0.1/", validator))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("a relative hop stays on the host and is still validated")
        void relativeRedirect() {
            var target = SafeRedirects.resolve(
                    "https://example.com/careers", "/careers/", validator);
            assertThat(target.toString()).isEqualTo("https://example.com/careers/");
        }

        @Test
        @DisplayName("an absolute hop to another public host is allowed")
        void absoluteRedirect() {
            var target = SafeRedirects.resolve(
                    "https://example.com/careers", "https://boards-api.greenhouse.io/v1/boards/a/jobs",
                    validator);
            assertThat(target.getHost()).isEqualTo("boards-api.greenhouse.io");
        }

        @Test
        @DisplayName("a downgrade to http is refused even from a public host")
        void refusesDowngrade() {
            assertThatThrownBy(() -> SafeRedirects.resolve(
                    "https://example.com/careers", "http://example.com/careers/", validator))
                    .isInstanceOf(UnsafeUrlException.class);
        }

        @Test
        @DisplayName("a malformed Location is refused rather than guessed at")
        void malformedLocation() {
            assertThatThrownBy(() -> SafeRedirects.resolve(
                    "https://example.com/careers", "ht tp://%%%", validator))
                    .isInstanceOf(UnsafeUrlException.class);
        }
    }
}
