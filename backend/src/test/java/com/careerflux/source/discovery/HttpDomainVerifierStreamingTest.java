package com.careerflux.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.careerflux.common.error.UnsafeUrlException;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.support.StreamingHttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The home-page read is bounded while it happens (Phase 1 follow-up).
 *
 * <p>Before this, the verifier read a candidate's whole page into memory and
 * only then cut it to the ceiling, so a host streaming an endless page held the
 * heap for as long as it kept sending. These tests serve real chunked responses
 * over loopback and measure what the server managed to send and whether the
 * client hung up, so "stopped reading" and "read everything, then cut" come out
 * differently.
 *
 * <p>Nothing here prints a body: every assertion is on a length, a flag or a
 * verdict, so a failure cannot flood the test output.
 */
class HttpDomainVerifierStreamingTest {

    private static final int LIMIT = HttpDomainVerifier.MAX_BODY_BYTES;

    /** Far past the ceiling, and still small enough that no failure could fill a disk. */
    private static final long LARGE = 32L * 1024 * 1024;

    /** How far socket buffers let a server write ahead of a reader that has stopped. */
    private static final long BUFFER_SLACK = 4L * 1024 * 1024;

    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    private static final String HOST = "acme-verify.example";

    private StreamingHttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private HttpDomainVerifier verifier;

    /**
     * The real validator resolves hosts through DNS, which a test must not need.
     * This one allows exactly one https host and refuses every other, the way the
     * real one refuses a private address.
     */
    private static final class OnlyTheTestHost extends SafeUrlValidator {
        @Override
        public URI validate(String rawUrl) {
            URI uri = URI.create(rawUrl);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
                throw new UnsafeUrlException("Refused in this test: " + uri.getHost());
            }
            return uri;
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = new StreamingHttpServer();
        SimpleClientHttpRequestFactory loopback = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                    throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        loopback.setConnectTimeout(Duration.ofSeconds(5));
        loopback.setReadTimeout(Duration.ofSeconds(5));
        // The verifier asks for https://<domain>/…; each request goes to the
        // loopback server at the same path, so redirects behave as they would.
        ClientHttpRequestFactory routed = (uri, method) -> {
            requested.add(uri.toString());
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return loopback.createRequest(URI.create(server.url(path)), method);
        };
        verifier = new HttpDomainVerifier(RestClient.builder().requestFactory(routed).build(), new OnlyTheTestHost());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static byte[] html(String title, String body) {
        return ("<!doctype html><html><head><title>" + title + "</title></head><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** {@code total} bytes of filler whose last bytes are {@code tail}. */
    private static byte[] endingWith(String tail, int total) {
        byte[] end = tail.getBytes(StandardCharsets.UTF_8);
        byte[] page = new byte[total];
        Arrays.fill(page, (byte) 'x');
        System.arraycopy(end, 0, page, total - end.length, end.length);
        return page;
    }

    private Optional<CompanyResolver.Candidate> verify(String companyName) {
        return assertTimeoutPreemptively(Duration.ofSeconds(20), () -> verifier.verify(HOST, companyName));
    }

    @Nested
    @DisplayName("an ordinary home page")
    class Ordinary {

        @Test
        @DisplayName("is judged exactly as before: the title is the evidence when it names the company")
        void titleNamesTheCompany() {
            server.fixed("/", 200, html("AutoRABIT | Release management for Salesforce", "<h1>Welcome</h1>"));

            CompanyResolver.Candidate candidate = verify("AutoRABIT").orElseThrow();

            assertThat(candidate.domain()).isEqualTo(HOST);
            assertThat(candidate.evidence()).isEqualTo(CompanyResolver.Evidence.VERIFIED_SITE);
            assertThat(candidate.detail())
                    .isEqualTo("The site's title says \"AutoRABIT | Release management for Salesforce\".");
        }

        @Test
        @DisplayName("names the company in its markup when the title does not")
        void bodyNamesTheCompany() {
            server.fixed("/", 200, html("Home", "<footer>© 2026 AutoRABIT Inc., Hyderabad</footer>"));

            assertThat(verify("AutoRABIT").orElseThrow().detail())
                    .isEqualTo("The site's home page names the company.");
        }

        @Test
        @DisplayName("that belongs to someone else is not a candidate")
        void anotherCompany() {
            server.fixed("/", 200, html("Parked domain — this name is for sale", "Buy it today"));

            assertThat(verify("AutoRABIT")).isEmpty();
        }

        @Test
        @DisplayName("is still asked for as a web page")
        void acceptHeaderIsUnchanged() {
            server.fixed("/", 200, html("AutoRABIT", ""));

            verify("AutoRABIT");

            assertThat(server.requestHeader("/", "Accept")).isEqualTo("text/html, */*");
        }

        @Test
        @DisplayName("sent chunked, with no Content-Length, is judged the same way")
        void chunkedPage() {
            byte[] page = html("AutoRABIT | DevOps", "");
            server.page("/", 200, page, page.length);

            assertThat(verify("AutoRABIT")).isPresent();
        }

        @Test
        @DisplayName("that answers with an empty body is not a candidate")
        void emptyBody() {
            server.fixed("/", 200, new byte[0]);

            assertThat(verify("AutoRABIT")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the ceiling")
    class Ceiling {

        @Test
        @DisplayName("a page exactly at the ceiling is read whole: a name in its very last bytes counts")
        void exactlyAtTheCeiling() {
            byte[] page = endingWith("AutoRABIT", LIMIT);
            server.page("/", 200, page, page.length);

            assertThat(verify("AutoRABIT")).isPresent();
        }

        @Test
        @DisplayName("one byte over: the page is judged on its first bytes and the extra byte is never used")
        void oneByteOver() {
            // The name's last letter is the one byte past the ceiling.
            byte[] page = endingWith("AutoRABIT", LIMIT + 1);
            server.page("/", 200, page, page.length);

            assertThat(verify("AutoRABIT")).isEmpty();
        }

        @Test
        @DisplayName("a larger page is still judged on its first bytes, as it always was")
        void largePageNamedNearTheTop() {
            server.page("/", 200, html("AutoRABIT | Careers", ""), LARGE);

            assertThat(verify("AutoRABIT")).isPresent();
        }

        @Test
        @DisplayName("a name that only appears past the ceiling is never seen")
        void nameOnlyPastTheCeiling() {
            byte[] page = endingWith("AutoRABIT", LIMIT + 64 * 1024);
            server.fixed("/", 200, page);

            assertThat(verify("AutoRABIT")).isEmpty();
        }
    }

    @Nested
    @DisplayName("a page that will not end")
    class Endless {

        @Test
        @DisplayName("is read to the ceiling and no further, and the connection is closed at once")
        void endlessChunkedPage() throws InterruptedException {
            server.page("/", 200, html("AutoRABIT", ""), LARGE);

            assertThat(verify("AutoRABIT")).isPresent();

            assertThat(server.awaitFinished("/", PROMPTLY)).describedAs("server handler finished").isTrue();
            assertThat(server.sentEverything("/")).describedAs("server sent the whole 32 MiB").isFalse();
            assertThat(server.bytesSent("/")).describedAs("bytes the server managed to send")
                    .isLessThan(LIMIT + BUFFER_SLACK);
        }

        @Test
        @DisplayName("declaring a huge Content-Length changes nothing: only the ceiling is read")
        void declaredHugeLength() throws InterruptedException {
            byte[] huge = new byte[(int) LARGE];
            byte[] head = html("AutoRABIT", "");
            System.arraycopy(head, 0, huge, 0, head.length);
            server.fixed("/", 200, huge);

            assertThat(verify("AutoRABIT")).isPresent();

            assertThat(server.awaitFinished("/", PROMPTLY)).describedAs("server handler finished").isTrue();
            assertThat(server.sentEverything("/")).describedAs("server sent the whole declared body").isFalse();
        }

        @Test
        @DisplayName("an error page is not read at all, however long it runs")
        void endlessErrorPage() throws InterruptedException {
            server.page("/", 500, html("AutoRABIT", ""), LARGE);

            assertThat(verify("AutoRABIT")).isEmpty();

            assertThat(server.awaitFinished("/", PROMPTLY)).describedAs("server handler finished").isTrue();
            assertThat(server.sentEverything("/")).describedAs("server sent the whole error body").isFalse();
            assertThat(server.bytesSent("/")).describedAs("bytes the server managed to send")
                    .isLessThan(LIMIT);
        }
    }

    @Nested
    @DisplayName("redirects")
    class Redirects {

        @Test
        @DisplayName("are still followed one validated hop at a time")
        void validatedHopIsFollowed() {
            server.fixed("/", 301, new byte[0], "Location", "https://" + HOST + "/home");
            server.fixed("/home", 200, html("AutoRABIT", ""));

            assertThat(verify("AutoRABIT")).isPresent();
            assertThat(requested).containsExactly("https://" + HOST + "/", "https://" + HOST + "/home");
        }

        @Test
        @DisplayName("to a host the validator refuses are not followed, and that host is never asked")
        void refusedHopIsNotFollowed() {
            server.fixed("/", 302, new byte[0], "Location", "https://internal.example/admin");

            assertThat(verify("AutoRABIT")).isEmpty();
            assertThat(requested).containsExactly("https://" + HOST + "/");
        }
    }
}
