package com.careerflux.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.domain.RobotsStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * robots.txt evaluation, tested against the shapes real sites publish.
 *
 * <p>Only the parser is exercised here; the fetch is not. Getting the matching
 * rules wrong is the failure that would actually matter, because it decides
 * whether CareerFlux reads something it was asked not to.
 */
class RobotsTxtServiceTest {

    private final RobotsTxtService service = new RobotsTxtService(
            RestClient.create(),
            new CareerFluxProperties(
                    null,
                    null,
                    null,
                    new CareerFluxProperties.Sources(
                            "CareerFluxBot/0.1 (+https://careerflux.local/bot)",
                            Duration.ofSeconds(20), 20, Duration.ofHours(6)),
                    null,
                    new CareerFluxProperties.Demo(false, false),
                    null /* rate limits: not exercised here */,
                    true /* background work: not exercised here */));

    @Test
    @DisplayName("a 404 means no rules were published")
    void notFoundMeansNoRules() {
        var fetch = RobotsTxtService.fromStatus(404);
        assertThat(fetch).isNotNull();
        assertThat(fetch.notFound()).isTrue();
    }

    @Test
    @DisplayName("a 401 or 403 also means no rules were published, not a refusal")
    void authenticatedRobotsIsNotADisallow() {
        // RFC 9309 section 2.3.1.3 groups the whole 4xx range as "unavailable".
        // Ashby's API host answers 401 here, and reading that as a blocker made
        // every Ashby-hosted job board impossible to activate.
        for (int status : new int[] {400, 401, 403, 410, 429, 451}) {
            var fetch = RobotsTxtService.fromStatus(status);
            assertThat(fetch).as("HTTP %s", status).isNotNull();
            assertThat(fetch.notFound()).as("HTTP %s should read as no rules published", status).isTrue();
        }
    }

    @Test
    @DisplayName("a server error blocks, because rules may exist and cannot be read")
    void serverErrorFailsClosed() {
        // Section 2.3.1.4: "unreachable" is the case that should assume a
        // complete disallow, and it still does.
        for (int status : new int[] {500, 502, 503}) {
            var fetch = RobotsTxtService.fromStatus(status);
            assertThat(fetch).as("HTTP %s", status).isNotNull();
            assertThat(fetch.notFound()).as("HTTP %s must not read as no rules", status).isFalse();
            assertThat(fetch.body()).as("HTTP %s must not yield a body", status).isNull();
        }
    }

    @Test
    @DisplayName("a success is left for the parser to interpret")
    void successIsParsed() {
        assertThat(RobotsTxtService.fromStatus(200)).isNull();
        assertThat(RobotsTxtService.fromStatus(204)).isNull();
    }

    @Test
    @DisplayName("an empty rule set allows everything")
    void emptyAllows() {
        var result = service.parseAndEvaluate("", "https://x.invalid/robots.txt", "/jobs");
        assertThat(result.status()).isEqualTo(RobotsStatus.ALLOWED);
    }

    @Test
    @DisplayName("a wildcard disallow blocks a matching path")
    void wildcardDisallow() {
        String body = """
                User-agent: *
                Disallow: /jobs
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/jobs").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
        assertThat(service.parseAndEvaluate(body, "u", "/jobs/123").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
        assertThat(service.parseAndEvaluate(body, "u", "/careers").status())
                .isEqualTo(RobotsStatus.ALLOWED);
    }

    @Test
    @DisplayName("an empty Disallow means nothing is disallowed")
    void emptyDisallowAllowsAll() {
        String body = """
                User-agent: *
                Disallow:
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/anything").status())
                .isEqualTo(RobotsStatus.ALLOWED);
    }

    @Test
    @DisplayName("the longest matching rule wins, so a nested Allow beats a broad Disallow")
    void longestMatchWins() {
        String body = """
                User-agent: *
                Disallow: /
                Allow: /jobs/public
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/jobs/public/1").status())
                .isEqualTo(RobotsStatus.ALLOWED);
        assertThat(service.parseAndEvaluate(body, "u", "/admin").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
    }

    @Test
    @DisplayName("a rule group naming our agent overrides the wildcard group")
    void specificAgentGroupWins() {
        String body = """
                User-agent: *
                Disallow: /

                User-agent: careerfluxbot
                Allow: /jobs
                Disallow: /admin
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/jobs").status())
                .isEqualTo(RobotsStatus.ALLOWED);
        assertThat(service.parseAndEvaluate(body, "u", "/admin").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
    }

    @Test
    @DisplayName("the * wildcard inside a pattern is honoured")
    void patternWildcard() {
        String body = """
                User-agent: *
                Disallow: /*/private
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/team/private").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
        assertThat(service.parseAndEvaluate(body, "u", "/team/public").status())
                .isEqualTo(RobotsStatus.ALLOWED);
    }

    @Test
    @DisplayName("a trailing $ anchors the pattern to the end of the path")
    void endAnchor() {
        String body = """
                User-agent: *
                Disallow: /jobs$
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/jobs").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
        // Anchored, so the deeper path is not covered by this rule.
        assertThat(service.parseAndEvaluate(body, "u", "/jobs/123").status())
                .isEqualTo(RobotsStatus.ALLOWED);
    }

    @Test
    @DisplayName("crawl-delay is read and reported")
    void crawlDelay() {
        String body = """
                User-agent: *
                Crawl-delay: 10
                Disallow: /admin
                """;
        var result = service.parseAndEvaluate(body, "u", "/jobs");
        assertThat(result.crawlDelaySeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("comments and unknown directives are ignored")
    void ignoresNoise() {
        String body = """
                # A comment
                Sitemap: https://x.invalid/sitemap.xml
                User-agent: *   # trailing comment
                Disallow: /admin
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/jobs").status())
                .isEqualTo(RobotsStatus.ALLOWED);
        assertThat(service.parseAndEvaluate(body, "u", "/admin").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
    }

    @Test
    @DisplayName("consecutive user-agent lines share one rule group")
    void groupedAgents() {
        String body = """
                User-agent: somebot
                User-agent: careerfluxbot
                Disallow: /nope
                """;
        assertThat(service.parseAndEvaluate(body, "u", "/nope").status())
                .isEqualTo(RobotsStatus.DISALLOWED);
    }

    @Test
    @DisplayName("path matching is prefix-based and case sensitive")
    void prefixMatching() {
        assertThat(service.matches("/jobs", "/jobs/senior-engineer")).isTrue();
        assertThat(service.matches("/jobs", "/job")).isFalse();
        assertThat(service.matches("/Jobs", "/jobs")).isFalse();
    }

    @Test
    @DisplayName("an unparseable URL is unavailable rather than allowed")
    void badUrlIsUnavailable() {
        for (String url : List.of("not a url", "/relative/only")) {
            assertThat(service.evaluate(url).status())
                    .as("%s", url)
                    .isEqualTo(RobotsStatus.UNAVAILABLE);
        }
    }
}
