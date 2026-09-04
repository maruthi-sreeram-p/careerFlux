package com.careerflux.source.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.careerflux.common.TextUtils;
import com.careerflux.config.BackgroundWorkGate;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.adapter.FailureClassification;
import com.careerflux.source.adapter.JobSourceAdapter;
import com.careerflux.source.adapter.SourceConfiguration;
import com.careerflux.source.adapter.SourceHealthResult;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceHealthCheck;
import com.careerflux.source.domain.SourceHealthStatus;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.repository.SourceHealthCheckRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watches whether sources still work.
 *
 * <p>Health is always the result of an actual probe. A source that has never been
 * checked stays {@link SourceHealthStatus#UNKNOWN} and the UI says "not yet
 * checked" — it is never optimistically shown as healthy.
 *
 * <p>Sustained failure has consequences: three consecutive failures degrade a
 * source, and continued failure past that pulls it out of ACTIVE into
 * PENDING_REVIEW so a person looks at it rather than the system silently serving
 * stale jobs forever.
 */
@Service
public class SourceHealthService {

    private static final Logger log = LoggerFactory.getLogger(SourceHealthService.class);
    private static final int DEGRADE_AFTER_FAILURES = 3;
    private static final int REVIEW_AFTER_FAILURES = 8;
    private static final int HEALTH_HISTORY_DAYS = 30;
    private static final int MAX_CHECKS_PER_CYCLE = 25;

    private final JobSourceRepository sourceRepository;
    private final SourceHealthCheckRepository healthCheckRepository;
    private final AdapterRegistry adapterRegistry;
    private final SourceLifecycleService lifecycleService;
    private final CareerFluxProperties properties;
    private final BackgroundWorkGate gate;

    public SourceHealthService(JobSourceRepository sourceRepository,
                               SourceHealthCheckRepository healthCheckRepository,
                               AdapterRegistry adapterRegistry,
                               SourceLifecycleService lifecycleService,
                               CareerFluxProperties properties,
                               BackgroundWorkGate gate) {
        this.sourceRepository = sourceRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.adapterRegistry = adapterRegistry;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
        this.gate = gate;
    }

    /** Probes one source and records what came back. */
    @Transactional
    public SourceHealthCheck check(JobSource source) {
        JobSourceAdapter adapter = adapterRegistry.find(source.getAdapterKey()).orElse(null);
        SourceHealthResult result;

        if (adapter == null) {
            result = SourceHealthResult.unmeasurable(
                    "No adapter is registered for this source, so its health cannot be measured.");
        } else if (!source.getState().permitsFetching()) {
            result = SourceHealthResult.unmeasurable(
                    "Source is in state " + source.getState() + " and is not contacted.");
        } else {
            try {
                result = adapter.checkHealth(SourceConfiguration.from(source, 1));
            } catch (RuntimeException ex) {
                log.warn("Health probe for {} threw unexpectedly: {}", source.getId(), ex.getMessage());
                result = SourceHealthResult.unreachable("Probe failed: " + ex.getMessage());
            }
        }

        return record(source, result);
    }

    /**
     * Records what a probe or a sync observed, and applies the consequences.
     *
     * <p>An observation CareerFlux never made is not recorded at all. When our
     * own pacing declines to send a request, nothing happened to the source, and
     * writing a health row would claim otherwise — that was the defect where a
     * busy source degraded itself purely because we were being polite to it.
     */
    @Transactional
    public SourceHealthCheck record(JobSource source, SourceHealthResult result) {
        if (result.classification() == FailureClassification.NOT_ATTEMPTED) {
            log.debug("Not recording health for source {}: {}", source.getId(), result.message());
            return null;
        }
        SourceHealthCheck check = new SourceHealthCheck();
        check.setSource(source);
        check.setStatus(result.status());
        check.setHttpStatus(result.httpStatus());
        check.setLatencyMs(result.latencyMs());
        check.setJobsSeen(result.jobsSeen());
        check.setMessage(TextUtils.truncate(result.message(), 600));
        check.setCheckedAt(Instant.now());
        healthCheckRepository.save(check);

        source.setHealthStatus(result.status());
        source.setLastHealthCheckAt(check.getCheckedAt());

        boolean failed = result.status() == SourceHealthStatus.FAILING
                || result.status() == SourceHealthStatus.UNREACHABLE
                || result.status() == SourceHealthStatus.DEGRADED;
        if (failed) {
            source.setConsecutiveFailures(source.getConsecutiveFailures() + 1);
            applyFailureConsequences(source, result.classification());
        } else if (result.status() == SourceHealthStatus.HEALTHY) {
            if (source.getConsecutiveFailures() > 0) {
                log.info("Source {} recovered after {} failures", source.getId(), source.getConsecutiveFailures());
            }
            source.setConsecutiveFailures(0);
            if (source.getState() == SourceState.DEGRADED) {
                lifecycleService.transition(source, SourceState.ACTIVE, "health-monitor",
                        "Recovered: a health probe succeeded.");
            }
        }

        sourceRepository.save(source);
        return check;
    }

    /**
     * What repeated failure costs the source, which depends on what kind it is.
     *
     * <p>Only failures that will keep happening reach PENDING_REVIEW. A source
     * that throttles us or has a bad afternoon still degrades — visible to an
     * operator, still retried on schedule — but is never pulled out of service
     * and queued for human attention it does not need. Retiring the busiest
     * boards for answering 429 was precisely backwards.
     */
    private void applyFailureConsequences(JobSource source, FailureClassification classification) {
        int failures = source.getConsecutiveFailures();
        boolean mayEscalate = classification == null || classification.escalatesToReview();
        if (mayEscalate && failures >= REVIEW_AFTER_FAILURES
                && source.getState().canTransitionTo(SourceState.PENDING_REVIEW)) {
            lifecycleService.transition(source, SourceState.PENDING_REVIEW, "health-monitor",
                    failures + " consecutive failures. Pulled out of service pending review.");
        } else if (failures >= DEGRADE_AFTER_FAILURES && source.getState() == SourceState.ACTIVE) {
            lifecycleService.transition(source, SourceState.DEGRADED, "health-monitor",
                    failures + " consecutive failures.");
        }
    }

    /**
     * Periodic sweep over the sources whose last check is older than the configured
     * interval. Bounded per cycle so a large registry never produces a burst of
     * outbound requests.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    @Transactional
    public void monitorDueSources() {
        // Makes outbound requests to third parties, so it is ingestion-gated.
        if (!gate.permitsScheduledIngestion()) {
            return;
        }
        Instant threshold = Instant.now().minus(properties.sources().healthCheckInterval());
        List<JobSource> due = sourceRepository.findDueForHealthCheck(
                EnumSet.of(SourceState.ACTIVE, SourceState.DEGRADED),
                threshold,
                PageRequest.of(0, MAX_CHECKS_PER_CYCLE));

        if (due.isEmpty()) {
            return;
        }
        log.info("Health-checking {} sources", due.size());
        due.forEach(this::check);
    }

    /** Trims the rolling health history. Old rows have no operational value. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void pruneHistory() {
        // Deletes rows, so it answers to the master switch rather than running
        // unconditionally as it did before.
        if (!gate.permitsBackgroundWork()) {
            return;
        }
        int removed = healthCheckRepository.deleteOlderThan(
                Instant.now().minus(java.time.Duration.ofDays(HEALTH_HISTORY_DAYS)));
        if (removed > 0) {
            log.info("Pruned {} health check rows older than {} days", removed, HEALTH_HISTORY_DAYS);
        }
    }

    @Transactional(readOnly = true)
    public List<SourceHealthCheck> recentChecks(UUID sourceId, int limit) {
        return healthCheckRepository.findBySourceIdOrderByCheckedAtDesc(sourceId, PageRequest.of(0, limit));
    }
}
