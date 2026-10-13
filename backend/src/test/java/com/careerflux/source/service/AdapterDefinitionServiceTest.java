package com.careerflux.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.careerflux.audit.AuditService;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.UnsafeUrlException;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.repository.AdapterDefinitionRepository;
import com.careerflux.source.service.AdapterDefinitionService.Definition;
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
 * What a definition must be before it is saved, and what trying one does.
 *
 * <p>Requests, robots.txt included, are routed to the loopback server whatever
 * host they name, and only the fixture host passes the address check.
 */
class AdapterDefinitionServiceTest {

    private static final String HOST = "jobs.northgate-systems.in";

    private StreamingHttpServer server;
    private AdapterDefinitionService service;

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
        SimpleClientHttpRequestFactory loopback = new SimpleClientHttpRequestFactory();
        loopback.setConnectTimeout(Duration.ofSeconds(5));
        loopback.setReadTimeout(Duration.ofSeconds(5));
        OnlyFixtureHost validator = new OnlyFixtureHost();
        ClientHttpRequestFactory routing = (uri, method) -> {
            validator.validate(uri.toString());
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return loopback.createRequest(URI.create(server.url(path)), method);
        };
        RestClient client = RestClient.builder().requestFactory(routing).build();
        RobotsTxtService robots = new RobotsTxtService(client,
                new CareerFluxProperties(null, null, null,
                        new CareerFluxProperties.Sources("CareerFluxBot/0.1 (+https://careerflux.local/bot)",
                                Duration.ofSeconds(20), 20, Duration.ofHours(6)),
                        null, new CareerFluxProperties.Demo(false, false), null, true));
        service = new AdapterDefinitionService(mock(AdapterDefinitionRepository.class), client,
                new SourceRateLimiter(), validator, new ObjectMapper(), robots, mock(AuditService.class));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static Definition definition(String endpoint) {
        return new Definition(null, "Northgate feed", null, null, endpoint,
                "jobs", "id", "title", "city", null, "url", null, null, null, null);
    }

    private void feed() {
        server.fixed("/feed.json", 200, """
                {"jobs":[{"id":"R-1","title":"Platform Engineer","city":"Pune","url":"https://jobs.northgate-systems.in/R-1"},
                         {"id":"R-2","title":"Data Analyst"},
                         {"title":"No id"}]}""".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json");
    }

    private void robots(String rules) {
        server.fixed("/robots.txt", 200, rules.getBytes(StandardCharsets.UTF_8), "Content-Type", "text/plain");
    }

    @Test
    @DisplayName("trying a definition shows what it reads, and how much it could not")
    void previewReads() {
        robots("User-agent: *\nAllow: /\n");
        feed();

        var preview = service.preview(definition("https://" + HOST + "/feed.json"), null, "admin@careerflux.local");

        assertThat(preview.fetched()).isTrue();
        assertThat(preview.robotsStatus()).isEqualTo(RobotsStatus.ALLOWED);
        assertThat(preview.total()).isEqualTo(3);
        assertThat(preview.unreadable()).isEqualTo(1);
        assertThat(preview.postings()).extracting(posting -> posting.title())
                .containsExactly("Platform Engineer", "Data Analyst");
        assertThat(preview.postings().get(0).locationText()).isEqualTo("Pune");
    }

    @Test
    @DisplayName("a path robots.txt disallows is not fetched to try a definition")
    void previewObeysRobots() {
        robots("User-agent: *\nDisallow: /feed.json\n");
        feed();

        var preview = service.preview(definition("https://" + HOST + "/feed.json"), null, "admin@careerflux.local");

        assertThat(preview.fetched()).isFalse();
        assertThat(preview.robotsStatus()).isEqualTo(RobotsStatus.DISALLOWED);
        assertThat(preview.message()).startsWith("Not fetched:");
        assertThat(server.requestCount("/feed.json")).isZero();
    }

    @Test
    @DisplayName("a host whose robots.txt cannot be read is not fetched either")
    void previewUnreadableRobots() {
        server.fixed("/robots.txt", 503, new byte[0]);
        feed();

        var preview = service.preview(definition("https://" + HOST + "/feed.json"), null, "admin@careerflux.local");

        assertThat(preview.fetched()).isFalse();
        assertThat(server.requestCount("/feed.json")).isZero();
    }

    @Test
    @DisplayName("an endpoint with {token} needs a sample to try it")
    void previewNeedsToken() {
        assertThatThrownBy(() -> service.preview(definition("https://" + HOST + "/{token}/feed.json"), " ", "a"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("{token}");
    }

    @Test
    @DisplayName("only an https address on a public-looking host, with no credentials or other placeholders")
    void endpointRules() {
        assertThat(AdapterDefinitionService.endpointTemplate("https://boards.example.in/{token}/jobs"))
                .isEqualTo("https://boards.example.in/{token}/jobs");

        assertThatThrownBy(() -> AdapterDefinitionService.endpointTemplate("http://boards.example.in/jobs"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> AdapterDefinitionService.endpointTemplate("https://user:pw@boards.example.in/jobs"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("credentials");
        assertThatThrownBy(() -> AdapterDefinitionService.endpointTemplate("https://boards.example.in/{page}"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("placeholder");
        assertThatThrownBy(() -> AdapterDefinitionService.endpointTemplate("https://intranet/jobs"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("public host");
        assertThatThrownBy(() -> AdapterDefinitionService.endpointTemplate("https://boards.example.in:8443/jobs"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("port");
        assertThatThrownBy(() -> AdapterDefinitionService.endpointTemplate(" "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("every key is custom-, so none can take a built-in adapter's place")
    void keys() {
        assertThat(AdapterDefinitionService.normaliseKey(null, "Acme Jobs API")).isEqualTo("custom-acme-jobs-api");
        assertThat(AdapterDefinitionService.normaliseKey("greenhouse", "x")).isEqualTo("custom-greenhouse");
        assertThat(AdapterDefinitionService.normaliseKey("custom-acme", "x")).isEqualTo("custom-acme");
        assertThatThrownBy(() -> AdapterDefinitionService.normaliseKey("!!", "x"))
                .isInstanceOf(BadRequestException.class);
    }
}
