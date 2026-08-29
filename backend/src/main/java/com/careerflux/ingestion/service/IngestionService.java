package com.careerflux.ingestion.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.ApplyUrl;
import com.careerflux.common.TextUtils;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.event.PipelineEventBus;
import com.careerflux.ingestion.event.PipelineTopics;
import com.careerflux.ingestion.pipeline.JobChangeDetector;
import com.careerflux.ingestion.pipeline.JobDeduplicator;
import com.careerflux.ingestion.pipeline.JobEnricher;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.pipeline.NormalizedJob;
import com.careerflux.ingestion.repository.IngestionRunRepository;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobObservation;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;
import com.careerflux.source.adapter.AdapterException;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.adapter.JobSourceAdapter;
import com.careerflux.source.adapter.RawJobPosting;
import com.careerflux.source.adapter.SourceConfiguration;
import com.careerflux.source.adapter.SourceHealthResult;
import com.careerflux.source.domain.Company;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.SourceHealthService;
import com.careerflux.source.service.SourceRegistryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the ingestion pipeline for one source.
 *
 * <pre>
 *   adapter.fetchJobs
 *      -> normalize        (deterministic field mapping)
 *      -> deduplicate      (find or create the canonical job)
 *      -> detect changes   (diff against what we held)
 *      -> enrich           (skills and attributes)
 *      -> persist + emit   (pipeline events)
 *      -> close vanished   (postings the source stopped listing)
 * </pre>
 *
 * <p>Each stage emits its event on {@link PipelineEventBus}, so the same flow is
 * observable whether the transport is the in-process outbox or Kafka.
 *
 * <p>A source is only ever contacted when its state permits fetching, which in
 * turn required a passing policy verdict. That check is repeated here rather than
 * assumed, because ingestion is the last place it can be enforced.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final JobSourceRepository sourceRepository;
    private final JobRepository jobRepository;
    private final JobObservationRepository observationRepository;
    private final IngestionRunRepository runRepository;
    private final SkillRepository skillRepository;
    private final AdapterRegistry adapterRegistry;
    private final JobNormalizer normalizer;
    private final JobDeduplicator deduplicator;
    private final JobEnricher enricher;
    private final JobChangeDetector changeDetector;
    private final SourceRegistryService registryService;
    private final SourceHealthService healthService;
    private final PipelineEventBus eventBus;
    private final CareerFluxProperties properties;

    public IngestionService(JobSourceRepository sourceRepository,
                            JobRepository jobRepository,
                            JobObservationRepository observationRepository,
                            IngestionRunRepository runRepository,
                            SkillRepository skillRepository,
                            AdapterRegistry adapterRegistry,
                            JobNormalizer normalizer,
                            JobDeduplicator deduplicator,
                            JobEnricher enricher,
                            JobChangeDetector changeDetector,
                            SourceRegistryService registryService,
                            SourceHealthService healthService,
                            PipelineEventBus eventBus,
                            CareerFluxProperties properties) {
        this.sourceRepository = sourceRepository;
        this.jobRepository = jobRepository;
        this.observationRepository = observationRepository;
        this.runRepository = runRepository;
        this.skillRepository = skillRepository;
        this.adapterRegistry = adapterRegistry;
        this.normalizer = normalizer;
        this.deduplicator = deduplicator;
        this.enricher = enricher;
        this.changeDetector = changeDetector;
        this.registryService = registryService;
        this.healthService = healthService;
        this.eventBus = eventBus;
        this.properties = properties;
    }

    @Transactional
    public IngestionRun ingest(UUID sourceId, IngestionTrigger trigger) {
        JobSource source = registryService.require(sourceId);
        return ingest(source, trigger);
    }

    @Transactional
    public IngestionRun ingest(JobSource source, IngestionTrigger trigger) {
        IngestionRun run = startRun(source, trigger);

        // Last line of defence: never contact a source the lifecycle does not permit.
        if (!source.getState().permitsFetching()) {
            return finishRun(run, IngestionStatus.SKIPPED,
                    "Source is in state " + source.getState() + " and is not contacted.");
        }
        JobSourceAdapter adapter = adapterRegistry.find(source.getAdapterKey()).orElse(null);
        if (adapter == null) {
            return finishRun(run, IngestionStatus.SKIPPED,
                    "No adapter is registered for key " + source.getAdapterKey() + ".");
        }

        source.setLastSyncAttemptAt(Instant.now());
        List<RawJobPosting> raw;
        try {
            raw = adapter.fetchJobs(SourceConfiguration.from(source, properties.ingestion().maxJobsPerRun()));
        } catch (AdapterException ex) {
            source.setSyncFailureCount(source.getSyncFailureCount() + 1);
            healthService.record(source, ex.getHttpStatus() == null
                    ? SourceHealthResult.unreachable(ex.getMessage())
                    : SourceHealthResult.failing(ex.getHttpStatus(), null, ex.getMessage()));
            return finishRun(run, IngestionStatus.FAILED, ex.getMessage());
        }

        run.setRawCount(raw.size());
        emit(PipelineTopics.JOB_RAW, run.getCorrelationId(),
                new RawBatchEvent(source.getId(), source.getName(), raw.size(), run.getCorrelationId()));

        List<Skill> dictionary = skillRepository.findAll();
        Set<String> seenExternalIds = new HashSet<>();
        List<String> errors = new ArrayList<>();

        for (RawJobPosting posting : raw) {
            if (!TextUtils.hasText(posting.externalId()) || !TextUtils.hasText(posting.title())) {
                run.setErrorCount(run.getErrorCount() + 1);
                continue;
            }
            seenExternalIds.add(posting.externalId());
            try {
                processOne(source, posting, run, dictionary);
            } catch (RuntimeException ex) {
                log.warn("Failed to ingest posting {} from {}: {}",
                        posting.externalId(), source.getName(), ex.getMessage());
                run.setErrorCount(run.getErrorCount() + 1);
                if (errors.size() < 3) {
                    errors.add(ex.getMessage());
                }
            }
        }

        int closed = closeVanished(source, seenExternalIds);
        run.setClosedCount(closed);

        source.setSyncSuccessCount(source.getSyncSuccessCount() + 1);
        source.setLastSuccessfulSyncAt(Instant.now());
        source.setConsecutiveFailures(0);
        source.setJobsIngestedTotal(source.getJobsIngestedTotal() + run.getNewCount());
        sourceRepository.save(source);

        healthService.record(source, SourceHealthResult.healthy(200, 0, raw.size()));

        IngestionStatus status = run.getErrorCount() == 0 ? IngestionStatus.SUCCEEDED : IngestionStatus.PARTIAL;
        return finishRun(run, status, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void processOne(JobSource source, RawJobPosting posting, IngestionRun run, List<Skill> dictionary) {
        String companyName = TextUtils.hasText(posting.companyName())
                ? posting.companyName()
                : source.getCompany() != null ? source.getCompany().getName() : source.getName();

        NormalizedJob normalized = normalizer.normalize(posting, companyName);
        run.setNormalizedCount(run.getNormalizedCount() + 1);
        emit(PipelineTopics.JOB_NORMALIZED, source.getId() + ":" + normalized.externalId(),
                new NormalizedJobEvent(source.getId(), normalized.externalId(), normalized.normalizedTitle(),
                        run.getCorrelationId()));

        Company company = source.getCompany() != null
                ? source.getCompany()
                : registryService.findOrCreateCompany(companyName, null);

        JobDeduplicator.Resolution resolution = deduplicator.resolve(normalized, company, source.getId());
        emit(PipelineTopics.JOB_DEDUPLICATED, source.getId() + ":" + normalized.externalId(),
                new DeduplicationEvent(source.getId(), normalized.externalId(),
                        resolution.signal().name(), resolution.isNewJob(), run.getCorrelationId()));

        Job job;
        if (resolution.isNewJob()) {
            job = createJob(normalized, company);
            run.setNewCount(run.getNewCount() + 1);
        } else {
            job = resolution.job();
            run.setDuplicateCount(run.getDuplicateCount() + 1);

            if (resolution.isRepeatObservation()) {
                // The same source showing us the same posting again. This is the only
                // case where the canonical record is rewritten, because it is the only
                // case where a difference means the employer actually edited something.
                List<com.careerflux.job.domain.JobChange> changes =
                        changeDetector.detect(job, normalized, resolution.observation());
                if (!changes.isEmpty()) {
                    run.setUpdatedCount(run.getUpdatedCount() + 1);
                    applyUpdates(job, normalized);
                    changes.forEach(change -> emit(PipelineTopics.JOB_CHANGED,
                            job.getId() + ":" + change.getChangeType(),
                            new JobChangedEvent(job.getId(), change.getChangeType().name(),
                                    change.getSummary(), run.getCorrelationId())));
                }
            } else {
                // A different source describing a job we already hold. Its wording is
                // not more authoritative than the record we already have, so the
                // canonical fields keep their identity and only genuine gaps are
                // filled. Otherwise a job's title would flap between boards depending
                // on which one happened to be ingested last.
                fillGaps(job, normalized);
            }

            if (job.getStatus() == JobStatus.CLOSED) {
                job.setStatus(JobStatus.REOPENED);
                job.setClosedAt(null);
                changeDetector.recordReopened(job, source.getName());
            }
        }

        job.setLastObservedAt(Instant.now());
        JobObservation observation = upsertObservation(job, source, posting, normalized,
                resolution.observation(), resolution.isNewJob());

        if (resolution.isNewJob()) {
            enricher.enrich(job, dictionary);
            emit(PipelineTopics.JOB_ENRICHED, job.getId().toString(),
                    new EnrichmentEvent(job.getId(), job.getEnrichmentEngine(),
                            job.getSkills().size(), run.getCorrelationId()));
            changeDetector.recordCreated(job, observation, source.getName());
            emit(PipelineTopics.JOB_CLASSIFIED, job.getId().toString(),
                    new ClassifiedJobEvent(job.getId(), job.getSeniority().name(),
                            job.getWorkMode().name(), run.getCorrelationId()));
        }

        jobRepository.save(job);
    }

    private Job createJob(NormalizedJob normalized, Company company) {
        Job job = new Job();
        job.setCompany(company);
        job.setCanonicalKey(deduplicator.canonicalKey(
                company.getSlug(), normalized.normalizedTitle(), normalized.city()));
        applyUpdates(job, normalized);
        job.setStatus(JobStatus.OPEN);
        job.setFirstObservedAt(Instant.now());
        job.setLastObservedAt(Instant.now());
        return jobRepository.save(job);
    }

    private void applyUpdates(Job job, NormalizedJob normalized) {
        job.setTitle(normalized.title());
        job.setNormalizedTitle(normalized.normalizedTitle());
        job.setDescription(normalized.description());
        job.setResponsibilities(normalized.responsibilities());
        job.setRequirements(normalized.requirements());
        job.setLocationRaw(normalized.locationRaw());
        job.setCity(normalized.city());
        job.setRegion(normalized.region());
        job.setCountry(normalized.country());
        job.setWorkMode(normalized.workMode());
        job.setEmploymentType(normalized.employmentType());
        job.setSeniority(normalized.seniority());
        job.setMinExperienceYears(normalized.minExperienceYears());
        job.setMaxExperienceYears(normalized.maxExperienceYears());
        job.setSalaryMin(normalized.salaryMin());
        job.setSalaryMax(normalized.salaryMax());
        job.setSalaryCurrency(normalized.salaryCurrency());
        job.setSalaryPeriod(normalized.salaryPeriod());
        // The stored link is sanitized; the deduplication key below is not.
        // A placeholder or malformed URL is still a reliable signal that two
        // listings are the same opening — it is simply not somewhere a student
        // may be sent. Keeping the two apart preserves deduplication while
        // making it impossible to render an unusable link.
        job.setApplyUrl(ApplyUrl.sanitize(normalized.applyUrl()));
        // Written through the deduplicator so the stored key is exactly what a later
        // lookup will compare against.
        String applyUrlKey = deduplicator.normalizeUrl(normalized.applyUrl());
        job.setApplyUrlKey(applyUrlKey.isEmpty() ? null : applyUrlKey);
        job.setPostedAt(normalized.postedAt());
        job.setContentHash(normalized.contentHash());
    }

    /**
     * Adds information a second source supplies that the canonical record is
     * missing, without overwriting anything already known. A board that states a
     * salary the original listing omitted is worth having; one that words the
     * title differently is not.
     */
    private void fillGaps(Job job, NormalizedJob normalized) {
        if (job.getSalaryMin() == null && normalized.salaryMin() != null) {
            job.setSalaryMin(normalized.salaryMin());
            job.setSalaryMax(normalized.salaryMax());
            job.setSalaryCurrency(normalized.salaryCurrency());
            job.setSalaryPeriod(normalized.salaryPeriod());
        }
        if (job.getMinExperienceYears() == null && normalized.minExperienceYears() != null) {
            job.setMinExperienceYears(normalized.minExperienceYears());
            job.setMaxExperienceYears(normalized.maxExperienceYears());
        }
        if (job.getWorkMode() == com.careerflux.common.taxonomy.WorkMode.UNSPECIFIED) {
            job.setWorkMode(normalized.workMode());
        }
        if (job.getEmploymentType() == com.careerflux.common.taxonomy.EmploymentType.UNSPECIFIED) {
            job.setEmploymentType(normalized.employmentType());
        }
        if (job.getSeniority() == com.careerflux.common.taxonomy.Seniority.UNSPECIFIED) {
            job.setSeniority(normalized.seniority());
        }
        if (!TextUtils.hasText(job.getApplyUrl()) && TextUtils.hasText(normalized.applyUrl())) {
            job.setApplyUrl(ApplyUrl.sanitize(normalized.applyUrl()));
            String key = deduplicator.normalizeUrl(normalized.applyUrl());
            job.setApplyUrlKey(key.isEmpty() ? null : key);
        }
        if (job.getPostedAt() == null) {
            job.setPostedAt(normalized.postedAt());
        }
    }

    private JobObservation upsertObservation(Job job, JobSource source, RawJobPosting posting,
                                             NormalizedJob normalized, JobObservation existing,
                                             boolean newJob) {
        String payloadHash = TextUtils.sha256(posting.rawPayload());
        if (existing != null) {
            existing.setLastObservedAt(Instant.now());
            existing.setObservationCount(existing.getObservationCount() + 1);
            existing.setActive(true);
            existing.setPayloadHash(payloadHash);
            existing.setRawPayload(posting.rawPayload());
            return observationRepository.save(existing);
        }

        JobObservation observation = new JobObservation();
        observation.setJob(job);
        observation.setSource(source);
        observation.setExternalJobId(TextUtils.truncate(posting.externalId(), 200));
        observation.setRequisitionId(TextUtils.truncate(posting.requisitionId(), 120));
        observation.setSourceUrl(TextUtils.truncate(normalized.sourceUrl(), 1000));
        observation.setRawPayload(posting.rawPayload());
        observation.setPayloadHash(payloadHash);
        observation.setFirstObservedAt(Instant.now());
        observation.setLastObservedAt(Instant.now());
        observationRepository.save(observation);
        job.getObservations().add(observation);

        if (!newJob) {
            // The same job newly appearing on an additional source is worth surfacing.
            changeDetector.recordNewSource(job, observation, source.getName());
        }
        return observation;
    }

    /**
     * Marks observations the source stopped listing. A job only becomes CLOSED
     * when no source lists it any more; while another source still carries it, it
     * stays open with one fewer observation.
     */
    private int closeVanished(JobSource source, Set<String> seenExternalIds) {
        List<JobObservation> active = observationRepository.findBySourceIdAndActiveTrue(source.getId());
        int closedJobs = 0;
        for (JobObservation observation : active) {
            if (seenExternalIds.contains(observation.getExternalJobId())) {
                continue;
            }
            observation.setActive(false);
            observationRepository.save(observation);

            Job job = observation.getJob();
            long remaining = observationRepository.countByJobIdAndActiveTrue(job.getId());
            if (remaining == 0 && job.getStatus() != JobStatus.CLOSED) {
                job.setStatus(JobStatus.CLOSED);
                job.setClosedAt(Instant.now());
                jobRepository.save(job);
                changeDetector.recordClosed(job, "No longer listed on " + source.getName());
                emit(PipelineTopics.JOB_EXPIRED, job.getId().toString(),
                        new JobExpiredEvent(job.getId(), source.getId(), "delisted"));
                closedJobs++;
            }
        }
        return closedJobs;
    }

    private IngestionRun startRun(JobSource source, IngestionTrigger trigger) {
        IngestionRun run = new IngestionRun();
        run.setSource(source);
        run.setTriggerType(trigger);
        run.setStatus(IngestionStatus.RUNNING);
        run.setCorrelationId(UUID.randomUUID().toString().substring(0, 16));
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    private IngestionRun finishRun(IngestionRun run, IngestionStatus status, String message) {
        run.setStatus(status);
        run.setErrorMessage(TextUtils.truncate(message, 1000));
        run.setFinishedAt(Instant.now());
        log.info("Ingestion run {} for source {} finished {}: raw={} new={} updated={} duplicate={} closed={} errors={}",
                run.getCorrelationId(),
                run.getSource() == null ? "?" : run.getSource().getName(),
                status, run.getRawCount(), run.getNewCount(), run.getUpdatedCount(),
                run.getDuplicateCount(), run.getClosedCount(), run.getErrorCount());
        return runRepository.save(run);
    }

    private void emit(String topic, String key, Object payload) {
        eventBus.publish(topic, key, payload);
    }

    // Pipeline event payloads. Small, flat and JSON-friendly on purpose.

    public record RawBatchEvent(UUID sourceId, String sourceName, int count, String correlationId) {
    }

    public record NormalizedJobEvent(UUID sourceId, String externalId, String normalizedTitle,
                                     String correlationId) {
    }

    public record DeduplicationEvent(UUID sourceId, String externalId, String signal, boolean newJob,
                                     String correlationId) {
    }

    public record EnrichmentEvent(UUID jobId, String engine, int skillCount, String correlationId) {
    }

    public record ClassifiedJobEvent(UUID jobId, String seniority, String workMode, String correlationId) {
    }

    public record JobChangedEvent(UUID jobId, String changeType, String summary, String correlationId) {
    }

    public record JobExpiredEvent(UUID jobId, UUID sourceId, String reason) {
    }
}
