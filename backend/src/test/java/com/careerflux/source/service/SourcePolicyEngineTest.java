package com.careerflux.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy gate is the single most important piece of compliance logic in
 * CareerFlux. Each test here corresponds to a way a source could wrongly be
 * allowed through.
 */
class SourcePolicyEngineTest {

    private final SourcePolicyEngine engine = new SourcePolicyEngine();

    private JobSource source;
    private SourceAccessPolicy policy;

    @BeforeEach
    void setUp() {
        source = new JobSource();
        source.setName("Example board");
        source.setBaseUrl("https://example.invalid/jobs");
        source.setSourceType(SourceType.ATS_PUBLIC_API);
        source.setRateLimitPerMinute(20);

        // A policy record that passes every gate, so each test can break exactly one.
        policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.ALLOWED);
        policy.setRobotsCheckedAt(Instant.now());
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("ops@careerflux.local");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_API);
        source.setAccessPolicy(policy);
    }

    @Test
    @DisplayName("a fully reviewed public API passes")
    void passesWhenEverythingIsSatisfied() {
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.blockers()).isEmpty();
    }

    @Test
    @DisplayName("a missing policy record is a blocker, not a pass")
    void noPolicyRecordFails() {
        source.setAccessPolicy(null);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("No access policy"));
    }

    @Test
    @DisplayName("robots.txt disallowing the path blocks the source")
    void robotsDisallowedFails() {
        policy.setRobotsStatus(RobotsStatus.DISALLOWED);
        policy.setRobotsRule("Disallow: /jobs");
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("robots.txt disallows"));
    }

    @Test
    @DisplayName("an unreadable robots.txt is treated as a refusal, not as permission")
    void unreadableRobotsFails() {
        policy.setRobotsStatus(RobotsStatus.UNAVAILABLE);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("an unchecked robots.txt blocks activation")
    void uncheckedRobotsFails() {
        policy.setRobotsStatus(RobotsStatus.NOT_CHECKED);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("no published robots.txt passes, but warns that there is no explicit permission")
    void missingRobotsPassesWithWarning() {
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.warnings()).anyMatch(warning -> warning.contains("No robots.txt"));
    }

    @Test
    @DisplayName("terms that prohibit automated access can never be overridden")
    void prohibitedTermsFail() {
        policy.setTosStatus(TosStatus.PROHIBITED);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("prohibit automated access"));
    }

    @Test
    @DisplayName("ambiguous terms are treated as a refusal")
    void unclearTermsFail() {
        policy.setTosStatus(TosStatus.UNCLEAR);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("unreviewed terms block activation")
    void unreviewedTermsFail() {
        policy.setTosStatus(TosStatus.NOT_REVIEWED);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("a terms review with no named reviewer is not a review")
    void unattributedReviewFails() {
        policy.setTosReviewedBy(null);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("not attributed"));
    }

    @Test
    @DisplayName("anything requiring a login is permanently ineligible")
    void authenticationRequiredFails() {
        policy.setRequiresAuthentication(true);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("requires authentication"));
    }

    @Test
    @DisplayName("a CAPTCHA is a disqualifier, never an obstacle to solve")
    void captchaFails() {
        policy.setRequiresCaptcha(true);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("CAPTCHA"));
    }

    @Test
    @DisplayName("anti-bot protection blocks the source")
    void antiBotFails() {
        policy.setHasAntiBot(true);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("paywalled content is not collected")
    void paywallFails() {
        policy.setPaywalled(true);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("a source with no adapter cannot be called active")
    void missingAdapterFails() {
        var verdict = engine.evaluate(source, false);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("No adapter"));
    }

    @Test
    @DisplayName("an unclassified source type blocks activation")
    void unknownTypeFails() {
        source.setSourceType(SourceType.UNKNOWN);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("unlimited polling is not acceptable use")
    void missingRateLimitFails() {
        source.setRateLimitPerMinute(0);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("rate limit"));
    }

    @Test
    @DisplayName("a rate limit faster than the declared crawl-delay is rejected")
    void rateLimitMustRespectCrawlDelay() {
        policy.setCrawlDelaySeconds(30);
        source.setRateLimitPerMinute(20);
        var verdict = engine.evaluate(source, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.blockers()).anyMatch(blocker -> blocker.contains("crawl-delay"));

        source.setRateLimitPerMinute(2);
        assertThat(engine.evaluate(source, true).passed()).isTrue();
    }

    @Test
    @DisplayName("a restricted access route is rejected")
    void restrictedRouteFails() {
        policy.setAccessPolicy(AccessPolicyType.RESTRICTED);
        assertThat(engine.evaluate(source, true).passed()).isFalse();
    }

    @Test
    @DisplayName("every unmet condition is reported at once, not one at a time")
    void reportsAllBlockersTogether() {
        policy.setRobotsStatus(RobotsStatus.DISALLOWED);
        policy.setTosStatus(TosStatus.PROHIBITED);
        policy.setRequiresAuthentication(true);
        var verdict = engine.evaluate(source, false);
        assertThat(verdict.blockers()).hasSizeGreaterThanOrEqualTo(4);
    }
}
