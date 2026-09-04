package com.careerflux.ingestion.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import com.careerflux.config.BackgroundWorkGate;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.service.MatchingService;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.repository.JobSourceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives ingestion, expiry and rematching on a schedule.
 *
 * <p>Everything here is bounded per cycle. A registry of a hundred sources must
 * never turn into a hundred simultaneous outbound requests, so each pass takes a
 * small slice, oldest-first, and the next pass picks up where it left off.
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);
    private static final int SOURCES_PER_CYCLE = 5;
    /**
     * The shortest interval between two scheduled attempts on one source.
     *
     * <p>Visible because it is a contract, not just a tuning knob: it is the
     * guarantee that lets a parsed {@code Retry-After} be honoured without any
     * deferral machinery, and a test asserts the two stay consistent.
     */
    public static final Duration SYNC_INTERVAL = Duration.ofHours(4);
    private static final int STALE_JOB_DAYS = 45;
    private static final int EXPIRY_BATCH = 200;

    private final JobSourceRepository sourceRepository;
    private final JobRepository jobRepository;
    private final IngestionService ingestionService;
    private final MatchingService matchingService;
    private final com.careerflux.candidate.repository.CandidateProfileRepository profileRepository;
    private final CareerFluxProperties properties;
    private final BackgroundWorkGate gate;

    public IngestionScheduler(JobSourceRepository sourceRepository,
                              JobRepository jobRepository,
                              IngestionService ingestionService,
                              MatchingService matchingService,
                              com.careerflux.candidate.repository.CandidateProfileRepository profileRepository,
                              CareerFluxProperties properties,
                              BackgroundWorkGate gate) {
        this.sourceRepository = sourceRepository;
        this.jobRepository = jobRepository;
        this.ingestionService = ingestionService;
        this.matchingService = matchingService;
        this.profileRepository = profileRepository;
        this.properties = properties;
        this.gate = gate;
    }

    /** Syncs the sources whose last attempt is oldest, a few at a time. */
    @Scheduled(fixedDelayString = "PT20M", initialDelayString = "PT1M")
    public void syncDueSources() {
        if (!gate.permitsScheduledIngestion()) {
            return;
        }
        List<JobSource> due = sourceRepository.findDueForSync(
                EnumSet.of(SourceState.ACTIVE, SourceState.DEGRADED),
                Instant.now().minus(SYNC_INTERVAL),
                PageRequest.of(0, SOURCES_PER_CYCLE));

        if (due.isEmpty()) {
            return;
        }
        log.info("Scheduled ingestion for {} sources", due.size());
        for (JobSource source : due) {
            try {
                ingestionService.ingest(source, IngestionTrigger.SCHEDULED);
            } catch (RuntimeException ex) {
                log.warn("Scheduled ingestion of {} failed: {}", source.getName(), ex.getMessage());
            }
        }
    }

    /**
     * Ages out jobs nobody has listed for a long time. These are marked EXPIRED
     * rather than CLOSED: CareerFlux stopped seeing them, which is not the same as
     * knowing the employer closed them, and the distinction matters to a candidate.
     */
    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void expireStaleJobs() {
        if (!gate.permitsScheduledIngestion()) {
            return;
        }
        Instant threshold = Instant.now().minus(STALE_JOB_DAYS, ChronoUnit.DAYS);
        List<Job> stale = jobRepository.findStale(threshold, PageRequest.of(0, EXPIRY_BATCH));
        for (Job job : stale) {
            job.setStatus(JobStatus.EXPIRED);
            jobRepository.save(job);
        }
        if (!stale.isEmpty()) {
            log.info("Marked {} jobs expired after {} days without an observation", stale.size(), STALE_JOB_DAYS);
        }
    }

    /**
     * Nightly rematch. Job attributes drift as postings are edited and candidate
     * profiles change, so scores are recomputed rather than left frozen at the
     * moment a job was first ingested.
     */
    @Scheduled(cron = "0 0 5 * * *")
    public void recomputeMatches() {
        if (!gate.permitsScheduledIngestion()) {
            return;
        }
        var candidates = profileRepository.findAllOnboarded();
        log.info("Nightly rematch for {} candidates", candidates.size());
        for (var profile : candidates) {
            try {
                matchingService.recomputeForCandidate(profile.getId());
            } catch (RuntimeException ex) {
                log.warn("Rematch failed for candidate {}: {}", profile.getId(), ex.getMessage());
            }
        }
    }
}
