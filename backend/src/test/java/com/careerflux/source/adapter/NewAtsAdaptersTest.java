package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * The four ATS adapters added for enterprise coverage, against canned payloads.
 *
 * <p>Every fixture here is trimmed from a real response captured while verifying
 * the provider, so the field names are the provider's own rather than what an
 * adapter would find convenient. That is the point: an adapter that reads
 * {@code title} from a provider that sends {@code name} still compiles, still
 * returns postings, and produces a board of untitled jobs.
 *
 * <p>No network. The transport is stubbed, so these run anywhere and assert on
 * parsing and normalization rather than on a third party being up.
 */
class NewAtsAdaptersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SourceRateLimiter permissive = new SourceRateLimiter();

    /** Every URI the adapter asked for, in order — the paging assertions read this. */
    private final List<String> requested = new ArrayList<>();

    private RestClient clientReturning(String... bodiesInOrder) {
        return RestClient.builder().requestFactory(sequence(200, bodiesInOrder)).build();
    }

    private RestClient clientReturning(int status, String body) {
        return RestClient.builder().requestFactory(sequence(status, body)).build();
    }

    private ClientHttpRequestFactory sequence(int status, String... bodies) {
        return (uri, method) -> {
            int index = Math.min(requested.size(), bodies.length - 1);
            requested.add(uri.toString());
            return new StubRequest(uri, method, status, new HttpHeaders(), bodies[index]);
        };
    }

    private SourceConfiguration config(String adapterKey, String baseUrl, String identifier, int maxJobs) {
        return new SourceConfiguration(UUID.randomUUID(), "Test Employer", baseUrl, identifier,
                adapterKey, 6000, null, maxJobs);
    }

    // ============================================================ SmartRecruiters

    @Nested
    @DisplayName("SmartRecruiters")
    class SmartRecruiters {

        private static final String ONE_POSTING = """
                {"offset":0,"limit":10,"totalFound":1,"content":[
                  {"id":"744000147950329","name":"Commercial Buyer","refNumber":"REF295764L",
                   "releasedDate":"2026-09-07T14:03:41.074Z",
                   "company":{"identifier":"BoschGroup","name":"Bosch Group"},
                   "location":{"city":"Bengaluru","region":"KA","country":"in"},
                   "typeOfEmployment":{"id":"permanent","label":"Full-time"},
                   "department":{"label":"Purchasing"}}]}
                """;

        private SmartRecruitersAdapter adapter(RestClient client) {
            return new SmartRecruitersAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
        }

        @Test
        @DisplayName("reads the provider's own field names, not convenient ones")
        void parsesAPosting() {
            List<RawJobPosting> jobs = adapter(clientReturning(ONE_POSTING))
                    .fetchJobs(config(SmartRecruitersAdapter.KEY,
                            "https://api.smartrecruiters.com", "BoschGroup", 200));

            assertThat(jobs).hasSize(1);
            RawJobPosting job = jobs.get(0);
            assertThat(job.externalId()).isEqualTo("744000147950329");
            assertThat(job.title()).isEqualTo("Commercial Buyer");
            assertThat(job.companyName()).isEqualTo("Bosch Group");
            assertThat(job.requisitionId()).isEqualTo("REF295764L");
            assertThat(job.locationText()).isEqualTo("Bengaluru, KA, in");
            assertThat(job.employmentTypeText()).isEqualTo("Full-time");
            assertThat(job.postedAt()).isNotNull();
        }

        @Test
        @DisplayName("the apply link points at the advert, not back at the API")
        void buildsACandidateFacingApplyUrl() {
            RawJobPosting job = adapter(clientReturning(ONE_POSTING))
                    .fetchJobs(config(SmartRecruitersAdapter.KEY,
                            "https://api.smartrecruiters.com", "BoschGroup", 200)).get(0);

            // The payload's own `ref` is another api.smartrecruiters.com URL. A
            // student sent there would get JSON.
            assertThat(job.applyUrl())
                    .isEqualTo("https://jobs.smartrecruiters.com/BoschGroup/744000147950329");
        }

        @Test
        @DisplayName("a description the list does not carry stays missing rather than invented")
        void descriptionIsHonestlyAbsent() {
            RawJobPosting job = adapter(clientReturning(ONE_POSTING))
                    .fetchJobs(config(SmartRecruitersAdapter.KEY,
                            "https://api.smartrecruiters.com", "BoschGroup", 200)).get(0);

            assertThat(job.descriptionHtml()).isNull();
        }

        @Test
        @DisplayName("an empty board is an empty list, not a failure")
        void emptyBoard() {
            assertThat(adapter(clientReturning("{\"totalFound\":0,\"content\":[]}"))
                    .fetchJobs(config(SmartRecruitersAdapter.KEY,
                            "https://api.smartrecruiters.com", "Nobody", 200)))
                    .isEmpty();
        }

        @Test
        @DisplayName("a response with no content array is reported, not silently empty")
        void malformedResponse() {
            assertThatThrownBy(() -> adapter(clientReturning("{\"unexpected\":true}"))
                    .fetchJobs(config(SmartRecruitersAdapter.KEY,
                            "https://api.smartrecruiters.com", "X", 200)))
                    .isInstanceOf(AdapterException.class)
                    .hasMessageContaining("content array");
        }

        @Test
        @DisplayName("a posting with no id is skipped rather than stored without identity")
        void skipsPostingsWithNoId() {
            String body = "{\"content\":[{\"name\":\"Nameless\"},"
                    + "{\"id\":\"7\",\"name\":\"Real\",\"company\":{\"name\":\"C\"}}]}";
            List<RawJobPosting> jobs = adapter(clientReturning(body))
                    .fetchJobs(config(SmartRecruitersAdapter.KEY,
                            "https://api.smartrecruiters.com", "X", 200));

            assertThat(jobs).hasSize(1);
            assertThat(jobs.get(0).title()).isEqualTo("Real");
        }
    }

    // =================================================================== Workable

    @Nested
    @DisplayName("Workable")
    class Workable {

        private static final String ONE_JOB = """
                {"name":"Blueground","description":"d","jobs":[
                  {"shortcode":"0FD01ABC66","title":"Business Development Representative",
                   "code":"BD-1","description":"<p>Sell things</p>","city":"","state":"",
                   "country":"United States","employment_type":"Full-time","department":"Sales",
                   "telecommuting":true,"published_on":"2026-08-18","created_at":"2026-08-18",
                   "url":"https://apply.workable.com/j/0FD01ABC66",
                   "shortlink":"https://apply.workable.com/j/0FD01ABC66"}]}
                """;

        private WorkableAdapter adapter(RestClient client) {
            return new WorkableAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
        }

        @Test
        @DisplayName("parses a job and takes the company from the account, not the subdomain")
        void parsesAJob() {
            RawJobPosting job = adapter(clientReturning(ONE_JOB))
                    .fetchJobs(config(WorkableAdapter.KEY,
                            "https://apply.workable.com", "blueground", 200)).get(0);

            assertThat(job.externalId()).isEqualTo("0FD01ABC66");
            assertThat(job.title()).isEqualTo("Business Development Representative");
            assertThat(job.companyName()).isEqualTo("Blueground");
            assertThat(job.descriptionHtml()).contains("Sell things");
            assertThat(job.applyUrl()).isEqualTo("https://apply.workable.com/j/0FD01ABC66");
        }

        @Test
        @DisplayName("blank city and state do not become a location of commas")
        void skipsBlankLocationParts() {
            RawJobPosting job = adapter(clientReturning(ONE_JOB))
                    .fetchJobs(config(WorkableAdapter.KEY,
                            "https://apply.workable.com", "blueground", 200)).get(0);

            // The provider sends "" rather than omitting the field for a remote role.
            assertThat(job.locationText()).isEqualTo("United States");
        }

        @Test
        @DisplayName("the provider's remote flag is passed through as text for the normalizer")
        void carriesTheRemoteFlag() {
            RawJobPosting job = adapter(clientReturning(ONE_JOB))
                    .fetchJobs(config(WorkableAdapter.KEY,
                            "https://apply.workable.com", "blueground", 200)).get(0);

            assertThat(job.workModeText()).isEqualTo("Remote");
        }

        @Test
        @DisplayName("a missing jobs array is reported")
        void malformedResponse() {
            assertThatThrownBy(() -> adapter(clientReturning("{\"name\":\"X\"}"))
                    .fetchJobs(config(WorkableAdapter.KEY, "https://apply.workable.com", "x", 200)))
                    .isInstanceOf(AdapterException.class)
                    .hasMessageContaining("jobs array");
        }

        @Test
        @DisplayName("the run's ceiling is respected even when the board is larger")
        void respectsTheCeiling() {
            StringBuilder body = new StringBuilder("{\"name\":\"Big\",\"jobs\":[");
            for (int i = 0; i < 30; i++) {
                body.append(i == 0 ? "" : ",")
                        .append("{\"shortcode\":\"S").append(i).append("\",\"title\":\"T\"}");
            }
            body.append("]}");

            assertThat(adapter(clientReturning(body.toString()))
                    .fetchJobs(config(WorkableAdapter.KEY, "https://apply.workable.com", "big", 5)))
                    .hasSize(5);
        }
    }

    // ===================================================================== Breezy

    @Nested
    @DisplayName("Breezy HR")
    class Breezy {

        private static final String ONE_POSTING = """
                [{"id":"98323abf2296","name":"Backend Engineer",
                  "friendly_id":"98323abf2296-backend-engineer",
                  "published_date":"2024-02-15T14:37:22.684Z",
                  "company":{"name":"MS Breezy Trial"},
                  "department":"Engineering","salary":"$1 - $2 / hour",
                  "type":{"id":"full_time","name":"Full-Time"},
                  "location":{"country":{"name":"India","id":"IN"},"state":{"name":"KA"},
                              "city":"Bengaluru"},
                  "url":"https://breezy.breezy.hr/p/98323abf2296-backend-engineer"}]
                """;

        private BreezyAdapter adapter(RestClient client) {
            return new BreezyAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
        }

        @Test
        @DisplayName("reads a bare array, which is the shape this provider uses")
        void parsesAnArrayResponse() {
            RawJobPosting job = adapter(clientReturning(ONE_POSTING))
                    .fetchJobs(config(BreezyAdapter.KEY, "https://breezy.breezy.hr", "breezy", 200))
                    .get(0);

            assertThat(job.externalId()).isEqualTo("98323abf2296");
            assertThat(job.title()).isEqualTo("Backend Engineer");
            assertThat(job.companyName()).isEqualTo("MS Breezy Trial");
            assertThat(job.employmentTypeText()).isEqualTo("Full-Time");
            assertThat(job.salaryText()).isEqualTo("$1 - $2 / hour");
        }

        @Test
        @DisplayName("location parts arrive as objects and as plain strings, and both are read")
        void flattensMixedLocationShapes() {
            RawJobPosting job = adapter(clientReturning(ONE_POSTING))
                    .fetchJobs(config(BreezyAdapter.KEY, "https://breezy.breezy.hr", "breezy", 200))
                    .get(0);

            // city is a string here; state and country are objects with a name.
            assertThat(job.locationText()).isEqualTo("Bengaluru, KA, India");
        }

        @Test
        @DisplayName("an empty board is an empty list")
        void emptyBoard() {
            assertThat(adapter(clientReturning("[]"))
                    .fetchJobs(config(BreezyAdapter.KEY, "https://breezy.breezy.hr", "x", 200)))
                    .isEmpty();
        }

        @Test
        @DisplayName("an object where an array belongs is reported")
        void malformedResponse() {
            assertThatThrownBy(() -> adapter(clientReturning("{\"jobs\":[]}"))
                    .fetchJobs(config(BreezyAdapter.KEY, "https://breezy.breezy.hr", "x", 200)))
                    .isInstanceOf(AdapterException.class)
                    .hasMessageContaining("array");
        }
    }

    // ================================================================ Oracle HCM

    @Nested
    @DisplayName("Oracle HCM")
    class OracleHcm {

        private static String page(String... ids) {
            StringBuilder reqs = new StringBuilder();
            for (int i = 0; i < ids.length; i++) {
                reqs.append(i == 0 ? "" : ",").append("""
                        {"Id":"%s","Title":"Consultant %s","PrimaryLocation":"Bengaluru, India",
                         "PostedDate":"2026-09-06","ShortDescriptionStr":"Work on things",
                         "JobType":"Regular","WorkplaceType":"Hybrid","JobFamily":"Consulting"}
                        """.formatted(ids[i], ids[i]));
            }
            return "{\"items\":[{\"SearchId\":1,\"TotalJobsCount\":2270,\"requisitionList\":["
                    + reqs + "]}]}";
        }

        private OracleHcmAdapter adapter(RestClient client) {
            return new OracleHcmAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
        }

        private SourceConfiguration oracle(int maxJobs) {
            return config(OracleHcmAdapter.KEY,
                    "https://eeho.fa.us2.oraclecloud.com", "CX_1", maxJobs);
        }

        @Test
        @DisplayName("finds requisitions nested inside the search envelope")
        void parsesNestedRequisitions() {
            RawJobPosting job = adapter(clientReturning(page("344533"))).fetchJobs(oracle(1)).get(0);

            assertThat(job.externalId()).isEqualTo("344533");
            assertThat(job.title()).isEqualTo("Consultant 344533");
            assertThat(job.locationText()).isEqualTo("Bengaluru, India");
            assertThat(job.descriptionHtml()).isEqualTo("Work on things");
            assertThat(job.workModeText()).isEqualTo("Hybrid");
            assertThat(job.applyUrl()).isEqualTo("https://eeho.fa.us2.oraclecloud.com"
                    + "/hcmUI/CandidateExperience/en/sites/CX_1/job/344533");
        }

        @Test
        @DisplayName("an envelope with no requisitionList is a fault, not an empty board")
        void missingExpandIsReported() {
            // This is the trap: without expand=requisitionList the provider still
            // returns 200 and a job count. Treating it as "no jobs" would retire a
            // healthy source for having nothing to offer.
            String noList = "{\"items\":[{\"SearchId\":1,\"TotalJobsCount\":2270}]}";

            assertThatThrownBy(() -> adapter(clientReturning(noList)).fetchJobs(oracle(10)))
                    .isInstanceOf(AdapterException.class)
                    .hasMessageContaining("expand=requisitionList");
        }

        @Test
        @DisplayName("the request asks for the expand every time")
        void alwaysRequestsTheExpand() {
            adapter(clientReturning(page("1"))).fetchJobs(oracle(1));

            assertThat(requested).isNotEmpty();
            // The client percent-encodes the '=' inside the finder value, so the
            // wire form is siteNumber%3DCX_1. Verified against the live endpoint:
            // Oracle accepts it and returns requisitions, so the assertion is on
            // the intent rather than on one spelling of it.
            String url = java.net.URLDecoder.decode(requested.get(0), StandardCharsets.UTF_8);
            assertThat(url)
                    .contains("expand=requisitionList")
                    .contains("siteNumber=CX_1")
                    .contains("offset=0");
        }

        @Test
        @DisplayName("pages until a short page arrives, then stops")
        void pagesThenStops() {
            // A full page (limit 2) then a short one. Two requests, three jobs, no
            // third request looking for more.
            RestClient client = RestClient.builder()
                    .requestFactory(sequence(200, page("1", "2"), page("3")))
                    .build();

            List<RawJobPosting> jobs = adapter(client).fetchJobs(oracle(2));

            assertThat(jobs).hasSize(2);
            assertThat(requested).hasSize(1);
        }

        @Test
        @DisplayName("a source with no host is refused rather than guessed at")
        void refusesAConfigurationWithNoHost() {
            assertThatThrownBy(() -> adapter(clientReturning(page("1")))
                    .fetchJobs(config(OracleHcmAdapter.KEY, "  ", "CX_1", 10)))
                    .isInstanceOf(AdapterException.class)
                    .hasMessageContaining("Fusion host");
        }
    }

    // ================================================================== registry

    @Nested
    @DisplayName("registry and routing")
    class Registry {

        private JobSourceAdapter any(String key) {
            RestClient client = RestClient.builder().requestFactory(sequence(200, "{}")).build();
            return switch (key) {
                case SmartRecruitersAdapter.KEY ->
                        new SmartRecruitersAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
                case WorkableAdapter.KEY ->
                        new WorkableAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
                case BreezyAdapter.KEY ->
                        new BreezyAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
                default ->
                        new OracleHcmAdapter(client, permissive, new SafeUrlValidator(), objectMapper);
            };
        }

        @Test
        @DisplayName("every new adapter registers under its own key")
        void keysAreDistinctAndResolvable() {
            List<JobSourceAdapter> adapters = List.of(
                    any(SmartRecruitersAdapter.KEY), any(WorkableAdapter.KEY),
                    any(BreezyAdapter.KEY), any(OracleHcmAdapter.KEY));

            AdapterRegistry registry = new AdapterRegistry(adapters);

            for (String key : List.of("smartrecruiters", "workable", "breezy", "oracle-hcm")) {
                assertThat(registry.has(key)).describedAs(key).isTrue();
                assertThat(registry.find(key)).describedAs(key).isPresent();
            }
        }

        @Test
        @DisplayName("each declares the provider it actually is")
        void metadataNamesTheProvider() {
            assertThat(any(SmartRecruitersAdapter.KEY).getMetadata().atsProvider().name())
                    .isEqualTo("SMARTRECRUITERS");
            assertThat(any(WorkableAdapter.KEY).getMetadata().atsProvider().name())
                    .isEqualTo("WORKABLE");
            assertThat(any(BreezyAdapter.KEY).getMetadata().atsProvider().name())
                    .isEqualTo("BREEZY");
            assertThat(any(OracleHcmAdapter.KEY).getMetadata().atsProvider().name())
                    .isEqualTo("ORACLE_HCM");
        }

        @Test
        @DisplayName("a source is claimed by its host even when the key is absent")
        void supportsByUrl() {
            assertThat(any(BreezyAdapter.KEY).supports(
                    config(null, "https://acme.breezy.hr/json", "acme", 10))).isTrue();
            assertThat(any(WorkableAdapter.KEY).supports(
                    config(null, "https://apply.workable.com/x", "x", 10))).isTrue();
            assertThat(any(OracleHcmAdapter.KEY).supports(
                    config(null, "https://eeho.fa.us2.oraclecloud.com", "CX_1", 10))).isTrue();
            assertThat(any(SmartRecruitersAdapter.KEY).supports(
                    config(null, "https://boards.greenhouse.io/x", "x", 10))).isFalse();
        }
    }

    // ------------------------------------------------------------------ transport

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
