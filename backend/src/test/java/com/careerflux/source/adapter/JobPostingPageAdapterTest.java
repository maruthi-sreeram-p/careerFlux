package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.careerflux.common.error.UnsafeUrlException;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.adapter.jsonld.Sitemap;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.SourceHealthStatus;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.RobotsTxtService;
import com.careerflux.source.service.SourceRateLimiter;
import com.careerflux.support.StreamingHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Reading a careers page, and the job pages it points at (Phase 2, stage 4).
 *
 * <p>The fixtures are written in the shape a hosted careers page publishes:
 * postings in separate blocks, no {@code identifier}, a date-only
 * {@code datePosted}, {@code employmentType} as an array, and an address whose
 * country is spelled out. The postings themselves are invented.
 *
 * <p>Most of what is tested here is the disagreement between a listing and the
 * pages it links to, because that is where a real employer's data went wrong: a
 * careers page advertising roles that had already been withdrawn.
 *
 * <p>Every test is pinned to the loopback server by {@link OnlyLoopback}, so a
 * fixture that names an outside host can never put a request on the network.
 * Nothing asserts on a whole body either, so a broken expectation cannot flood
 * the output.
 */
class JobPostingPageAdapterTest {

    private StreamingHttpServer server;
    private SimpleClientHttpRequestFactory loopback;

    private static final String ONE_PATH = "/apply/4EpNpkSTON/Associate-Project-Manager";
    private static final String TWO_PATH = "/apply/ByksQiOipi/Cloud-Architect";

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

    /** Nothing leaves the loopback interface, whatever a fixture says. */
    private static final class OnlyLoopback extends SafeUrlValidator {
        @Override
        public URI validate(String rawUrl) {
            URI uri = URI.create(rawUrl);
            if (!"127.0.0.1".equals(uri.getHost()) && !"localhost".equals(uri.getHost())) {
                throw new UnsafeUrlException("Refused in this test: " + rawUrl);
            }
            return uri;
        }
    }

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

    /**
     * A robots reader whose every request lands on the loopback server, whatever
     * host the URL names. A fixture that links to an outside host would otherwise
     * make a real request for that host's robots.txt.
     */
    private RobotsTxtService robots() {
        ClientHttpRequestFactory routing = (uri, method) -> {
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return loopback.createRequest(URI.create(server.url(path)), method);
        };
        return new RobotsTxtService(
                RestClient.builder().requestFactory(routing).build(),
                new CareerFluxProperties(null, null, null,
                        new CareerFluxProperties.Sources("CareerFluxBot/0.1 (+https://careerflux.local/bot)",
                                Duration.ofSeconds(20), 20, Duration.ofHours(6)),
                        null, new CareerFluxProperties.Demo(false, false), null, true));
    }

    private JobPostingPageAdapter adapter() {
        return new JobPostingPageAdapter(RestClient.builder().requestFactory(loopback).build(),
                new SourceRateLimiter(), new OnlyLoopback(), new ObjectMapper(), robots());
    }

    /**
     * The fixture, with {@code __BASE__} resolved to wherever the test server is
     * listening, so a posting's stated destination is genuinely on the host the
     * page was served from.
     */
    private byte[] page(String... blocks) {
        return pageLinking(List.of(), blocks);
    }

    /** A page carrying the given JSON-LD blocks and the given anchors. */
    private byte[] pageLinking(List<String> hrefs, String... blocks) {
        String base = server.url("");
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Careers</title>");
        for (String block : blocks) {
            html.append("<script type=\"application/ld+json\">")
                    .append(block.replace("__BASE__", base))
                    .append("</script>");
        }
        html.append("</head><body><h1>Careers</h1>");
        for (String href : hrefs) {
            html.append("<a href=\"").append(href.replace("__BASE__", base)).append("\">role</a>");
        }
        return html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Registers a job's own page, declaring the posting the way a real one does. */
    private void jobPage(String path, String block) {
        server.fixed(path, 200, page(block), "Content-Type", "text/html; charset=utf-8");
    }

    private void listing(String... blocks) {
        server.fixed("/careers", 200, page(blocks), "Content-Type", "text/html; charset=utf-8");
    }

    private SourceConfiguration sourceAt(String path, int maxJobs) {
        return new SourceConfiguration(UUID.randomUUID(), "Northgate Systems", server.url(path),
                "pages.test.example", JobPostingPageAdapter.KEY, 6000, null, maxJobs);
    }

    private List<RawJobPosting> fetch(int maxJobs) {
        return adapter().fetchJobs(sourceAt("/careers", maxJobs));
    }

    // ------------------------------------------------------------ reading

    @Test
    @DisplayName("reads every posting the listing declares")
    void readsThePostings() {
        listing(NOT_A_POSTING, POSTING_ONE, POSTING_TWO);
        jobPage(ONE_PATH, POSTING_ONE);
        jobPage(TWO_PATH, POSTING_TWO);

        List<RawJobPosting> postings = fetch(200);

        assertThat(postings).hasSize(2);
        assertThat(postings).extracting(RawJobPosting::title)
                .containsExactlyInAnyOrder("Associate Project Manager", "Cloud Architect");
        RawJobPosting first = postings.stream()
                .filter(p -> "Associate Project Manager".equals(p.title())).findFirst().orElseThrow();
        assertThat(first.locationText()).contains("Hyderabad");
        assertThat(first.applyUrl()).isEqualTo(server.url(ONE_PATH));
        assertThat(first.postedAt()).isNotNull();
        assertThat(first.externalId()).isNotBlank();
    }

    @Test
    @DisplayName("finds postings the listing does not declare, by opening what it links to")
    void readsPostingsFromLinkedPagesAlone() {
        // The shape of a real applicant tracking board: the listing names no
        // postings in its markup, only links to each job's own page.
        server.fixed("/careers", 200,
                pageLinking(List.of("__BASE__" + ONE_PATH, "__BASE__" + TWO_PATH), NOT_A_POSTING),
                "Content-Type", "text/html; charset=utf-8");
        jobPage(ONE_PATH, POSTING_ONE);
        jobPage(TWO_PATH, POSTING_TWO);

        assertThat(fetch(200)).hasSize(2);
    }

    @Test
    @DisplayName("a posting's own page outranks what the listing said about it")
    void theOwnPageWins() {
        // The listing is a snapshot; the job's page is current.
        listing(POSTING_ONE);
        jobPage(ONE_PATH, POSTING_ONE.replace("Associate Project Manager", "Senior Project Manager"));

        assertThat(fetch(200)).extracting(RawJobPosting::title).containsExactly("Senior Project Manager");
    }

    // -------------------------------------------------------- withdrawals

    @Test
    @DisplayName("a posting whose page is gone is dropped")
    void dropsWithdrawnPostings() {
        listing(POSTING_ONE, POSTING_TWO);
        jobPage(ONE_PATH, POSTING_ONE);
        server.fixed(TWO_PATH, 410, new byte[0]);

        List<RawJobPosting> postings = fetch(200);

        assertThat(postings).extracting(RawJobPosting::title).containsExactly("Associate Project Manager");
    }

    @Test
    @DisplayName("every posting withdrawn is an empty board, not a failure")
    void allWithdrawnIsAnEmptyBoard() {
        // The employer answered for each one. That is knowledge, not a read
        // failure, and the run should close them rather than mark the source bad.
        listing(POSTING_ONE, POSTING_TWO);
        server.fixed(ONE_PATH, 410, new byte[0]);
        server.fixed(TWO_PATH, 404, new byte[0]);

        assertThat(fetch(200)).isEmpty();
    }

    @Test
    @DisplayName("a page that says nothing leaves the listing's claim standing")
    void keepsAClaimWhenThePageIsSilent() {
        // An employer whose job pages carry no markup is no worse off than before.
        listing(POSTING_ONE);
        server.fixed(ONE_PATH, 200, "<html><body>Apply here</body></html>".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/html");

        assertThat(fetch(200)).extracting(RawJobPosting::title).containsExactly("Associate Project Manager");
    }

    @Test
    @DisplayName("a link it cannot fetch does not abort the run, and does not withdraw the posting")
    void aRefusedLinkDoesNotAbortTheRun() {
        // A board link is followed, and this one cannot be fetched: OnlyLoopback
        // refuses the outside host, which is what the real validator does for a
        // private address or a plain-http hop. That is no evidence either way, so
        // the run finishes and the listing's claim stands.
        listing(POSTING_ONE.replace("__BASE__", "https://northgate.applytojob.com"),
                POSTING_TWO);
        jobPage(TWO_PATH, POSTING_TWO);

        assertThat(fetch(200)).extracting(RawJobPosting::title)
                .containsExactlyInAnyOrder("Associate Project Manager", "Cloud Architect");
    }

    // ------------------------------------------------------------- expiry

    @Test
    @DisplayName("a posting whose stated expiry has passed is dropped")
    void dropsExpiredPostings() {
        listing(POSTING_ONE.replace("\"datePosted\":\"2026-02-02\"",
                "\"datePosted\":\"2020-01-01\",\"validThrough\":\"2020-06-30\""));

        assertThat(fetch(200)).isEmpty();
    }

    @Test
    @DisplayName("a posting that has not expired yet is kept")
    void keepsPostingsWithAFutureExpiry() {
        listing(POSTING_ONE.replace("\"datePosted\":\"2026-02-02\"",
                "\"datePosted\":\"2026-02-02\",\"validThrough\":\"2099-12-30\""));
        jobPage(ONE_PATH, POSTING_ONE);

        assertThat(fetch(200)).hasSize(1);
    }

    @Test
    @DisplayName("an expiry it cannot read is never a reason to drop a posting")
    void unreadableExpiryIsIgnored() {
        assertThat(JobPostingPageAdapter.hasExpired(null)).isFalse();
        assertThat(JobPostingPageAdapter.hasExpired("")).isFalse();
        assertThat(JobPostingPageAdapter.hasExpired("whenever")).isFalse();
        assertThat(JobPostingPageAdapter.hasExpired("2099-12-30")).isFalse();
        assertThat(JobPostingPageAdapter.hasExpired("2020-06-30")).isTrue();
        assertThat(JobPostingPageAdapter.hasExpired("2020-06-30T12:00:00+05:30")).isTrue();
    }

    // -------------------------------------------------------------- rules

    @Test
    @DisplayName("the employer is the source's, not the name written into the page")
    void companyComesFromTheSource() {
        // hiringOrganization is written by whoever wrote the page. A page that
        // names somebody else must not re-attribute the jobs to them.
        listing(POSTING_ONE.replace("Northgate Systems", "Some Other Employer"));
        jobPage(ONE_PATH, POSTING_ONE.replace("Northgate Systems", "Some Other Employer"));

        assertThat(fetch(200)).extracting(RawJobPosting::companyName).containsOnly("Northgate Systems");
    }

    @Test
    @DisplayName("stops at the configured ceiling")
    void honoursMaxJobs() {
        listing(POSTING_ONE, POSTING_TWO);
        jobPage(ONE_PATH, POSTING_ONE);
        jobPage(TWO_PATH, POSTING_TWO);

        assertThat(fetch(1)).hasSize(1);
    }

    @Test
    @DisplayName("a page with no JobPosting markup anywhere fails rather than reporting an empty board")
    void refusesToReportAnEmptyBoard() {
        // Nothing was found and nothing answered "gone", so this is a failure to
        // read. Reporting zero would let one site redesign close every job this
        // source has ever produced.
        listing(NOT_A_POSTING);

        assertThatThrownBy(() -> fetch(200))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("No readable JobPosting markup");
    }

    @Test
    @DisplayName("markup that declares nothing usable is a failure too")
    void refusesPostingsThatCannotBeMapped() {
        listing("{\"@context\":\"https://schema.org\",\"@type\":\"JobPosting\","
                + "\"description\":\"We are hiring.\"}");

        assertThatThrownBy(() -> fetch(200)).isInstanceOf(AdapterException.class);
    }

    @Test
    @DisplayName("an apply link on the employer's applicant tracking system is kept")
    void keepsAnAtsDestination() {
        // The roles are listed on the company's own page and applied for on their
        // ATS, so the destination is on a different host by design. The link is
        // not fetched here — OnlyLoopback refuses it — which is exactly the
        // "no evidence either way" case, so the claim stands.
        listing(POSTING_ONE.replace("__BASE__", "https://northgate.applytojob.com"));

        List<RawJobPosting> postings = fetch(200);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).applyUrl())
                .isEqualTo("https://northgate.applytojob.com" + ONE_PATH);
    }

    @Test
    @DisplayName("an apply link on a host that is neither the page's nor a board is dropped")
    void dropsAnUnrelatedDestination() {
        listing(POSTING_ONE.replace("__BASE__", "https://unrelated-host.net"));

        List<RawJobPosting> postings = fetch(200);

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

    // ------------------------------------------------------------- robots

    @Test
    @DisplayName("a page the host's robots.txt disallows is not opened")
    void doesNotOpenPagesRobotsDisallows() {
        // The ingestion gate judged the listing's address. The pages it points at
        // are other paths, with rules of their own.
        server.fixed("/robots.txt", 200, "User-agent: *\nDisallow: /apply/\n".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/plain");
        listing(POSTING_ONE, POSTING_TWO);
        jobPage(ONE_PATH, POSTING_ONE);
        jobPage(TWO_PATH, POSTING_TWO);

        List<RawJobPosting> postings = fetch(200);

        assertThat(server.requestCount(ONE_PATH)).isZero();
        assertThat(server.requestCount(TWO_PATH)).isZero();
        // Nothing contradicted the listing, so its claims stand.
        assertThat(postings).hasSize(2);
    }

    @Test
    @DisplayName("a host whose robots.txt cannot be read is treated as closed")
    void unreadableRobotsKeepsPagesClosed() {
        server.fixed("/robots.txt", 503, new byte[0]);
        listing(POSTING_ONE);
        jobPage(ONE_PATH, POSTING_ONE);

        fetch(200);

        assertThat(server.requestCount(ONE_PATH)).isZero();
    }

    @Test
    @DisplayName("a host that publishes no robots.txt is open")
    void noRobotsMeansOpen() {
        listing(POSTING_ONE);
        jobPage(ONE_PATH, POSTING_ONE);

        fetch(200);

        assertThat(server.requestCount(ONE_PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("robots.txt is read once for a whole run, however many pages follow")
    void robotsIsReadOncePerRun() {
        server.fixed("/robots.txt", 200, "User-agent: *\nDisallow: /private/\n".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/plain");
        listing(POSTING_ONE, POSTING_TWO);
        jobPage(ONE_PATH, POSTING_ONE);
        jobPage(TWO_PATH, POSTING_TWO);

        fetch(200);

        assertThat(server.requestCount("/robots.txt")).isEqualTo(1);
    }

    // ------------------------------------------------------------- sitemap

    private static final String JOB_ONE = "/jobs/100/platform-engineer";
    private static final String JOB_TWO = "/jobs/200/data-analyst";

    /** A job's page declaring its posting but no url of its own, as plenty of sites do. */
    private static String anonymousPosting(String title) {
        return "{\"@context\":\"https://schema.org\",\"@type\":\"JobPosting\",\"title\":\"" + title
                + "\",\"description\":\"<p>Join the team.</p>\",\"datePosted\":\"2026-09-15\","
                + "\"validThrough\":\"2099-12-30\",\"employmentType\":\"FULL_TIME\","
                + "\"jobLocation\":{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\","
                + "\"addressLocality\":\"Hyderabad\",\"addressCountry\":\"India\"}}}";
    }

    private void sitemap(String path, String... locs) {
        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (String loc : locs) {
            xml.append("<url><loc>").append(loc).append("</loc></url>");
        }
        server.fixed(path, 200, xml.append("</urlset>").toString().getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/xml");
    }

    @Test
    @DisplayName("reads the job pages a sitemap names")
    void readsFromASitemap() {
        sitemap("/sitemap.xml", server.url(JOB_ONE), server.url(JOB_TWO));
        jobPage(JOB_ONE, anonymousPosting("Platform Engineer"));
        jobPage(JOB_TWO, anonymousPosting("Data Analyst"));

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/sitemap.xml", 200));

        assertThat(postings).extracting(RawJobPosting::title)
                .containsExactly("Platform Engineer", "Data Analyst");
        assertThat(postings).extracting(RawJobPosting::companyName).containsOnly("Northgate Systems");
    }

    @Test
    @DisplayName("a page that names no identity of its own is known by its address")
    void aPageIsItsOwnIdentity() {
        sitemap("/sitemap.xml", server.url(JOB_ONE), server.url(JOB_TWO));
        jobPage(JOB_ONE, anonymousPosting("Platform Engineer"));
        jobPage(JOB_TWO, anonymousPosting("Platform Engineer"));

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/sitemap.xml", 200));

        // Same title, different pages: two jobs, not one.
        assertThat(postings).hasSize(2);
        assertThat(postings).extracting(RawJobPosting::externalId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the marketing pages in a sitemap are not opened")
    void ignoresPagesThatAreNotJobs() {
        sitemap("/sitemap.xml", server.url("/about-us"), server.url(JOB_ONE), server.url("/pricing"));
        jobPage(JOB_ONE, anonymousPosting("Platform Engineer"));
        // Registered, so a request for either would be counted. An unregistered
        // path is answered by the server's default handler and counted by nobody,
        // which would make the assertions below true whatever the adapter did.
        jobPage("/about-us", anonymousPosting("About us"));
        jobPage("/pricing", anonymousPosting("Pricing"));

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/sitemap.xml", 200));

        assertThat(postings).extracting(RawJobPosting::title).containsExactly("Platform Engineer");

        assertThat(server.requestCount("/about-us")).isZero();
        assertThat(server.requestCount("/pricing")).isZero();
    }

    @Test
    @DisplayName("a sitemap page robots.txt disallows is not opened")
    void sitemapRespectsRobots() {
        server.fixed("/robots.txt", 200, "User-agent: *\nDisallow: /jobs/200\n".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/plain");
        sitemap("/sitemap.xml", server.url(JOB_ONE), server.url(JOB_TWO));
        jobPage(JOB_ONE, anonymousPosting("Platform Engineer"));
        jobPage(JOB_TWO, anonymousPosting("Data Analyst"));

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/sitemap.xml", 200));

        assertThat(server.requestCount(JOB_TWO)).isZero();
        assertThat(postings).extracting(RawJobPosting::title).containsExactly("Platform Engineer");
    }

    @Test
    @DisplayName("an index is expanded into the sitemaps it names on its own host")
    void expandsAnIndex() {
        // The second child is served and readable, but under another host name:
        // "localhost" reaches the same test server as "127.0.0.1" while being a
        // different host as far as the adapter is concerned.
        String otherHost = server.url("/other-sitemap.xml").replace("127.0.0.1", "localhost");
        server.fixed("/sitemap.xml", 200,
                ("<?xml version=\"1.0\"?><sitemapindex>"
                        + "<sitemap><loc>" + server.url("/sitemap-jobs.xml") + "</loc></sitemap>"
                        + "<sitemap><loc>" + otherHost + "</loc></sitemap>"
                        + "</sitemapindex>").getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/xml");
        sitemap("/sitemap-jobs.xml", server.url(JOB_ONE));
        sitemap("/other-sitemap.xml", server.url(JOB_TWO));
        jobPage(JOB_ONE, anonymousPosting("Platform Engineer"));
        jobPage(JOB_TWO, anonymousPosting("Data Analyst"));

        List<RawJobPosting> postings = adapter().fetchJobs(sourceAt("/sitemap.xml", 200));

        assertThat(postings).extracting(RawJobPosting::title).containsExactly("Platform Engineer");
        assertThat(server.requestCount("/other-sitemap.xml")).isZero();
    }

    @Test
    @DisplayName("a sitemap whose pages carry no markup fails rather than reporting an empty board")
    void sitemapWithoutMarkupFails() {
        sitemap("/sitemap.xml", server.url(JOB_ONE));
        server.fixed(JOB_ONE, 200, "<html><body>Apply</body></html>".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/html");

        assertThatThrownBy(() -> adapter().fetchJobs(sourceAt("/sitemap.xml", 200)))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("No readable JobPosting markup");
    }

    @Test
    @DisplayName("recognises a sitemap and nothing else as one")
    void recognisesSitemaps() {
        assertThat(Sitemap.isSitemap("<?xml version=\"1.0\"?><urlset></urlset>")).isTrue();
        assertThat(Sitemap.isSitemap("<sitemapindex></sitemapindex>")).isTrue();
        assertThat(Sitemap.isSitemap("<html><body>urlset</body></html>")).isFalse();
        assertThat(Sitemap.isSitemap(null)).isFalse();
        assertThat(Sitemap.isIndex("<sitemapindex></sitemapindex>")).isTrue();
        assertThat(Sitemap.isIndex("<urlset></urlset>")).isFalse();
        // Entities in an address are undone, and only the path decides what is a job.
        assertThat(Sitemap.locs("<loc> https://a.example/jobs?x=1&amp;y=2 </loc>"))
                .containsExactly("https://a.example/jobs?x=1&y=2");
        assertThat(Sitemap.looksLikeJobPage("https://a.example/jobs/12/engineer")).isTrue();
        assertThat(Sitemap.looksLikeJobPage("https://jobs.a.example/about-us")).isFalse();
        assertThat(Sitemap.looksLikeJobPage("https://a.example/sitemap-jobs.xml")).isFalse();
    }

    // ------------------------------------------------------------- health

    @Test
    @DisplayName("health reports how many postings the listing declares")
    void healthCountsPostings() {
        listing(POSTING_ONE, POSTING_TWO);

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
