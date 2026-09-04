package com.careerflux.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.careerflux.config.BackgroundWorkGate;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.adapter.SourceHealthResult;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.repository.SourceHealthCheckRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * What a failure means, and what it should cost the source.
 *
 * <p>Today every failure is the same event. A board that has been deleted, a
 * board that is briefly overloaded, and a board that is politely asking us to
 * slow down all increment the same counter and walk the source toward
 * PENDING_REVIEW — out of service until a person looks at it.
 *
 * <p>That is wrong in one direction only. A 404 should retire a source
 * eventually; a 429 should not, because the source is working exactly as
 * intended and is telling us so. These tests pin the distinction before the
 * code can make it.
 */
class SourceFailureClassificationTest {

    private final JobSourceRepository sources = mock(JobSourceRepository.class);
    private final SourceHealthCheckRepository checks = mock(SourceHealthCheckRepository.class);
    private final AdapterRegistry adapters = mock(AdapterRegistry.class);
    private final SourceLifecycleService lifecycle = mock(SourceLifecycleService.class);

    private SourceHealthService service() {
        CareerFluxProperties properties = new Binder(new MapConfigurationPropertySource(
                new java.util.HashMap<String, Object>(Map.of(
                        "careerflux.security.jwt.secret", "a-test-secret-that-is-long-enough-0123456789",
                        "careerflux.sources.user-agent", "CareerFluxBot/0.1 (test)",
                        "careerflux.ingestion.transport", "in-process",
                        "careerflux.matching.batch-size", "400"))))
                .bind("careerflux", CareerFluxProperties.class).get();
        when(checks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new SourceHealthService(sources, checks, adapters, lifecycle,
                properties, new BackgroundWorkGate(properties));
    }

    private JobSource activeSource() {
        JobSource source = new JobSource();
        source.setId(UUID.randomUUID());
        source.setName("Test board");
        source.setBaseUrl("https://boards-api.greenhouse.io/v1/boards/test/jobs");
        source.setState(SourceState.ACTIVE);
        return source;
    }

    /** Feeds the same failure repeatedly, as a persistently failing source would. */
    private JobSource failRepeatedly(SourceHealthResult result, int times) {
        SourceHealthService health = service();
        JobSource source = activeSource();
        for (int i = 0; i < times; i++) {
            health.record(source, result);
        }
        return source;
    }

    @Nested
    @DisplayName("a source that is gone")
    class PermanentFailure {

        @Test
        @DisplayName("repeated 404s eventually take the source out of service")
        void notFoundEscalates() {
            // Unchanged behaviour, asserted so the fix cannot loosen it.
            failRepeatedly(SourceHealthResult.failing(404, 120, "Source returned HTTP 404."), 8);

            verify(lifecycle).transition(any(), eq(SourceState.PENDING_REVIEW), any(), any());
        }

        @Test
        @DisplayName("repeated 404s degrade the source on the way there")
        void notFoundDegradesFirst() {
            failRepeatedly(SourceHealthResult.failing(404, 120, "Source returned HTTP 404."), 3);

            verify(lifecycle).transition(any(), eq(SourceState.DEGRADED), any(), any());
            verify(lifecycle, never()).transition(any(), eq(SourceState.PENDING_REVIEW), any(), any());
        }
    }

    @Nested
    @DisplayName("a source that is throttling us")
    class Throttled {

        @Test
        @DisplayName("repeated 429s must not take a working source out of service")
        void throttlingDoesNotRetireTheSource() {
            // The M3 reproduction. A board answering 429 is healthy and telling
            // us to slow down; pulling it out of service and demanding a human
            // look at it is the wrong response to being asked to wait.
            failRepeatedly(SourceHealthResult.throttled(429, "Source returned HTTP 429.", null), 8);

            verify(lifecycle, never()).transition(any(), eq(SourceState.PENDING_REVIEW), any(), any());
        }

        @Test
        @DisplayName("repeated 429s still degrade it, so the throttling is visible")
        void throttlingIsStillVisible() {
            // Not silently ignored either: an operator should be able to see
            // that a source is persistently rate-limiting us.
            failRepeatedly(SourceHealthResult.throttled(429, "Source returned HTTP 429.", null), 3);

            verify(lifecycle).transition(any(), eq(SourceState.DEGRADED), any(), any());
        }

        @Test
        @DisplayName("a 429 still records an observation")
        void throttlingIsRecorded() {
            SourceHealthService health = service();
            JobSource source = activeSource();
            health.record(source, SourceHealthResult.throttled(429, "Source returned HTTP 429.", null));

            verify(checks).save(any());
            assertThat(source.getLastHealthCheckAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("a source that is temporarily broken")
    class TransientFailure {

        @Test
        @DisplayName("repeated 503s must not take the source out of service")
        void serverErrorsDoNotRetire() {
            failRepeatedly(SourceHealthResult.transientFailure(503, 90, "Source returned HTTP 503."), 8);

            verify(lifecycle, never()).transition(any(), eq(SourceState.PENDING_REVIEW), any(), any());
        }

        @Test
        @DisplayName("a timeout must not take the source out of service")
        void timeoutsDoNotRetire() {
            failRepeatedly(SourceHealthResult.transientFailure(null, null, "Read timed out."), 8);

            verify(lifecycle, never()).transition(any(), eq(SourceState.PENDING_REVIEW), any(), any());
        }
    }

    @Nested
    @DisplayName("our own pacing")
    class LocalRateLimit {

        @Test
        @DisplayName("choosing not to call is not a source failure")
        void localWaitIsNotAFailure() {
            // The M4 reproduction. SourceRateLimiter refusing to let a request
            // through is CareerFlux's own decision. Recording it against the
            // source says the board was unreachable, which is simply untrue —
            // we never asked it anything.
            SourceHealthService health = service();
            JobSource source = activeSource();
            int before = source.getConsecutiveFailures();

            health.record(source, SourceHealthResult.notAttempted(
                    "Rate limit for this source is saturated; skipping this attempt."));

            assertThat(source.getConsecutiveFailures())
                    .describedAs("our own pacing must not count against the source")
                    .isEqualTo(before);
            verify(lifecycle, never()).transition(any(), any(), any(), any());
        }

        @Test
        @DisplayName("repeated pacing never degrades the source")
        void repeatedLocalWaitsNeverDegrade() {
            JobSource source = failRepeatedly(SourceHealthResult.notAttempted("paced"), 20);

            assertThat(source.getConsecutiveFailures()).isZero();
            verify(lifecycle, never()).transition(any(), any(), any(), any());
        }

        @Test
        @DisplayName("pacing does not overwrite a healthy status with a failing one")
        void pacingDoesNotDowngradeHealth() {
            SourceHealthService health = service();
            JobSource source = activeSource();
            health.record(source, SourceHealthResult.healthy(200, 50, 12));
            var healthyStatus = source.getHealthStatus();

            health.record(source, SourceHealthResult.notAttempted("paced"));

            assertThat(source.getHealthStatus())
                    .describedAs("a skipped attempt tells us nothing new about the source")
                    .isEqualTo(healthyStatus);
        }
    }

    @Nested
    @DisplayName("recovery")
    class Recovery {

        @Test
        @DisplayName("one success clears the failure count however it failed before")
        void successResets() {
            SourceHealthService health = service();
            JobSource source = activeSource();
            for (int i = 0; i < 5; i++) {
                health.record(source, SourceHealthResult.throttled(429, "throttled", null));
            }
            assertThat(source.getConsecutiveFailures()).isPositive();

            health.record(source, SourceHealthResult.healthy(200, 40, 30));

            assertThat(source.getConsecutiveFailures()).isZero();
        }
    }
}
