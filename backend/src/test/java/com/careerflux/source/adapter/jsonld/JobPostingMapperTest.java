package com.careerflux.source.adapter.jsonld;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import com.careerflux.common.TextUtils;
import com.careerflux.source.adapter.RawJobPosting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Mapping one JobPosting the page declared into the posting shape the pipeline reads
 * (Phase 2, stage 2).
 *
 * <p>Pages here are strings, as in stage 1: the parser finds the posting and the
 * mapper reads it. Nothing makes a request, and one test proves the mapper makes
 * none either.
 */
class JobPostingMapperTest {

    private static final String PAGE_URL = "https://nimbusdevops.applytojob.com/apply/Ab3dE5fG7h/Platform-Engineer";
    private static final String COMPANY = "Nimbus DevOps Pvt. Ltd.";

    /** What the adapter knows: the source's company, the page it fetched, and its own id for the posting. */
    private static final JobPostingMapper.PageContext CONTEXT =
            new JobPostingMapper.PageContext(COMPANY, PAGE_URL, "Ab3dE5fG7h");

    /** The same, without an id of its own, so the page's identity is used. */
    private static final JobPostingMapper.PageContext NO_ADAPTER_ID =
            new JobPostingMapper.PageContext(COMPANY, PAGE_URL, null);

    private static JobPostingNode node(String json) {
        JobPostingJsonLd.Result result = JobPostingJsonLd.parse(
                "<html><head><script type=\"application/ld+json\">" + json + "</script></head><body></body></html>");
        assertThat(result.postings()).describedAs("the fixture parses to one posting").hasSize(1);
        return result.postings().get(0);
    }

    private static RawJobPosting mapped(String json) {
        return JobPostingMapper.map(node(json), CONTEXT).orElseThrow();
    }

    private static RawJobPosting mapped(String json, JobPostingMapper.PageContext context) {
        return JobPostingMapper.map(node(json), context).orElseThrow();
    }

    /** A JobPosting with the given extra properties. */
    private static String posting(String properties) {
        return "{\"@type\":\"JobPosting\",\"title\":\"Platform Engineer\"" + (properties.isEmpty() ? "" : "," + properties) + "}";
    }

    @Nested
    @DisplayName("what the posting is known by")
    class Identity {

        @Test
        @DisplayName("the adapter's own id wins, because only it knows what is stable on that board")
        void adapterIdWins() {
            RawJobPosting posting = mapped(posting(
                    "\"identifier\":{\"@type\":\"PropertyValue\",\"value\":\"REQ-42\"},\"url\":\"" + PAGE_URL + "\""));

            assertThat(posting.externalId()).isEqualTo("Ab3dE5fG7h");
            assertThat(posting.requisitionId()).isEqualTo("REQ-42");
        }

        @Test
        @DisplayName("the identifier the page states comes next, as text or as a PropertyValue")
        void identifierNext() {
            assertThat(mapped(posting("\"identifier\":\"REQ-7\",\"url\":\"" + PAGE_URL + "\""), NO_ADAPTER_ID)
                    .externalId()).isEqualTo("REQ-7");
            assertThat(mapped(posting("\"identifier\":{\"@type\":\"PropertyValue\",\"name\":\"Acme\",\"value\":\"REQ-8\"}"),
                    NO_ADAPTER_ID).externalId()).isEqualTo("REQ-8");
            assertThat(mapped(posting("\"identifier\":{\"@type\":\"PropertyValue\",\"value\":4711}"), NO_ADAPTER_ID)
                    .externalId()).isEqualTo("4711");
        }

        @Test
        @DisplayName("then the posting's own canonical URL")
        void urlNext() {
            RawJobPosting posting = mapped(posting("\"url\":\"https://NimbusDevOps.applytojob.com/apply/Ab3dE5fG7h/Platform-Engineer/#apply\""),
                    NO_ADAPTER_ID);

            assertThat(posting.externalId())
                    .isEqualTo("https://nimbusdevops.applytojob.com/apply/Ab3dE5fG7h/Platform-Engineer");
            assertThat(posting.requisitionId()).isNull();
        }

        @Test
        @DisplayName("and failing that, the page the posting says it is")
        void mainEntityOfPageLast() {
            assertThat(mapped(posting("\"mainEntityOfPage\":\"" + PAGE_URL + "\""), NO_ADAPTER_ID).externalId())
                    .isEqualTo(PAGE_URL);
            assertThat(mapped(posting("\"mainEntityOfPage\":{\"@type\":\"WebPage\",\"@id\":\"" + PAGE_URL + "\"}"),
                    NO_ADAPTER_ID).externalId()).isEqualTo(PAGE_URL);
        }

        @Test
        @DisplayName("sameAs is never an identity")
        void sameAsIsNotIdentity() {
            Optional<RawJobPosting> mapped = JobPostingMapper.map(
                    node(posting("\"sameAs\":[\"https://nimbusdevops.applytojob.com/apply/Ab3dE5fG7h/Copy\"]")),
                    NO_ADAPTER_ID);

            assertThat(mapped).isEmpty();
        }

        @Test
        @DisplayName("a posting with nothing stable to be known by is not mapped")
        void withoutIdentity() {
            assertThat(JobPostingMapper.map(node(posting("")), NO_ADAPTER_ID)).isEmpty();
            assertThat(JobPostingMapper.map(node(posting("\"url\":\"/apply/relative\"")), NO_ADAPTER_ID)).isEmpty();
            assertThat(JobPostingMapper.map(node(posting("\"url\":\"javascript:alert(1)\"")), NO_ADAPTER_ID)).isEmpty();
        }

        @Test
        @DisplayName("a posting with no title is not mapped, whatever else it states")
        void withoutTitle() {
            assertThat(JobPostingMapper.map(node("{\"@type\":\"JobPosting\",\"identifier\":\"R-1\"}"), CONTEXT)).isEmpty();
            assertThat(JobPostingMapper.map(node("{\"@type\":\"JobPosting\",\"title\":\"   \",\"identifier\":\"R-1\"}"),
                    CONTEXT)).isEmpty();
        }

        @Test
        @DisplayName("an id too long to store is carried as a digest of itself, not cut short")
        void longIdentity() {
            String longId = "R-" + "9".repeat(400);
            RawJobPosting posting = mapped(posting(""),
                    new JobPostingMapper.PageContext(COMPANY, PAGE_URL, longId));

            assertThat(posting.externalId()).isEqualTo("sha256:" + TextUtils.sha256(longId));
            assertThat(posting.externalId().length()).isLessThanOrEqualTo(JobPostingMapper.MAX_EXTERNAL_ID_CHARS);
        }

        @Test
        @DisplayName("a title is trimmed, and a requisition id longer than the column is cut to it")
        void trimming() {
            assertThat(mapped("{\"@type\":\"JobPosting\",\"title\":\"  Platform Engineer  \"}").title())
                    .isEqualTo("Platform Engineer");
            assertThat(mapped(posting("\"identifier\":\"" + "R".repeat(200) + "\"")).requisitionId()).hasSize(120);
        }
    }

    @Nested
    @DisplayName("the company is the source's own")
    class Company {

        @Test
        @DisplayName("hiringOrganization never becomes the company, however the page words it")
        void hiringOrganizationIsNotTheCompany() {
            RawJobPosting posting = mapped(posting(
                    "\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Global Mega Corp\","
                            + "\"sameAs\":\"https://mega.example\"}"));

            assertThat(posting.companyName()).isEqualTo(COMPANY);
            // Still recorded, as the page said it.
            assertThat(posting.rawPayload()).contains("Global Mega Corp");
        }

        @Test
        @DisplayName("a source with no company name of its own leaves it for the pipeline to fill")
        void withoutSourceCompany() {
            RawJobPosting posting = mapped(posting("\"hiringOrganization\":\"Global Mega Corp\""),
                    new JobPostingMapper.PageContext(null, PAGE_URL, "Ab3dE5fG7h"));

            assertThat(posting.companyName()).isNull();
        }
    }

    @Nested
    @DisplayName("what the posting says about itself")
    class Core {

        @Test
        @DisplayName("the description is passed on as published, for the pipeline to strip")
        void description() {
            String html = "<p>Run our release pipeline on <strong>Kubernetes</strong>.</p>";
            RawJobPosting posting = mapped(posting("\"description\":\"" + html.replace("\"", "\\\"") + "\""));

            assertThat(posting.descriptionHtml()).isEqualTo(html);
        }

        @Test
        @DisplayName("a date is a day at midnight UTC, a date-time is the moment it names")
        void dates() {
            assertThat(mapped(posting("\"datePosted\":\"2026-08-12\"")).postedAt())
                    .isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
            assertThat(mapped(posting("\"datePosted\":\"2026-08-12T09:30:00+05:30\"")).postedAt())
                    .isEqualTo(Instant.parse("2026-08-12T04:00:00Z"));
            assertThat(mapped(posting("\"datePosted\":\"2026-08-12T04:00:00Z\"")).postedAt())
                    .isEqualTo(Instant.parse("2026-08-12T04:00:00Z"));
            assertThat(mapped(posting("\"datePosted\":\"last Tuesday\"")).postedAt()).isNull();
            assertThat(mapped(posting("")).postedAt()).isNull();
        }

        @Test
        @DisplayName("validThrough is kept as stated and read by nothing yet")
        void validThrough() {
            RawJobPosting posting = mapped(posting("\"validThrough\":\"2026-11-10\""));

            assertThat(posting.rawPayload()).contains("\"validThrough\":\"2026-11-10\"");
            assertThat(posting.postedAt()).isNull();
        }

        @Test
        @DisplayName("one employment type is passed on; several different ones are left unstated")
        void employmentType() {
            assertThat(mapped(posting("\"employmentType\":\"FULL_TIME\"")).employmentTypeText()).isEqualTo("FULL_TIME");
            assertThat(mapped(posting("\"employmentType\":[\"FULL_TIME\"]")).employmentTypeText()).isEqualTo("FULL_TIME");
            assertThat(mapped(posting("\"employmentType\":[\"FULL_TIME\",\"FULL_TIME\"]")).employmentTypeText())
                    .isEqualTo("FULL_TIME");
            assertThat(mapped(posting("\"employmentType\":[\"FULL_TIME\",\"PART_TIME\"]")).employmentTypeText()).isNull();
            assertThat(mapped(posting("\"employmentType\":\"PER_DIEM\"")).employmentTypeText()).isEqualTo("PER_DIEM");
            assertThat(mapped(posting("")).employmentTypeText()).isNull();
        }

        @Test
        @DisplayName("an experience level written as a phrase is never turned into years")
        void experienceIsNotInvented() {
            for (String label : new String[] {"Entry Level", "Mid Level", "Senior Manager/Supervisor"}) {
                RawJobPosting posting = mapped(posting("\"experienceRequirements\":\"" + label + "\""));
                assertThat(posting.rawPayload()).contains(label);
                // Nothing in the posting shape can hold years, so nothing claims any.
                assertThat(posting.descriptionHtml()).isNull();
                assertThat(posting.salaryText()).isNull();
            }
        }

        @Test
        @DisplayName("months of experience are kept as stated; the pipeline has nowhere to put a number yet")
        void structuredExperience() {
            RawJobPosting posting = mapped(posting(
                    "\"experienceRequirements\":{\"@type\":\"OccupationalExperienceRequirements\",\"monthsOfExperience\":24}"));

            assertThat(posting.rawPayload()).contains("\"monthsOfExperience\":24");
            assertThat(posting.descriptionHtml()).isNull();
        }
    }

    @Nested
    @DisplayName("where the job is")
    class Location {

        @Test
        @DisplayName("a city on its own, and a city with a state code")
        void cityAndRegion() {
            assertThat(mapped(posting("\"jobLocation\":{\"@type\":\"Place\",\"address\":"
                    + "{\"@type\":\"PostalAddress\",\"addressLocality\":\"Hyderabad\"}}")).locationText())
                    .isEqualTo("Hyderabad");
            assertThat(mapped(posting("\"jobLocation\":{\"@type\":\"Place\",\"address\":"
                    + "{\"addressLocality\":\"Atlanta\",\"addressRegion\":\"GA\"}}")).locationText())
                    .isEqualTo("Atlanta, GA");
        }

        @Test
        @DisplayName("a country written out is kept, and an ISO code becomes the country it stands for")
        void countries() {
            assertThat(location("\"addressLocality\":\"Pune\",\"addressRegion\":\"Maharashtra\",\"addressCountry\":\"India\""))
                    .isEqualTo("Pune, Maharashtra, India");
            assertThat(location("\"addressLocality\":\"Hyderabad\",\"addressCountry\":\"IN\""))
                    .isEqualTo("Hyderabad, India");
            assertThat(location("\"addressLocality\":\"Atlanta\",\"addressRegion\":\"GA\",\"addressCountry\":\"US\""))
                    .isEqualTo("Atlanta, GA, United States");
            assertThat(location("\"addressLocality\":\"Bengaluru\",\"addressCountry\":\"in\""))
                    .isEqualTo("Bengaluru, India");
            assertThat(location("\"addressLocality\":\"Chennai\",\"addressCountry\":{\"@type\":\"Country\",\"name\":\"IN\"}"))
                    .isEqualTo("Chennai, India");
            // A code that stands for nothing is all the page gave us.
            assertThat(location("\"addressLocality\":\"Nowhere\",\"addressCountry\":\"ZZ\""))
                    .isEqualTo("Nowhere, ZZ");
        }

        @Test
        @DisplayName("a region spelled out with no country is left out, because it would be read as one")
        void regionWithoutCountry() {
            assertThat(location("\"addressLocality\":\"Hyderabad\",\"addressRegion\":\"Telangana\""))
                    .isEqualTo("Hyderabad");
            // Kept where nothing is lost.
            assertThat(mapped(posting("\"jobLocation\":{\"@type\":\"Place\",\"address\":"
                    + "{\"addressLocality\":\"Hyderabad\",\"addressRegion\":\"Telangana\"}}")).rawPayload())
                    .contains("Telangana");
        }

        @Test
        @DisplayName("several offices are kept in the order the page named them, each once")
        void severalOffices() {
            RawJobPosting posting = mapped(posting("\"jobLocation\":["
                    + "{\"@type\":\"Place\",\"address\":{\"addressLocality\":\"Hyderabad\",\"addressCountry\":\"IN\"}},"
                    + "{\"@type\":\"Place\",\"address\":{\"addressLocality\":\"Pune\",\"addressCountry\":\"IN\"}},"
                    + "{\"@type\":\"Place\",\"address\":{\"addressLocality\":\"Hyderabad\",\"addressCountry\":\"IN\"}}]"));

            assertThat(posting.locationText()).isEqualTo("Hyderabad, India • Pune, India");
        }

        @Test
        @DisplayName("an address written as one string, or a place with only a name, is taken as it stands")
        void plainAddresses() {
            assertThat(mapped(posting("\"jobLocation\":{\"@type\":\"Place\",\"address\":\"Bengaluru, India\"}"))
                    .locationText()).isEqualTo("Bengaluru, India");
            assertThat(mapped(posting("\"jobLocation\":{\"@type\":\"Place\",\"name\":\"Mumbai\"}")).locationText())
                    .isEqualTo("Mumbai");
        }

        @Test
        @DisplayName("no location is stated when the page states none, and none is inferred from the words")
        void noLocation() {
            assertThat(mapped(posting("\"description\":\"Work from our Hyderabad office\"")).locationText()).isNull();
            assertThat(mapped(posting("\"jobLocation\":\"Hyderabad\"")).locationText()).isNull();
        }

        private String location(String address) {
            return mapped(posting("\"jobLocation\":{\"@type\":\"Place\",\"address\":{" + address + "}}")).locationText();
        }
    }

    @Nested
    @DisplayName("working remotely")
    class Remote {

        @Test
        @DisplayName("TELECOMMUTE is remote, alone or among others")
        void telecommute() {
            assertThat(mapped(posting("\"jobLocationType\":\"TELECOMMUTE\"")).workModeText()).isEqualTo("remote");
            assertThat(mapped(posting("\"jobLocationType\":[\"TELECOMMUTE\"]")).workModeText()).isEqualTo("remote");
            assertThat(mapped(posting("\"jobLocationType\":\"telecommute\"")).workModeText()).isEqualTo("remote");
        }

        @Test
        @DisplayName("anything else leaves the work mode for the pipeline to read from what was said")
        void nothingElseIsInferred() {
            assertThat(mapped(posting("")).workModeText()).isNull();
            assertThat(mapped(posting("\"jobLocationType\":\"OTHER\"")).workModeText()).isNull();
            assertThat(mapped(posting("\"description\":\"A hybrid role, two days in the office\"")).workModeText())
                    .isNull();
            assertThat(mapped(posting("\"applicantLocationRequirements\":{\"@type\":\"Country\",\"name\":\"IN\"}"))
                    .workModeText()).isNull();
        }

        @Test
        @DisplayName("who may apply is kept as stated and used for nothing")
        void applicantLocationRequirements() {
            RawJobPosting posting = mapped(posting(
                    "\"jobLocationType\":\"TELECOMMUTE\",\"applicantLocationRequirements\":{\"@type\":\"Country\",\"name\":\"US\"}"));

            assertThat(posting.rawPayload()).contains("applicantLocationRequirements");
            assertThat(posting.locationText()).isNull();
        }
    }

    @Nested
    @DisplayName("pay")
    class Salary {

        private String salary(String body) {
            return mapped(posting("\"baseSalary\":{\"@type\":\"MonetaryAmount\"," + body + "}")).salaryText();
        }

        @Test
        @DisplayName("a range, a single amount, and each period the pipeline can state")
        void statedExactly() {
            assertThat(salary("\"currency\":\"INR\",\"value\":{\"@type\":\"QuantitativeValue\","
                    + "\"minValue\":1200000,\"maxValue\":1800000,\"unitText\":\"YEAR\"}"))
                    .isEqualTo("₹1200000 - ₹1800000 per year");
            assertThat(salary("\"currency\":\"INR\",\"value\":{\"value\":90000,\"unitText\":\"MONTH\"}"))
                    .isEqualTo("₹90000 - ₹90000 per month");
            assertThat(salary("\"currency\":\"USD\",\"value\":{\"minValue\":25.5,\"maxValue\":30,\"unitText\":\"HOUR\"}"))
                    .isEqualTo("$25.5 - $30 per hour");
        }

        @Test
        @DisplayName("a currency or a period the pipeline cannot state is left unclaimed, and still recorded")
        void notStatedWhenItCannotBeSaid() {
            assertThat(salary("\"currency\":\"AED\",\"value\":{\"minValue\":10000,\"maxValue\":20000,\"unitText\":\"MONTH\"}"))
                    .isNull();
            assertThat(salary("\"currency\":\"INR\",\"value\":{\"minValue\":5000,\"maxValue\":7000,\"unitText\":\"WEEK\"}"))
                    .isNull();
            assertThat(salary("\"currency\":\"INR\",\"value\":{\"minValue\":1200000,\"maxValue\":1800000}")).isNull();
            assertThat(salary("\"currency\":\"INR\",\"value\":{\"unitText\":\"YEAR\"}")).isNull();
            assertThat(mapped(posting("\"baseSalary\":1500000")).salaryText()).isNull();
            assertThat(mapped(posting("")).salaryText()).isNull();

            RawJobPosting unstated = mapped(posting("\"baseSalary\":{\"@type\":\"MonetaryAmount\",\"currency\":\"AED\","
                    + "\"value\":{\"minValue\":10000,\"unitText\":\"MONTH\"}}"));
            assertThat(unstated.rawPayload()).contains("AED");
        }
    }

    @Nested
    @DisplayName("where a student is sent")
    class Destination {

        @Test
        @DisplayName("the posting's own page on the host it came from")
        void ownPage() {
            RawJobPosting posting = mapped(posting("\"url\":\"" + PAGE_URL + "\""));

            assertThat(posting.applyUrl()).isEqualTo(PAGE_URL);
            assertThat(posting.sourceUrl()).isEqualTo(PAGE_URL);
        }

        @Test
        @DisplayName("a URL on another host is data, not a destination")
        void otherHost() {
            RawJobPosting posting = mapped(posting("\"url\":\"https://careers.elsewhere.example/apply/1\""));

            assertThat(posting.applyUrl()).isNull();
            assertThat(posting.sourceUrl()).isNull();
            assertThat(posting.rawPayload()).contains("careers.elsewhere.example");
        }

        @Test
        @DisplayName("sameAs, mainEntityOfPage and the organization's links are never a destination")
        void neverFromOtherLinks() {
            RawJobPosting posting = mapped(posting(
                    "\"sameAs\":[\"" + PAGE_URL + "\"],\"mainEntityOfPage\":\"" + PAGE_URL + "\","
                            + "\"hiringOrganization\":{\"@type\":\"Organization\",\"sameAs\":\"" + PAGE_URL + "\"}"));

            assertThat(posting.applyUrl()).isNull();
            assertThat(posting.sourceUrl()).isNull();
        }

        @Test
        @DisplayName("a URL that is not a web address, or a page that was not fetched, has no destination")
        void unusableUrls() {
            assertThat(mapped(posting("\"url\":\"javascript:alert(1)\"")).applyUrl()).isNull();
            assertThat(mapped(posting("\"url\":\"https://localhost/apply\"")).applyUrl()).isNull();
            assertThat(mapped(posting("\"url\":\"" + PAGE_URL + "\""),
                    new JobPostingMapper.PageContext(COMPANY, null, "Ab3dE5fG7h")).applyUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("what is kept of the page")
    class RawPayload {

        @Test
        @DisplayName("the posting itself, and nothing else from the page it was on")
        void onlyThePosting() {
            String html = """
                    <html><head>
                    <script type="application/ld+json">{"@type":"Organization","name":"Nimbus DevOps Pvt. Ltd.","taxID":"SECRET-TAX-ID"}</script>
                    <script type="application/ld+json">{"@type":"JobPosting","title":"Platform Engineer","identifier":"R-1"}</script>
                    <script type="application/ld+json">{"@type":"WebSite","name":"Careers","url":"https://nimbusdevops.applytojob.com"}</script>
                    </head><body></body></html>""";
            JobPostingJsonLd.Result parsed = JobPostingJsonLd.parse(html);
            RawJobPosting posting = JobPostingMapper.map(parsed.postings().get(0), CONTEXT).orElseThrow();

            assertThat(posting.rawPayload())
                    .isEqualTo("{\"@type\":\"JobPosting\",\"title\":\"Platform Engineer\",\"identifier\":\"R-1\"}");
            assertThat(posting.rawPayload()).doesNotContain("SECRET-TAX-ID", "WebSite");
        }

        @Test
        @DisplayName("not even what shared its own block, when a graph holds the posting and other things")
        void onlyThePostingWithinItsBlock() {
            String html = """
                    <html><head><script type="application/ld+json">
                    {"@context":"https://schema.org","@graph":[
                      {"@type":"Organization","name":"Nimbus DevOps Pvt. Ltd.","taxID":"SECRET-TAX-ID"},
                      {"@type":"JobPosting","title":"Platform Engineer","identifier":"R-2"},
                      {"@type":"WebSite","name":"Careers"}]}
                    </script></head><body></body></html>""";
            JobPostingJsonLd.Result parsed = JobPostingJsonLd.parse(html);
            RawJobPosting posting = JobPostingMapper.map(parsed.postings().get(0), CONTEXT).orElseThrow();

            assertThat(posting.rawPayload())
                    .isEqualTo("{\"@type\":\"JobPosting\",\"title\":\"Platform Engineer\",\"identifier\":\"R-2\"}");
            assertThat(posting.rawPayload()).doesNotContain("SECRET-TAX-ID", "WebSite", "@graph");
        }

        @Test
        @DisplayName("the same page always maps to the same posting")
        void deterministic() {
            String json = posting("\"url\":\"" + PAGE_URL + "\",\"datePosted\":\"2026-08-12\","
                    + "\"jobLocation\":{\"@type\":\"Place\",\"address\":{\"addressLocality\":\"Hyderabad\",\"addressCountry\":\"IN\"}}");

            assertThat(mapped(json)).isEqualTo(mapped(json));
        }
    }

    @Nested
    @DisplayName("network safety")
    class NetworkSafety {

        private static final List<String> FORBIDDEN_CLASSES = List.of(
                "java/net/URL", "java/net/URLConnection", "java/net/HttpURLConnection", "java/net/Socket",
                "java/net/InetAddress", "java/net/InetSocketAddress", "java/net/Proxy", "java/net/ProxySelector",
                "java/lang/ProcessBuilder", "java/lang/Process", "java/lang/Runtime");

        private static final List<String> FORBIDDEN_PACKAGES = List.of(
                "java/net/http/", "javax/net/", "java/nio/channels/", "javax/script/",
                "org/springframework/web/client/", "org/springframework/http/client/", "com/careerflux/source/net/");

        @Test
        @DisplayName("the mapper's compiled classes reference nothing that can make a connection")
        void noNetworkingInTheBytecode() throws IOException {
            String bytecode = bytecodeOf(JobPostingMapper.class);

            for (String forbidden : FORBIDDEN_CLASSES) {
                assertThat(Pattern.compile(Pattern.quote(forbidden) + "(?![A-Za-z0-9_$])").matcher(bytecode).find())
                        .describedAs("the mapper references %s", forbidden).isFalse();
            }
            for (String forbidden : FORBIDDEN_PACKAGES) {
                assertThat(bytecode).describedAs("the mapper references %s", forbidden).doesNotContain(forbidden);
            }
            assertThat(bytecode).contains("java/net/URI");
        }

        @Test
        @DisplayName("mapping a posting whose every link points somewhere makes no request at all")
        void zeroRequests() {
            String json = posting("\"url\":\"https://jobs.mapper-test.invalid/1\","
                    + "\"sameAs\":[\"https://mirror.mapper-test.invalid/1\"],"
                    + "\"image\":\"https://img.mapper-test.invalid/logo.png\","
                    + "\"mainEntityOfPage\":{\"@type\":\"WebPage\",\"@id\":\"https://page.mapper-test.invalid/1\"},"
                    + "\"hiringOrganization\":{\"@type\":\"Organization\",\"sameAs\":\"https://org.mapper-test.invalid\"},"
                    + "\"description\":\"Apply at https://apply.mapper-test.invalid/now\"");
            JobPostingNode node = node(json);

            List<URI> requested = whileRecordingConnections(() -> JobPostingMapper.map(node,
                    new JobPostingMapper.PageContext(COMPANY, "https://jobs.mapper-test.invalid/1", null)));

            assertThat(requested).isEmpty();
            RawJobPosting posting = JobPostingMapper.map(node,
                    new JobPostingMapper.PageContext(COMPANY, "https://jobs.mapper-test.invalid/1", null)).orElseThrow();
            // Every link was read as data: the url identifies the posting, the rest is recorded.
            assertThat(posting.externalId()).isEqualTo("https://jobs.mapper-test.invalid/1");
            assertThat(posting.rawPayload()).contains("mirror.mapper-test.invalid", "apply.mapper-test.invalid");
        }

        @Test
        @DisplayName("the recorder does see an HTTP attempt, so its silence above means something")
        void recorderCatchesAnAttempt() {
            List<URI> requested = whileRecordingConnections(() -> {
                try {
                    new URI("http://canary.mapper-test.invalid/").toURL().openConnection().connect();
                } catch (Exception refused) {
                    // The recorder refuses every connection before any lookup happens.
                }
            });

            assertThat(requested).extracting(URI::getHost).containsExactly("canary.mapper-test.invalid");
        }

        private List<URI> whileRecordingConnections(Runnable work) {
            List<URI> seen = new CopyOnWriteArrayList<>();
            ProxySelector previous = ProxySelector.getDefault();
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    if (uri.getHost() != null && uri.getHost().endsWith("mapper-test.invalid")) {
                        seen.add(uri);
                        throw new IllegalStateException("connection refused by the test");
                    }
                    return previous == null ? List.of(Proxy.NO_PROXY) : previous.select(uri);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                    // Nothing to do.
                }
            });
            try {
                work.run();
            } finally {
                ProxySelector.setDefault(previous);
            }
            return List.copyOf(seen);
        }

        private String bytecodeOf(Class<?> type) throws IOException {
            try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(in).describedAs("class file for %s", type.getName()).isNotNull();
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }
}
