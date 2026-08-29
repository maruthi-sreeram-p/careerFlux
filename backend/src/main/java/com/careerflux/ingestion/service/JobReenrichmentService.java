package com.careerflux.ingestion.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.ingestion.pipeline.JobEnricher;
import com.careerflux.job.domain.Job;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.rematch.RematchQueue;
import com.careerflux.matching.rematch.RematchReason;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-runs enrichment over jobs already in the corpus.
 *
 * <p>Needed when the classifier changes: skills stored under older rules keep
 * their old tiers until something re-reads the posting. Nothing else about a job
 * is touched — identity, source, external ids, title, company, dates and status
 * are all left exactly as they are. {@link JobEnricher} rewrites the skill set,
 * the enrichment status and engine, and the search text, and nothing more.
 *
 * <p><b>Idempotent.</b> Enrichment is a pure function of the posting text and
 * the skill dictionary, so running it twice produces the same rows. That is also
 * what makes it restartable: an interrupted run is resumed by starting again,
 * and the work already done is simply redone to the same values.
 *
 * <p><b>Batched.</b> One transaction per batch rather than one for the corpus.
 * A single long transaction would hold a connection for the whole run, and a
 * failure at the end would roll back everything including the thousands of jobs
 * that enriched perfectly well.
 *
 * <p>Matches are not rescored here. Changing a job's skills invalidates every
 * match computed against it, but rescoring thousands of candidates inline would
 * be the synchronous-rematch mistake again. Affected candidates are queued and
 * the background worker drains them.
 */
@Service
public class JobReenrichmentService {

    private static final Logger log = LoggerFactory.getLogger(JobReenrichmentService.class);

    private final JobRepository jobRepository;
    private final SkillRepository skillRepository;
    private final JobEnricher enricher;
    private final CandidateProfileRepository profileRepository;
    private final RematchQueue rematchQueue;
    private final TransactionTemplate transactionTemplate;

    public JobReenrichmentService(JobRepository jobRepository,
                                  SkillRepository skillRepository,
                                  JobEnricher enricher,
                                  CandidateProfileRepository profileRepository,
                                  RematchQueue rematchQueue,
                                  PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.skillRepository = skillRepository;
        this.enricher = enricher;
        this.profileRepository = profileRepository;
        this.rematchQueue = rematchQueue;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Re-enriches every job, a batch at a time.
     *
     * @param batchSize      how many jobs share a transaction
     * @param queueRematches whether to queue affected candidates afterwards
     */
    public Result reenrichAll(int batchSize, boolean queueRematches) {
        int size = Math.clamp(batchSize, 10, 500);
        List<Skill> dictionary = skillRepository.findAll();
        long total = jobRepository.count();
        log.info("Re-enrichment starting over {} jobs in batches of {}", total, size);

        int processed = 0;
        int failedBatches = 0;
        List<String> failures = new ArrayList<>();
        long startedAt = System.currentTimeMillis();

        for (int page = 0; ; page++) {
            // Ordered by id so batches are stable and a restart covers the same
            // ground in the same order.
            List<Job> batch = jobRepository.findAll(
                    PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))).getContent();
            if (batch.isEmpty()) {
                break;
            }
            List<UUID> ids = batch.stream().map(Job::getId).toList();
            try {
                Integer done = transactionTemplate.execute(status -> {
                    int count = 0;
                    for (UUID id : ids) {
                        Job job = jobRepository.findById(id).orElse(null);
                        if (job == null) {
                            continue;
                        }
                        // Enrichment replaces the skill set wholesale. Hibernate
                        // orders the inserts for the new rows ahead of the deletes
                        // for the old ones, so every skill the job keeps collides
                        // with uq_job_skills (job_id, skill_id). Flushing the
                        // removal on its own puts the DELETE on the wire before the
                        // INSERT that reuses the same pair. Ingestion never hit this
                        // because it only enriches jobs it has just created.
                        job.getSkills().clear();
                        jobRepository.saveAndFlush(job);

                        enricher.enrich(job, dictionary);
                        jobRepository.save(job);
                        count++;
                    }
                    return count;
                });
                processed += done == null ? 0 : done;
            } catch (RuntimeException failure) {
                // The batch rolled back on its own. Record it and carry on: the
                // remaining jobs are independent, and stopping would leave more
                // of the corpus stale than continuing does.
                failedBatches++;
                failures.add("batch " + page + ": " + failure.getMessage());
                log.error("Re-enrichment batch {} failed and was rolled back", page, failure);
            }
            if (page % 5 == 0) {
                log.info("Re-enrichment progress: {} of {} jobs", processed, total);
            }
        }

        int queued = queueRematches ? queueAffectedCandidates() : 0;
        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("Re-enrichment finished: {} jobs in {} ms, {} failed batches, {} candidates queued",
                processed, elapsed, failedBatches, queued);
        return new Result(processed, (int) total, failedBatches, failures, queued, elapsed);
    }

    /**
     * Queues every candidate whose matches are now stale.
     *
     * <p>Every job's skills may have changed, so in practice this is everyone
     * with a scorable profile. Queueing rather than rescoring keeps the request
     * short and lets the worker pace the real work.
     */
    private int queueAffectedCandidates() {
        List<CandidateProfile> candidates = profileRepository.findAllOnboarded();
        int queued = 0;
        for (CandidateProfile candidate : candidates) {
            if (rematchQueue.enqueue(candidate.getId(), RematchReason.JOBS_REENRICHED).isPresent()) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * @param failedBatches batches that rolled back; their jobs keep their old
     *                      classification rather than a half-written one
     */
    public record Result(int jobsProcessed, int jobsTotal, int failedBatches,
                         List<String> failures, int candidatesQueued, long elapsedMs) {
    }
}
