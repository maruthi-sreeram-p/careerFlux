package com.careerflux.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.careerflux.source.adapter.GreenhouseAdapter;
import com.careerflux.source.adapter.LeverAdapter;
import com.careerflux.source.discovery.AtsBoardProbe.DiscoveredBoard;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.support.StreamingHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Discovery against fixed pages, never the live web (Phase 1).
 *
 * <p>Every URL the probe asks for is recorded, so the tests can say not only
 * what was found but what was never requested: a board with no adapter, and a
 * link to a host nobody recognises, must both go unfetched.
 */
class AtsBoardProbeDiscoveryTest {

    /** Pages keyed by URL. Anything not listed answers "unreachable", as a real miss would. */
    private static final class Fixtures {
        final Map<String, String> pages = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        Fixtures page(String url, String body) {
            pages.put(url, body);
            return this;
        }

        AtsBoardProbe probe() {
            return new AtsBoardProbe(url -> {
                requested.add(url);
                return pages.get(url);
            }, new ObjectMapper());
        }

        List<String> requestedHosts() {
            return requested.stream().map(url -> URI.create(url).getHost()).distinct().toList();
        }
    }

    /**
     * The shape of AutoRABIT's careers page as it was when this was written: a
     * LinkedIn link and links to individual openings on its JazzHR board, and
     * nothing from Greenhouse, Lever or Ashby.
     */
    private static final String AUTORABIT_CAREERS = """
            <html><body>
              <h1>Careers at AutoRABIT</h1>
              <a href="https://www.linkedin.com/company/autorabit">Follow us</a>
              <ul class="openings">
                <li><a href="https://autorabit.applytojob.com/apply/pCgw7Isara/Software-Engineer">Software Engineer</a></li>
                <li><a href="https://autorabit.applytojob.com/apply/k9OP7EUpSh/QA-Engineer">QA Engineer</a></li>
              </ul>
            </body></html>
            """;

    @Nested
    @DisplayName("the AutoRABIT case")
    class AutoRabit {

        @Test
        @DisplayName("a JazzHR board linked from the careers page is found and recorded without an adapter")
        void jazzHrBoardIsRecorded() {
            Fixtures web = new Fixtures().page("https://autorabit.com/careers", AUTORABIT_CAREERS);

            Optional<DiscoveredBoard> found = web.probe().probe("https://www.autorabit.com/");

            assertThat(found).isPresent();
            DiscoveredBoard board = found.get();
            assertThat(board.provider()).isEqualTo(AtsProvider.JAZZHR);
            assertThat(board.boardToken()).isEqualTo("autorabit");
            assertThat(board.sourceUrl()).isEqualTo("https://autorabit.applytojob.com/apply");
            assertThat(board.adapterKey()).isNull();
            assertThat(board.ingestible()).isFalse();
            assertThat(board.howFound()).isEqualTo(DiscoveryMethod.DOMAIN_INSPECTION);
            assertThat(board.detail()).contains("Linked from https://autorabit.com/careers");
        }

        @Test
        @DisplayName("nothing is requested from the JazzHR board, or from any host but AutoRABIT's own")
        void theBoardIsNeverFetched() {
            Fixtures web = new Fixtures().page("https://autorabit.com/careers", AUTORABIT_CAREERS);

            web.probe().probe("autorabit.com");

            assertThat(web.requestedHosts()).containsExactly("autorabit.com");
        }
    }

    @Nested
    @DisplayName("boards CareerFlux can read")
    class Ingestible {

        @Test
        @DisplayName("a Greenhouse board linked from the careers page is confirmed through its API")
        void greenhouseByInspection() {
            Fixtures web = new Fixtures()
                    .page("https://acme.com/careers", "<a href=\"https://boards.greenhouse.io/acme\">Jobs</a>")
                    .page("https://boards-api.greenhouse.io/v1/boards/acme/jobs", "{\"jobs\":[{\"id\":1}]}");

            DiscoveredBoard board = web.probe().probe("acme.com").orElseThrow();

            assertThat(board.provider()).isEqualTo(AtsProvider.GREENHOUSE);
            assertThat(board.adapterKey()).isEqualTo(GreenhouseAdapter.KEY);
            assertThat(board.sourceUrl()).isEqualTo("https://boards-api.greenhouse.io/v1/boards/acme/jobs");
            assertThat(board.howFound()).isEqualTo(DiscoveryMethod.DOMAIN_INSPECTION);
        }

        @Test
        @DisplayName("a board CareerFlux can read wins over one it can only record, whichever is linked first")
        void readableBoardWins() {
            Fixtures web = new Fixtures()
                    .page("https://acme.com/careers", """
                            <a href="https://acme.applytojob.com/apply/x/Role">Role</a>
                            <a href="https://boards.greenhouse.io/acme">Board</a>""")
                    .page("https://boards-api.greenhouse.io/v1/boards/acme/jobs", "{\"jobs\":[{\"id\":1}]}");

            assertThat(web.probe().probe("acme.com").orElseThrow().provider()).isEqualTo(AtsProvider.GREENHOUSE);
        }

        @Test
        @DisplayName("an empty Greenhouse board is not a board; the recognised JazzHR link is recorded instead")
        void emptyApiBoardFallsBackToRecord() {
            Fixtures web = new Fixtures()
                    .page("https://acme.com/careers", """
                            <a href="https://boards.greenhouse.io/acme">Board</a>
                            <a href="https://acme.applytojob.com/apply">Jobs</a>""")
                    .page("https://boards-api.greenhouse.io/v1/boards/acme/jobs", "{\"jobs\":[]}");

            assertThat(web.probe().probe("acme.com").orElseThrow().provider()).isEqualTo(AtsProvider.JAZZHR);
        }

        @Test
        @DisplayName("a Lever board is still found by token when the careers page links nothing")
        void leverByTokenProbe() {
            Fixtures web = new Fixtures()
                    .page("https://api.lever.co/v0/postings/acme?mode=json&limit=1", "[{\"id\":\"1\"}]");

            DiscoveredBoard board = web.probe().probe("acme.com").orElseThrow();

            assertThat(board.provider()).isEqualTo(AtsProvider.LEVER);
            assertThat(board.adapterKey()).isEqualTo(LeverAdapter.KEY);
            assertThat(board.howFound()).isEqualTo(DiscoveryMethod.ATS_PROBE);
        }

        @Test
        @DisplayName("an Ashby board is still found by token")
        void ashbyByTokenProbe() {
            Fixtures web = new Fixtures()
                    .page("https://api.ashbyhq.com/posting-api/job-board/acme", "{\"jobs\":[{\"id\":\"1\"}]}");

            assertThat(web.probe().probe("acme.com").orElseThrow().provider()).isEqualTo(AtsProvider.ASHBY);
        }
    }

    @Nested
    @DisplayName("what discovery will not do")
    class Boundaries {

        @Test
        @DisplayName("a link to a board host nobody recognises is neither followed nor recorded")
        void unknownBoardIsIgnored() {
            Fixtures web = new Fixtures().page("https://acme.com/careers", """
                    <a href="https://jobs.unknown-ats.example/acme">Open roles</a>
                    <a href="https://careers.someotherboard.io/acme/openings">More</a>""");

            assertThat(web.probe().probe("acme.com")).isEmpty();
            assertThat(web.requestedHosts())
                    .doesNotContain("jobs.unknown-ats.example", "careers.someotherboard.io")
                    // Only the company's own host and the documented public APIs.
                    .allMatch(host -> host.equals("acme.com") || host.equals("boards-api.greenhouse.io")
                            || host.equals("api.lever.co") || host.equals("api.ashbyhq.com"));
        }

        @Test
        @DisplayName("a board with no adapter is never probed by token")
        void recordOnlyFamiliesAreNeverProbed() {
            Fixtures web = new Fixtures();

            assertThat(web.probe().probe("acme.com")).isEmpty();
            assertThat(web.requestedHosts())
                    .noneMatch(host -> host.endsWith("applytojob.com") || host.endsWith("myworkdayjobs.com")
                            || host.endsWith("icims.com"));
        }

        @Test
        @DisplayName("only the company's own host is fetched as a careers page")
        void careersPagesStayOnTheCompanyHost() {
            Fixtures web = new Fixtures();

            web.probe().probe("acme.com");

            assertThat(web.requested.stream().filter(url -> !url.contains("api")))
                    .allMatch(url -> url.startsWith("https://acme.com/") || url.equals("https://acme.com"));
        }
    }

    @Nested
    @DisplayName("fetching a careers page")
    class Fetching {

        private RestClient loopbackClient() {
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
            return RestClient.builder().requestFactory(factory).build();
        }

        @Test
        @DisplayName("an ordinary page is returned as text")
        void ordinaryPage() throws IOException {
            try (StreamingHttpServer server = new StreamingHttpServer()) {
                server.fixed("/careers", 200, AUTORABIT_CAREERS.getBytes());
                String text = AtsBoardProbe.fetchText(loopbackClient(), new SafeUrlValidator(),
                        server.url("/careers"));
                assertThat(text).contains("autorabit.applytojob.com");
            }
        }

        @Test
        @DisplayName("an endless page is abandoned at the ceiling and treated as no page, not read to the end")
        void endlessPageIsAbandoned() throws IOException {
            try (StreamingHttpServer server = new StreamingHttpServer()) {
                server.endless("/careers");
                String text = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                        AtsBoardProbe.fetchText(loopbackClient(), new SafeUrlValidator(), server.url("/careers")));
                // Measured, not printed: were this ever to fail, the page itself
                // would be the failure message.
                assertThat(text == null ? 0 : text.length()).describedAs("characters kept from an endless page")
                        .isZero();
                assertThat(server.bytesSent("/careers"))
                        .isLessThan(AtsBoardProbe.MAX_BODY_BYTES + 64L * 1024 * 1024);
            }
        }
    }
}
