package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.common.error.UnsafeUrlException;
import com.careerflux.source.net.SafeUrlValidator;
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
 * Reading a JSON feed through an admin's definition.
 *
 * <p>The definitions name an https host, as real ones must. Every request is
 * routed to the loopback server whatever host it names, and the validator
 * refuses anything but that one host, so no fixture can reach the network.
 */
class ConfiguredJsonAdapterTest {

    private static final String HOST = "jobs.northgate-systems.in";

    private StreamingHttpServer server;

    /** Only the fixture host is fetchable; the routing below keeps it on loopback. */
    private static final class OnlyFixtureHost extends SafeUrlValidator {
        @Override
        public URI validate(String rawUrl) {
            URI uri = URI.create(rawUrl);
            if (!HOST.equals(uri.getHost())) {
                throw new UnsafeUrlException("Refused in this test: " + rawUrl);
            }
            return uri;
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = new StreamingHttpServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private ConfiguredJsonAdapter adapter(ConfiguredJsonAdapter.Spec spec) {
        SimpleClientHttpRequestFactory loopback = new SimpleClientHttpRequestFactory();
        loopback.setConnectTimeout(Duration.ofSeconds(5));
        loopback.setReadTimeout(Duration.ofSeconds(5));
        // Validates every request before routing it, as the production client's
        // SafeClientHttpRequestFactory does: that is where the first request's
        // address is checked, and the adapter's own validator checks redirects.
        OnlyFixtureHost validator = new OnlyFixtureHost();
        ClientHttpRequestFactory routing = (uri, method) -> {
            validator.validate(uri.toString());
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return loopback.createRequest(URI.create(server.url(path)), method);
        };
        return new ConfiguredJsonAdapter(RestClient.builder().requestFactory(routing).build(),
                new SourceRateLimiter(), validator, new ObjectMapper(), spec);
    }

    /** A feed shaped like most: postings under data.jobs, locations as a list of objects. */
    private static ConfiguredJsonAdapter.Spec nested() {
        return new ConfiguredJsonAdapter.Spec("custom-northgate", "Northgate feed", null, null,
                "https://" + HOST + "/feed/{token}",
                ConfiguredJsonAdapter.pointer("data.jobs"),
                ConfiguredJsonAdapter.pointer("id"),
                ConfiguredJsonAdapter.pointer("title"),
                ConfiguredJsonAdapter.pointer("locations"),
                ConfiguredJsonAdapter.pointer("body"),
                ConfiguredJsonAdapter.pointer("links.apply"),
                null,
                ConfiguredJsonAdapter.pointer("published"),
                ConfiguredJsonAdapter.pointer("type"),
                ConfiguredJsonAdapter.pointer("team.name"));
    }

    private void feed(String path, String json) {
        server.fixed(path, 200, json.getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json");
    }

    private static SourceConfiguration source(String key, String token, int maxJobs) {
        return new SourceConfiguration(UUID.randomUUID(), "Northgate Systems", "https://" + HOST,
                token, key, 600, null, maxJobs);
    }

    private static final String NESTED_FEED = """
            {"data":{"jobs":[
              {"id":"R-1","title":"Platform Engineer",
               "locations":[{"name":"Hyderabad"},{"name":"Bengaluru"}],
               "body":"<p>Build services.</p>","links":{"apply":"https://jobs.northgate-systems.in/apply/R-1"},
               "published":"2026-09-15T10:00:00Z","type":"Full-time","team":{"name":"Platform"}},
              {"id":"R-2","title":"Data Analyst","locations":"Pune",
               "published":1757930400,"links":{}},
              {"title":"No id at all"},
              {"id":"R-4"}
            ]}}
            """;

    @Test
    @DisplayName("reads each field from where the definition says it is")
    void readsThroughTheDefinition() {
        feed("/feed/northgate", NESTED_FEED);

        List<RawJobPosting> postings = adapter(nested()).fetchJobs(source("custom-northgate", "northgate", 200));

        assertThat(postings).extracting(RawJobPosting::externalId).containsExactly("R-1", "R-2");
        RawJobPosting first = postings.get(0);
        assertThat(first.title()).isEqualTo("Platform Engineer");
        assertThat(first.locationText()).isEqualTo("Hyderabad, Bengaluru");
        assertThat(first.descriptionHtml()).isEqualTo("<p>Build services.</p>");
        assertThat(first.applyUrl()).isEqualTo("https://jobs.northgate-systems.in/apply/R-1");
        assertThat(first.employmentTypeText()).isEqualTo("Full-time");
        assertThat(first.departmentText()).isEqualTo("Platform");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-09-15T10:00:00Z"));
        assertThat(first.rawPayload()).contains("\"R-1\"").doesNotContain("R-2");

        RawJobPosting second = postings.get(1);
        assertThat(second.locationText()).isEqualTo("Pune");
        assertThat(second.postedAt()).describedAs("epoch seconds").isEqualTo(Instant.ofEpochSecond(1757930400L));
        assertThat(second.applyUrl()).isNull();
    }

    @Test
    @DisplayName("a reading says how many postings had no id or title")
    void countsWhatItCouldNotRead() {
        feed("/feed/northgate", NESTED_FEED);

        ConfiguredJsonAdapter.Reading reading = adapter(nested()).read(source("custom-northgate", "northgate", 1));

        assertThat(reading.total()).isEqualTo(4);
        assertThat(reading.unreadable()).isEqualTo(2);
        assertThat(reading.postings()).extracting(RawJobPosting::externalId).containsExactly("R-1");
    }

    @Test
    @DisplayName("an apply-link template fills in the posting's id when the feed has no link")
    void applyTemplate() {
        feed("/all.json", """
                [{"slug":"platform engineer/1","name":"Platform Engineer","created":1757930400000}]""");
        ConfiguredJsonAdapter.Spec spec = new ConfiguredJsonAdapter.Spec("custom-flat", "Flat feed", null, null,
                "https://" + HOST + "/all.json", null, "/slug", "/name", null, null, null,
                "https://" + HOST + "/careers/{id}", "/created", null, null);

        List<RawJobPosting> postings = adapter(spec).fetchJobs(source("custom-flat", null, 200));

        assertThat(postings).singleElement().satisfies(posting -> {
            assertThat(posting.applyUrl()).isEqualTo("https://" + HOST + "/careers/platform+engineer%2F1");
            assertThat(posting.postedAt()).describedAs("epoch milliseconds")
                    .isEqualTo(Instant.ofEpochMilli(1757930400000L));
        });
    }

    @Test
    @DisplayName("the token is encoded into the endpoint, and a missing one is refused before any request")
    void token() {
        assertThat(ConfiguredJsonAdapter.endpoint("https://" + HOST + "/feed/{token}", "a b/c"))
                .isEqualTo("https://" + HOST + "/feed/a+b%2Fc");
        assertThat(ConfiguredJsonAdapter.endpoint("https://" + HOST + "/feed", null))
                .isEqualTo("https://" + HOST + "/feed");

        feed("/feed/", "[]");
        assertThatThrownBy(() -> adapter(nested()).fetchJobs(source("custom-northgate", " ", 200)))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("external identifier");
        assertThat(server.requestCount("/feed/")).isZero();
    }

    @Test
    @DisplayName("a response with no postings array where the definition says is a malformed response")
    void noArray() {
        feed("/feed/northgate", "{\"data\":{\"jobs\":{\"not\":\"a list\"}}}");

        assertThatThrownBy(() -> adapter(nested()).fetchJobs(source("custom-northgate", "northgate", 200)))
                .isInstanceOf(AdapterException.class)
                .satisfies(thrown -> assertThat(((AdapterException) thrown).getClassification())
                        .isEqualTo(FailureClassification.MALFORMED_RESPONSE));
    }

    @Test
    @DisplayName("requests go through the shared address check: another host is never contacted")
    void sharedTransport() {
        ConfiguredJsonAdapter.Spec elsewhere = new ConfiguredJsonAdapter.Spec("custom-x", "X", null, null,
                "https://feeds.elsewhere.in/jobs", null, "/id", "/title", null, null, null, null, null, null, null);
        feed("/jobs", "[{\"id\":\"1\",\"title\":\"Engineer\"}]");

        assertThatThrownBy(() -> adapter(elsewhere).fetchJobs(source("custom-x", null, 200)))
                .isInstanceOf(AdapterException.class);
        assertThat(server.requestCount("/jobs")).isZero();
    }

    @Test
    @DisplayName("claims only a source registered under its own key, never one by URL")
    void supportsOnlyItsKey() {
        ConfiguredJsonAdapter adapter = adapter(nested());

        assertThat(adapter.supports(source("custom-northgate", null, 1))).isTrue();
        assertThat(adapter.supports(source("custom-other", null, 1))).isFalse();
        assertThat(adapter.supports(new SourceConfiguration(UUID.randomUUID(), "x",
                "https://" + HOST + "/feed/northgate", null, null, 20, null, 1))).isFalse();
        assertThat(adapter.getMetadata().key()).isEqualTo("custom-northgate");
    }

    @Test
    @DisplayName("dotted paths become JSON Pointers; a pointer is kept as typed")
    void paths() {
        assertThat(ConfiguredJsonAdapter.pointer("data.jobs")).isEqualTo("/data/jobs");
        assertThat(ConfiguredJsonAdapter.pointer("locations.0.name")).isEqualTo("/locations/0/name");
        assertThat(ConfiguredJsonAdapter.pointer("/data/jobs")).isEqualTo("/data/jobs");
        assertThat(ConfiguredJsonAdapter.pointer("  ")).isNull();
    }
}
