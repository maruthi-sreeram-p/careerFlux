package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.SourceHealthStatus;
import com.careerflux.source.domain.SourceType;
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
 * Reading a careers page through its JobPosting markup (Phase 2, stage 4).
 *
 * <p>The fixture is written in the shape a JazzHR-hosted careers page publishes:
 * several postings in separate blocks, no {@code identifier}, a date-only
 * {@code datePosted}, {@code employmentType} as an array, and an address whose
 * country is spelled out. The postings themselves are invented.
 *
 * <p>Nothing here asserts on a whole body. Failures report counts and single
 * fields, so a broken expectation cannot flood the output.
 */
class JobPostingPageAdapterTest {

    private StreamingHttpServer server;
    private SimpleClientHttpRequestFactory loopback;

    private static final String POSTING_ONE = """
            {"@context":"https://schema.org",
             "@type":"JobPosting",
             "title":"Associate Project Manager",
             "description":"<p>Plan delivery for the platform team.</p>",
             "datePosted":"2026-02-02",
             "employmentType":["FULL_TIME"],
             "hiringOrganization":{"@type":"Organization","name":"Northgate Systems"},
             "jobLocation":{"@type":"Place","address":{"@type":"PostalAddress",
                 "addressLocality":"Hyderabad","addressRegion":"Telangana",
                 "addressCountry":"India"}},
             "url":"__BASE__/apply/4EpNpkSTON/Associate-Project-Manager"}
            """;

    private static final String POSTING_TWO = """
            {"@context":"https://schema.org",
             "@type":"JobPosting",
             "title":"Cloud Architect",
             "description":"<p>Design the cloud platform.</p>",
             "datePosted":"2026-02-02",
             "employmentType":["FULL_TIME"],
             "hiringOrganization":{"@type":"Organization","name":"Northgate Systems"},
             "jobLocation":{"@type":"Place","address":{"@type":"PostalAddress",
                 "addressLocality":"Bengaluru","addressCountry":"India"}},
             "url":"__BASE__/apply/ByksQiOipi/Cloud-Architect"}
            """;

    /** The blocks a careers page carries that are not postings. */
    private static final String NOT_A_POSTING = """
            {"@context":"https://schema.org","@type":"Organization",
             "name":"Northgate Systems","url":"__BASE__"}
            """;

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

    private JobPostingPageAdapter adapter() {
        return new JobPostingPageAdapter(RestClient.builder().requestFactory(loopback).build(),
                new SourceRateLimiter(), new SafeUrlValidator(), new ObjectMapper());
    }

    /**
     * The fixture, with {@code __BASE__} resolved to wherever the test server is
     * listening, so a posting's stated destination is genuinely on the host the
     * page was served from.
     */
    private byte[] page(String... blocks) {
        String base = server.url("");
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Careers</title>");
        for (String block : blocks) {
            html.append("<script type=\"application/ld+json\">")
                    .append(block.replace("__BASE__", base))
                    .append("</script>");
        }
        return html.append("</head><body><h1>Careers</h1></body></html>")
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private SourceConfiguration sourceAt(String path, int maxJobs) {
        return new SourceConfiguration(UUID.randomUUID(), "Northgate Systems", server.url(path),
                "pages.test.example", JobPostingPageAdapter.KEY, 6000, null, maxJobs);
    }

    @Test
    @DisplayName("reads every posting the page declares")
    void readsThePostings() {
        server.fixed("/careers", 200, page(NOT_A_POSTING, POSTING_ONE, POSTING_TWO),
                "Content-Type", "text/html; charset=utf-8");

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/careers", 200));

        assertThat(postings).hasSize(2);
        assertThat(postings).extracting(RawJobPosting::title)
                .containsExactly("Associate Project Manager", "Cloud Architect");
        assertThat(postings.get(0).locationText()).contains("Hyderabad");
        assertThat(postings.get(0).applyUrl())
                .isEqualTo(server.url("/apply/4EpNpkSTON/Associate-Project-Manager"));
        assertThat(postings.get(0).postedAt()).isNotNull();
        assertThat(postings.get(0).externalId()).isNotBlank();
    }

    @Test
    @DisplayName("the employer is the source's, not the name written into the page")
    void companyComesFromTheSource() {
        // hiringOrganization is written by whoever wrote the page. A page that
        // names somebody else must not re-attribute the jobs to them.
        String impersonating = POSTING_ONE.replace("Northgate Systems", "Some Other Employer");
        server.fixed("/careers", 200, page(impersonating), "Content-Type", "text/html");

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/careers", 200));

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).companyName()).isEqualTo("Northgate Systems");
    }

    @Test
    @DisplayName("stops at the configured ceiling")
    void honoursMaxJobs() {
        server.fixed("/careers", 200, page(POSTING_ONE, POSTING_TWO), "Content-Type", "text/html");

        assertThat(adapter().fetchJobs(sourceAt("/careers", 1))).hasSize(1);
    }

    @Test
    @DisplayName("a page with no JobPosting markup fails rather than reporting an empty board")
    void refusesToReportAnEmptyBoard() {
        // Reporting zero postings would let one site redesign close every job
        // this source has ever produced.
        server.fixed("/careers", 200, page(NOT_A_POSTING), "Content-Type", "text/html");

        assertThatThrownBy(() -> adapter().fetchJobs(sourceAt("/careers", 200)))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("No readable JobPosting markup");
    }

    @Test
    @DisplayName("markup that declares nothing usable is a failure too")
    void refusesPostingsThatCannotBeMapped() {
        // A JobPosting with no title is not something a student can be shown.
        server.fixed("/careers", 200,
                page("{\"@context\":\"https://schema.org\",\"@type\":\"JobPosting\","
                        + "\"description\":\"We are hiring.\"}"),
                "Content-Type", "text/html");

        assertThatThrownBy(() -> adapter().fetchJobs(sourceAt("/careers", 200)))
                .isInstanceOf(AdapterException.class);
    }

    @Test
    @DisplayName("an apply link on the employer's applicant tracking system is kept")
    void keepsAnAtsDestination() {
        // The shape this adapter exists for: the roles are listed on the
        // company's own page and applied for on the system hosting their hiring,
        // so the destination is on a different host by design.
        server.fixed("/careers", 200,
                page(POSTING_ONE.replace("__BASE__", "https://northgate.applytojob.com")),
                "Content-Type", "text/html");

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/careers", 200));

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).applyUrl())
                .isEqualTo("https://northgate.applytojob.com/apply/4EpNpkSTON/Associate-Project-Manager");
    }

    @Test
    @DisplayName("an apply link on a host that is neither the page's nor a board is dropped")
    void dropsAnUnrelatedDestination() {
        // A planted link must not send a student somewhere arbitrary. The posting
        // is still worth showing; CareerFlux just cannot say where to apply.
        server.fixed("/careers", 200,
                page(POSTING_ONE.replace("__BASE__", "https://unrelated-host.net")),
                "Content-Type", "text/html");

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/careers", 200));

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).applyUrl()).isNull();
        assertThat(postings.get(0).sourceUrl()).isNull();
    }

    @Test
    @DisplayName("claims only a source already assigned to it")
    void claimsNothingItWasNotGiven() {
        JobPostingPageAdapter adapter = adapter();
        // The registry hands a configuration to the first adapter that claims it,
        // so claiming by URL here would take other adapters' sources.
        assertThat(adapter.supports(new SourceConfiguration(UUID.randomUUID(), "x",
                "https://boards-api.greenhouse.io/v1/boards/acme/jobs", "acme", "greenhouse",
                60, null, 10))).isFalse();
        assertThat(adapter.supports(new SourceConfiguration(UUID.randomUUID(), "x",
                "https://acme.example/careers", "acme", null, 60, null, 10))).isFalse();
        assertThat(adapter.supports(new SourceConfiguration(UUID.randomUUID(), "x",
                "https://acme.example/careers", "acme", JobPostingPageAdapter.KEY, 60, null, 10))).isTrue();
    }

    @Test
    @DisplayName("health reports how many postings the page declares")
    void healthCountsPostings() {
        server.fixed("/careers", 200, page(POSTING_ONE, POSTING_TWO), "Content-Type", "text/html");

        SourceHealthResult health = adapter().checkHealth(sourceAt("/careers", 200));

        assertThat(health.status()).isEqualTo(SourceHealthStatus.HEALTHY);
        assertThat(health.jobsSeen()).isEqualTo(2);
    }

    @Test
    @DisplayName("health reports a failing page instead of throwing")
    void healthReportsFailure() {
        server.fixed("/careers", 500, new byte[0]);

        SourceHealthResult health = adapter().checkHealth(sourceAt("/careers", 200));

        assertThat(health.status()).isNotEqualTo(SourceHealthStatus.HEALTHY);
    }

    @Test
    @DisplayName("describes itself as a public page, not an ATS API")
    void metadataDescribesAPage() {
        SourceMetadata metadata = adapter().getMetadata();

        assertThat(metadata.key()).isEqualTo(JobPostingPageAdapter.KEY);
        assertThat(metadata.sourceType()).isEqualTo(SourceType.COMPANY_CAREER_PAGE);
        assertThat(metadata.intendedAccessPolicy()).isEqualTo(AccessPolicyType.PUBLIC_PAGE);
    }

    @Test
    @DisplayName("the page it would read is the page robots is evaluated against")
    void probeUrlIsThePage() {
        SourceConfiguration configuration = sourceAt("/careers", 200);

        assertThat(adapter().probeUrl(configuration)).isEqualTo(configuration.baseUrl());
    }
}
