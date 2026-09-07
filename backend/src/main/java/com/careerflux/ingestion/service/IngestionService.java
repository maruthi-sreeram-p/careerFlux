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
import com.careerflux.common.logging.CorrelationId;
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
import com.careerflux.source.adapter.FailureClassification;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    /**
     * Transactions are opened explicitly rather than with {@code @Transactional},
     * because the point of this class is that they do <em>not</em> span the whole
     * method: the network fetch has to happen between two of them, and a
     * self-invoked annotated method would not go through the Spring proxy anyway.
     *
     * <p>Propagation is the default {@code REQUIRED}. A caller that already has a
     * transaction still gets one transaction, exactly as before; the split is real
     * for the callers that matter, which are the scheduler and the controller, and
     * neither of those is transactional.
     */
    private final TransactionTemplate transaction;

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
                            CareerFluxProperties properties,
                            PlatformTransactionManager transactionManager) {
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
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public IngestionRun ingest(UUID sourceId, IngestionTrigger trigger) {
        // Resolves in its own read transaction, with company and access policy
        // fetched, so what comes back is complete rather than half-initialised.
        JobSource source = registryService.require(sourceId);
        return ingest(source, trigger);
    }

    /**
     * Orchestration only — deliberately not transactional.
     *
     * <p>The run is created and committed, the source is fetched with no
     * transaction held, and the results are processed in a second transaction.
     */
    public IngestionRun ingest(JobSource source, IngestionTrigger trigger) {
        IngestionRun run = transaction.execute(status -> startRun(source, trigger));
        // Every log line for the rest of this run carries the same id the run
        // row already stored, so the database record and the logs can finally be
        // read together.
        return CorrelationId.with(run.getCorrelationId(), () -> runIngestion(source, trigger, run));
    }

    private IngestionRun runIngestion(JobSource source, IngestionTrigger trigger, IngestionRun run) {

        // Totals live here rather than on the run entity, and are written onto it
        // once in finishRun. Created before the early exits so every path finishes
        // the same way, with an all-zero tally where nothing was counted.
        IngestionTally tally = new IngestionTally();

        // Last line of defence: never contact a source the lifecycle does not permit.
        if (!source.getState().permitsFetching()) {
            return transaction.execute(status -> finishRun(run, tally, IngestionStatus.SKIPPED,
                    "Source is in state " + source.getState() + " and is not contacted."));
        }
        JobSourceAdapter adapter = adapterRegistry.find(source.getAdapterKey()).orElse(null);
        if (adapter == null) {
            return transaction.execute(status -> finishRun(run, tally, IngestionStatus.SKIPPED,
                    "No adapter is registered for key " + source.getAdapterKey() + "."));
        }

        // The attempt is written down and committed BEFORE the network call. Two
        // reasons. The scheduler selects on lastSyncAttemptAt, so a fetch that fails
        // in a way that discards the transaction would otherwise leave this source
        // permanently due and retried every cycle. And what the fetch needs to know
        // about the source is read here, inside the transaction, so that only an
        // immutable value crosses the boundary rather than an entity whose lazy
        // associations would have no session left to load from.
        SourceConfiguration configuration = transaction.execute(status -> {
            source.setLastSyncAttemptAt(Instant.now());
            // Explicit: the source is detached on the scheduled path, so nothing
            // would notice this change without being told to save it.
            sourceRepository.save(source);
            return SourceConfiguration.from(source, properties.ingestion().maxJobsPerRun());
        });

        // ---------------------------------------------------- no transaction here
        List<RawJobPosting> raw;
        try {
            raw = adapter.fetchJobs(configuration);
        } catch (AdapterException ex) {
            return transaction.execute(status -> failedFetch(source, run, tally, ex));
        }
        // ------------------------------------------------------------------------

        // No transaction around this call. processFetched opens one per posting
        // and one for finalization; wrapping it here would put them all back
        // inside a single outer transaction and undo exactly what M2 fixed.
        return processFetched(source, raw, run, tally);
    }

    /** What a fetch that never returned postings leaves behind. */
    private IngestionRun failedFetch(JobSource source, IngestionRun run, IngestionTally tally,
                                     AdapterException ex) {
        if (ex.getClassification() == FailureClassification.NOT_ATTEMPTED) {
            // Our own pacing declined to send the request. Nothing was asked of the
            // source, so nothing is recorded against it and the run is skipped
            // rather than failed. The attempt timestamp the scheduler relies on was
            // already committed above, which is why there is no second save here.
            log.info("Skipping sync of {}: {}", source.getName(), ex.getMessage());
            return finishRun(run, tally, IngestionStatus.SKIPPED, ex.getMessage());
        }
        source.setSyncFailureCount(source.getSyncFailureCount() + 1);
        // record() saves the source itself, which is what persists both the failure
        // count above and the health fields it sets.
        healthService.record(source, SourceHealthResult.from(ex, null));
        return finishRun(run, tally, IngestionStatus.FAILED, ex.getMessage());
    }

    /**
     * Everything that happens once the postings are in hand, in one transaction.
     *
     * <p>Still one transaction for the whole batch: splitting it per posting is M2,
     * and deliberately not done here. What has changed is only that the network is
     * no longer inside it.
     *
     * <p>JOB_RAW is emitted here rather than as soon as the fetch returned. The
     * outbox row has to commit with the data it describes, and announcing postings
     * from outside this transaction would publish an event for work this
     * transaction may still discard.
     */
    private IngestionRun processFetched(JobSource source, List<RawJobPosting> raw,
                                        IngestionRun run, IngestionTally tally) {
        tally.recordRaw(raw.size());
        // Announces the fetch, not any posting, so it belongs to no posting's
        // transaction. The event bus is itself transactional, so with none open
        // here this commits on its own — which is honest: the fetch really did
        // return this many postings, whatever becomes of them individually.
        emit(PipelineTopics.JOB_RAW, run.getCorrelationId(),
                new RawBatchEvent(source.getId(), source.getName(), raw.size(), run.getCorrelationId()));

        // Read once, in a transaction of its own. Only the canonical name and
        // slug are read back from these, so they are safe to keep using after it
        // closes; the skills actually attached to a job are re-resolved inside
        // that job's own transaction.
        List<Skill> dictionary = transaction.execute(status -> skillRepository.findAll());
        Set<String> seenExternalIds = new HashSet<>();
        List<String> errors = new ArrayList<>();

        for (RawJobPosting posting : raw) {
            if (!TextUtils.hasText(posting.externalId()) || !TextUtils.hasText(posting.title())) {
                tally.recordError();
                continue;
            }
            // Before processing, deliberately, and outside the posting's
            // transaction so it survives that transaction being rolled back. A
            // posting the source is still advertising must count as seen even if
            // we then fail to process it, or closeVanished below would close a
            // job that never went away.
            seenExternalIds.add(posting.externalId());
            try {
                // One transaction per posting. This is the M2 fix: a posting that
                // fails to persist marks only its own transaction rollback-only,
                // so the postings before it stay committed and the ones after it
                // are unaffected. Under a single transaction the loop went on
                // doing work that was already doomed, and the final commit threw
                // away every posting that had succeeded.
                PostingOutcome outcome = transaction.execute(status ->
                        processOne(source, posting, run.getCorrelationId(), dictionary, tally));
                log.trace("Posting {} from {} resolved as {}",
                        posting.externalId(), source.getName(), outcome);
            } catch (RuntimeException ex) {
                log.warn("Failed to ingest posting {} from {}: {}",
                        posting.externalId(), source.getName(), ex.getMessage());
                tally.recordError();
                if (errors.size() < 3) {
                    errors.add(ex.getMessage());
                }
            }
        }

        return transaction.execute(status ->
                finalizeRun(source, raw.size(), run, tally, seenExternalIds, errors));
    }

    /**
     * Closes what vanished, updates the source, and writes the run down.
     *
     * <p>Its own transaction, and deliberately not part of any posting's. These
     * are statements about the run as a whole — how many jobs went away, that the
     * source answered us, what the totals were — and none of them should be
     * undone because one posting out of two hundred failed to persist.
     */
    private IngestionRun finalizeRun(JobSource source, int rawCount, IngestionRun run,
                                     IngestionTally tally, Set<String> seenExternalIds,
                                     List<String> errors) {
        tally.recordClosed(closeVanished(source, seenExternalIds));

        source.setSyncSuccessCount(source.getSyncSuccessCount() + 1);
        source.setLastSuccessfulSyncAt(Instant.now());
        source.setConsecutiveFailures(0);
        source.setJobsIngestedTotal(source.getJobsIngestedTotal() + tally.newCount());
        // Explicit: the source has been detached since before the fetch.
        sourceRepository.save(source);

        // A posting we could not persist is our problem, not the source's. It
        // answered the request perfectly well, so its health is recorded as
        // healthy and its failure counters are left alone.
        healthService.record(source, SourceHealthResult.healthy(200, 0, rawCount));

        IngestionStatus status = tally.isClean() ? IngestionStatus.SUCCEEDED : IngestionStatus.PARTIAL;
        return finishRun(run, tally, status, errors.isEmpty() ? null : String.join("; ", errors));
    }

    /**
     * Processes one posting and reports what it turned out to be.
     *
     * <p>Takes the correlation id and the tally rather than the run entity, so
     * nothing here touches a managed object. That is the point of the change: the
     * per-posting work has to be movable into a transaction of its own, and an
     * entity loaded by an outer persistence context could not come with it.
     *
     * <p>The tally is recorded <em>as each fact is established</em>, not once at
     * the end, because that is when the run entity used to be incremented. A
     * posting that normalizes and is classified as a duplicate and only then
     * fails is counted in normalizedCount and duplicateCount and errorCount —
     * counting it purely from the returned value would silently drop the first
     * two, and the totals an operator reads would change.
     */
    private PostingOutcome processOne(JobSource source, RawJobPosting posting, String correlationId,
                                      List<Skill> dictionary, IngestionTally tally) {
        String companyName = TextUtils.hasText(posting.companyName())
                ? posting.companyName()
                : source.getCompany() != null ? source.getCompany().getName() : source.getName();

        NormalizedJob normalized = normalizer.normalize(posting, companyName);
        tally.recordNormalized();
        emit(PipelineTopics.JOB_NORMALIZED, source.getId() + ":" + normalized.externalId(),
                new NormalizedJobEvent(source.getId(), normalized.externalId(), normalized.normalizedTitle(),
                        correlationId));

        Company company = source.getCompany() != null
                ? source.getCompany()
                : registryService.findOrCreateCompany(companyName, null);

        JobDeduplicator.Resolution resolution = deduplicator.resolve(normalized, company, source.getId());
        emit(PipelineTopics.JOB_DEDUPLICATED, source.getId() + ":" + normalized.externalId(),
                new DeduplicationEvent(source.getId(), normalized.externalId(),
                        resolution.signal().name(), resolution.isNewJob(), correlationId));

        Job job;
        PostingOutcome outcome;
        if (resolution.isNewJob()) {
            job = createJob(normalized, company);
            outcome = PostingOutcome.NEW;
            tally.record(outcome);
        } else {
            job = resolution.job();
            outcome = PostingOutcome.DUPLICATE;
            tally.record(outcome);

            if (resolution.isRepeatObservation()) {
                // The same source showing us the same posting again. This is the only
                // case where the canonical record is rewritten, because it is the only
                // case where a difference means the employer actually edited something.
                List<com.careerflux.job.domain.JobChange> changes =
                        changeDetector.detect(job, normalized, resolution.observation());
                if (!changes.isEmpty()) {
                    // Counted in addition to the duplicate above, never instead of it.
                    outcome = PostingOutcome.UPDATED;
                    tally.record(outcome);
                    applyUpdates(job, normalized);
                    changes.forEach(change -> emit(PipelineTopics.JOB_CHANGED,
                            job.getId() + ":" + change.getChangeType(),
                            new JobChangedEvent(job.getId(), change.getChangeType().name(),
                                    change.getSummary(), correlationId)));
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
                            job.getSkills().size(), correlationId));
            changeDetector.recordCreated(job, observation, source.getName());
            emit(PipelineTopics.JOB_CLASSIFIED, job.getId().toString(),
                    new ClassifiedJobEvent(job.getId(), job.getSeniority().name(),
                            job.getWorkMode().name(), correlationId));
        }

        jobRepository.save(job);
        return outcome;
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

    private IngestionRun finishRun(IngestionRun run, IngestionTally tally, IngestionStatus status,
                                   String message) {
        // The one place in-memory totals become persisted state. Keeping the copy
        // here rather than on the tally leaves the tally free of any knowledge of
        // JPA, which is what makes it safe to carry across a transaction boundary.
        run.setRawCount(tally.rawCount());
        run.setNormalizedCount(tally.normalizedCount());
        run.setNewCount(tally.newCount());
        run.setUpdatedCount(tally.updatedCount());
        run.setDuplicateCount(tally.duplicateCount());
        run.setClosedCount(tally.closedCount());
        run.setErrorCount(tally.errorCount());

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
