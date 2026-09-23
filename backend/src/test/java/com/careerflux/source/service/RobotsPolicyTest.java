package com.careerflux.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.support.StreamingHttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One reading of a host's robots.txt, applied to a whole run (Phase 2, stage 3).
 *
 * <p>A run may look at dozens of pages on one host. These tests hold it to asking
 * the host for its rules once, judging every URL by that one reading, and never
 * making a request to decide — while the rules themselves mean exactly what they
 * meant before.
 *
 * <p>Nothing here prints a body: assertions are on verdicts, counts and flags.
 */
class RobotsPolicyTest {

    private static final String RULES = """
            User-agent: *
            Disallow: /admin
            Allow: /admin/public
            Disallow: /*.pdf$
            Crawl-delay: 9

            User-agent: CareerFluxBot
            Disallow: /private
            Allow: /private/jobs
            Crawl-delay: 5
            """;

    /** Far past the robots ceiling, and small enough that no failure could fill a disk. */
    private static final long LARGE = 32L * 1024 * 1024;

    private static final long BUFFER_SLACK = 4L * 1024 * 1024;

    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    private StreamingHttpServer server;
    private RobotsTxtService robots;

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
        robots = new RobotsTxtService(
                RestClient.builder().requestFactory(loopback).build(),
                new CareerFluxProperties(null, null, null,
                        new CareerFluxProperties.Sources("CareerFluxBot/0.1 (+https://careerflux.local/bot)",
                                Duration.ofSeconds(20), 20, Duration.ofHours(6)),
                        null, new CareerFluxProperties.Demo(false, false), null, true));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private boolean robotsPublished;

    /** Publishes rules, replacing any already published, and takes one reading of them. */
    private RobotsTxtService.RobotsPolicy policyWith(String rules) {
        byte[] body = rules.getBytes(StandardCharsets.US_ASCII);
        if (robotsPublished) {
            server.replaceFixed("/robots.txt", 200, body, "Content-Type", "text/plain");
        } else {
            server.fixed("/robots.txt", 200, body, "Content-Type", "text/plain");
        }
        robotsPublished = true;
        return assertTimeoutPreemptively(Duration.ofSeconds(20), () -> robots.policyFor(server.url("/jobs")));
    }

    private String url(String path) {
        return server.url(path);
    }

    @Nested
    @DisplayName("one reading, a whole run")
    class OnePerRun {

        @Test
        @DisplayName("ten job URLs ask the host for its rules once")
        void oneRequestForManyUrls() {
            RobotsTxtService.RobotsPolicy policy = policyWith(RULES);

            for (int i = 1; i <= 10; i++) {
                policy.isAllowed(url("/jobs/" + i));
            }

            assertThat(server.requestCount("/robots.txt")).describedAs("robots.txt requests").isEqualTo(1);
        }

        @Test
        @DisplayName("a refused URL costs no request either")
        void refusedUrlsCostNothing() {
            RobotsTxtService.RobotsPolicy policy = policyWith(RULES);

            for (int i = 1; i <= 10; i++) {
                assertThat(policy.isAllowed(url("/private/" + i))).isFalse();
            }

            assertThat(server.requestCount("/robots.txt")).isEqualTo(1);
        }

        @Test
        @DisplayName("a second run takes its own reading, so an edited file takes effect")
        void secondRunReadsAgain() {
            RobotsTxtService.RobotsPolicy first = policyWith("User-agent: *\nDisallow: /private\n");
            assertThat(first.isAllowed(url("/private/1"))).isFalse();
            assertThat(first.isAllowed(url("/jobs/1"))).isTrue();

            server.replaceFixed("/robots.txt", 200, "User-agent: *\nDisallow: /jobs\n".getBytes(StandardCharsets.US_ASCII));
            RobotsTxtService.RobotsPolicy second = robots.policyFor(url("/jobs"));

            assertThat(second.isAllowed(url("/jobs/1"))).isFalse();
            assertThat(second.isAllowed(url("/private/1"))).isTrue();
            assertThat(server.requestCount("/robots.txt")).isEqualTo(2);
        }

        @Test
        @DisplayName("a file edited mid-run does not change what the run already decided")
        void theRunIsConsistent() {
            RobotsTxtService.RobotsPolicy policy = policyWith("User-agent: *\nDisallow: /private\n");
            assertThat(policy.isAllowed(url("/private/1"))).isFalse();

            server.replaceFixed("/robots.txt", 200, "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.US_ASCII));

            assertThat(policy.isAllowed(url("/private/2"))).describedAs("same rules all run").isFalse();
            assertThat(server.requestCount("/robots.txt")).isEqualTo(1);
        }

        @Test
        @DisplayName("rules belong to the host that published them and judge nothing else")
        void rulesBelongToTheirHost() {
            RobotsTxtService.RobotsPolicy policy = policyWith(RULES);

            RobotsTxtService.Evaluation elsewhere = policy.evaluate("https://another.example/jobs/1");

            assertThat(elsewhere.status()).isEqualTo(RobotsStatus.UNAVAILABLE);
            assertThat(policy.isAllowed("https://another.example/jobs/1")).isFalse();
            assertThat(server.requestCount("/robots.txt")).isEqualTo(1);
        }

        @Test
        @DisplayName("asking about one URL at a time still reads the file each time, as it always did")
        void singleUrlEvaluationIsUnchanged() {
            server.fixed("/robots.txt", 200, RULES.getBytes(StandardCharsets.US_ASCII));

            assertThat(robots.evaluate(url("/private/1")).status()).isEqualTo(RobotsStatus.DISALLOWED);
            assertThat(robots.evaluate(url("/jobs/1")).status()).isEqualTo(RobotsStatus.ALLOWED);

            assertThat(server.requestCount("/robots.txt")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("what the rules say")
    class Semantics {

        @Test
        @DisplayName("our own group wins over the wildcard group, rule for rule")
        void userAgentGroups() {
            RobotsTxtService.RobotsPolicy policy = policyWith(RULES);

            assertThat(policy.isAllowed(url("/private/1"))).isFalse();
            assertThat(policy.isAllowed(url("/private/jobs/1"))).isTrue();
            // The wildcard group's rules are set aside once a group names us.
            assertThat(policy.isAllowed(url("/admin"))).isTrue();
            assertThat(policy.isAllowed(url("/handbook.pdf"))).isTrue();
        }

        @Test
        @DisplayName("the wildcard group applies when no group names us, with longest match and patterns")
        void wildcardGroupRules() {
            RobotsTxtService.RobotsPolicy policy = policyWith("""
                    User-agent: *
                    Disallow: /admin
                    Allow: /admin/public
                    Disallow: /*.pdf$
                    Crawl-delay: 2
                    """);

            assertThat(policy.isAllowed(url("/admin"))).isFalse();
            assertThat(policy.isAllowed(url("/admin/public/1"))).describedAs("longest match wins").isTrue();
            assertThat(policy.isAllowed(url("/files/brochure.pdf"))).describedAs("pattern and anchor").isFalse();
            assertThat(policy.isAllowed(url("/files/brochure.pdf.html"))).isTrue();
            assertThat(policy.isAllowed(url("/jobs/1"))).isTrue();
        }

        @Test
        @DisplayName("a crawl-delay is read from the group that applies to us")
        void crawlDelay() {
            assertThat(policyWith(RULES).crawlDelaySeconds()).isEqualTo(5);
            assertThat(policyWith("User-agent: *\nCrawl-delay: 7\n").crawlDelaySeconds()).isEqualTo(7);
            assertThat(policyWith("User-agent: *\nDisallow: /x\n").crawlDelaySeconds()).isNull();
        }

        @Test
        @DisplayName("every verdict is the one the parser gives, path by path")
        void verdictsMatchTheParser() {
            RobotsTxtService.RobotsPolicy policy = policyWith(RULES);

            for (String path : List.of("/private", "/private/jobs/1", "/admin", "/jobs/1", "/a.pdf")) {
                assertThat(policy.evaluate(url(path)))
                        .describedAs(path)
                        .isEqualTo(robots.parseAndEvaluate(RULES, url("/robots.txt"), path));
            }
        }
    }

    @Nested
    @DisplayName("when the file is missing, broken or enormous")
    class Edges {

        @Test
        @DisplayName("no robots.txt means no rules, and every page may be read")
        void missing() {
            server.fixed("/robots.txt", 404, "not found".getBytes(StandardCharsets.US_ASCII));
            RobotsTxtService.RobotsPolicy policy = robots.policyFor(url("/jobs"));

            assertThat(policy.evaluate(url("/jobs/1")).status()).isEqualTo(RobotsStatus.NOT_PUBLISHED);
            assertThat(policy.isAllowed(url("/jobs/1"))).isTrue();
            assertThat(policy.isAllowed(url("/anything"))).isTrue();
        }

        @Test
        @DisplayName("a file that cannot be read refuses every page, as it does everywhere else")
        void unreadable() {
            server.fixed("/robots.txt", 503, "down".getBytes(StandardCharsets.US_ASCII));
            RobotsTxtService.RobotsPolicy policy = robots.policyFor(url("/jobs"));

            assertThat(policy.evaluate(url("/jobs/1")).status()).isEqualTo(RobotsStatus.UNAVAILABLE);
            assertThat(policy.isAllowed(url("/jobs/1"))).isFalse();
        }

        @Test
        @DisplayName("nonsense in the file is read for what it is, not guessed at")
        void malformed() {
            RobotsTxtService.RobotsPolicy policy = policyWith("<html>not robots</html>\n\tDisallow /x\n");

            assertThat(policy.evaluate(url("/x")))
                    .isEqualTo(robots.parseAndEvaluate("<html>not robots</html>\n\tDisallow /x\n",
                            url("/robots.txt"), "/x"));
            assertThat(policy.isAllowed(url("/x"))).isTrue();
        }

        @Test
        @DisplayName("only the first 512 KiB is read, so a rule past the ceiling is not in force")
        void boundedAtItsCeiling() {
            byte[] body = new byte[RobotsTxtService.MAX_ROBOTS_BYTES + 4096];
            Arrays.fill(body, (byte) 'x');
            byte[] head = "User-agent: *\nDisallow: /early\n#".getBytes(StandardCharsets.US_ASCII);
            byte[] tail = "\nDisallow: /late\n".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(tail, 0, body, body.length - tail.length, tail.length);
            server.fixed("/robots.txt", 200, body);

            RobotsTxtService.RobotsPolicy policy = robots.policyFor(url("/jobs"));

            assertThat(policy.isAllowed(url("/early/1"))).describedAs("rule inside the ceiling").isFalse();
            assertThat(policy.isAllowed(url("/late/1"))).describedAs("rule past the ceiling").isTrue();
        }

        @Test
        @DisplayName("a file that will not end is cut off at the ceiling, and its rules still apply")
        void endless() throws InterruptedException {
            server.page("/robots.txt", 200, "User-agent: *\nDisallow: /private\n".getBytes(StandardCharsets.US_ASCII),
                    LARGE);

            RobotsTxtService.RobotsPolicy policy = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> robots.policyFor(url("/jobs")));

            assertThat(policy.isAllowed(url("/private/1"))).isFalse();
            assertThat(policy.isAllowed(url("/jobs/1"))).isTrue();
            assertThat(server.awaitFinished("/robots.txt", PROMPTLY)).describedAs("handler finished").isTrue();
            assertThat(server.sentEverything("/robots.txt")).describedAs("server sent all 32 MiB").isFalse();
            assertThat(server.bytesSent("/robots.txt")).describedAs("bytes the server managed to send")
                    .isLessThan(RobotsTxtService.MAX_ROBOTS_BYTES + BUFFER_SLACK);
        }

        @Test
        @DisplayName("a URL that is not a URL is refused rather than guessed at, without a request")
        void unusableUrls() {
            RobotsTxtService.RobotsPolicy policy = robots.policyFor("not a url at all");

            assertThat(policy.robotsUrl()).isNull();
            assertThat(policy.isAllowed(url("/jobs/1"))).isFalse();
            assertThat(server.requestCount("/robots.txt")).isZero();
        }
    }
}
