package com.careerflux.matching.rematch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.careerflux.matching.service.MatchScorer;
import com.careerflux.matching.service.MatchingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the rematch queue in the background.
 *
 * <p>Claims one request at a time and scores that candidate against the corpus.
 * Deliberately unhurried: this is minutes of work per candidate, competing with
 * live traffic for the same database, and nothing downstream is waiting on it.
 *
 * <p>Failures are retried a bounded number of times and then parked as
 * {@code FAILED} rather than retried for ever. A stuck row that an operator can
 * see is better than a queue that quietly spins.
 *
 * <p>Each phase takes its own short transaction. Holding one open for the whole
 * scoring run would pin a connection for minutes and roll back every match
 * written if the last one failed.
 */
@Component
public class RematchWorker {

    private static final Logger log = LoggerFactory.getLogger(RematchWorker.class);

    /** After this many failed attempts a request is parked rather than retried. */
    private static final int MAX_ATTEMPTS = 3;

    /** A run still marked RUNNING after this long is assumed to have died with its worker. */
    private static final Duration STALLED_AFTER = Duration.ofMinutes(30);

    private final RematchRequestRepository requests;
    private final MatchingService matchingService;
    private final TransactionTemplate transactionTemplate;

    public RematchWorker(RematchRequestRepository requests,
                         MatchingService matchingService,
                         PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.matchingService = matchingService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${careerflux.matching.worker-interval-ms:5000}", initialDelay = 10_000)
    public void drain() {
        RematchRequest claimed = claimNext();
        if (claimed == null) {
            return;
        }
        run(claimed);
    }

    /**
     * Takes the oldest pending request and marks it running.
     *
     * <p>The claim commits before any scoring starts, so a second worker sees
     * RUNNING rather than picking up the same candidate.
     */
    private RematchRequest claimNext() {
        return transactionTemplate.execute(status -> {
            List<RematchRequest> pending = requests.findByStatus(RematchStatus.PENDING, PageRequest.of(0, 1));
            if (pending.isEmpty()) {
                return null;
            }
            RematchRequest request = pending.get(0);
            request.markRunning();
            return requests.saveAndFlush(request);
        });
    }

    private void run(RematchRequest request) {
        java.util.UUID candidateId = request.getCandidate().getId();
        long startedAt = System.currentTimeMillis();
        try {
            MatchingService.MatchRun result = matchingService.recomputeForCandidate(candidateId);
            transactionTemplate.execute(status -> {
                RematchRequest fresh = requests.findById(request.getId()).orElse(null);
                if (fresh != null) {
                    fresh.markCompleted(MatchScorer.VERSION);
                    requests.save(fresh);
                }
                return null;
            });
            log.info("Rematched candidate {} in {} ms: {} jobs scored, {} visible",
                    candidateId, System.currentTimeMillis() - startedAt,
                    result.jobsScored(), result.visibleMatches());
        } catch (RuntimeException failure) {
            boolean retryable = request.getAttempts() < MAX_ATTEMPTS;
            transactionTemplate.execute(status -> {
                RematchRequest fresh = requests.findById(request.getId()).orElse(null);
                if (fresh != null) {
                    fresh.markFailed(failure.getMessage(), retryable);
                    requests.save(fresh);
                }
                return null;
            });
            if (retryable) {
                log.warn("Rematch for candidate {} failed on attempt {}; will retry: {}",
                        candidateId, request.getAttempts(), failure.getMessage());
            } else {
                log.error("Rematch for candidate {} failed permanently after {} attempts",
                        candidateId, request.getAttempts(), failure);
            }
        }
    }

    /**
     * Returns abandoned runs to the queue.
     *
     * <p>A worker that dies mid-run leaves a row marked RUNNING that nothing
     * will ever finish, and a candidate stuck showing "recalculating" for ever.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void recoverStalled() {
        transactionTemplate.execute(status -> {
            List<RematchRequest> stalled = requests.findStalledSince(Instant.now().minus(STALLED_AFTER));
            for (RematchRequest request : stalled) {
                log.warn("Rematch for candidate {} stalled in RUNNING; returning it to the queue",
                        request.getCandidate().getId());
                request.markFailed("Worker stopped before finishing.", request.getAttempts() < MAX_ATTEMPTS);
                requests.save(request);
            }
            return null;
        });
    }
}
