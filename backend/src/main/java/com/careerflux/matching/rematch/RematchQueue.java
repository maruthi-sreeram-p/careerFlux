package com.careerflux.matching.rematch;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where rescoring work is asked for.
 *
 * <p>Enqueueing is the only way to request a rematch now. Scoring one candidate
 * against the corpus takes minutes, and doing that on a request thread timed the
 * caller out while the work carried on invisibly behind them — the caller got an
 * error, the database got an answer, and nothing reconciled the two.
 *
 * <p>Requests collapse. Rescoring is idempotent, so a candidate who edits their
 * profile four times in a minute is queued once, not four times.
 */
@Service
public class RematchQueue {

    private static final Logger log = LoggerFactory.getLogger(RematchQueue.class);

    private final RematchRequestRepository requests;
    private final CandidateProfileRepository profiles;

    public RematchQueue(RematchRequestRepository requests, CandidateProfileRepository profiles) {
        this.requests = requests;
        this.profiles = profiles;
    }

    /**
     * Asks for one candidate to be rescored.
     *
     * <p>Runs in its own transaction so a queued request survives whatever the
     * caller does next. A profile save that enqueues and then rolls back would
     * otherwise leave the candidate's matches stale with nothing scheduled to
     * fix them.
     *
     * @return the queued request, or empty if the candidate no longer exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RematchRequest> enqueue(UUID candidateId, RematchReason reason) {
        Optional<RematchRequest> existing = requests.findByCandidateId(candidateId);
        if (existing.isPresent()) {
            RematchRequest request = existing.get();
            if (request.getStatus() == RematchStatus.RUNNING) {
                // Leave a running job alone; the worker re-reads the profile as
                // it goes, and interrupting it would lose the work already done.
                log.debug("Rematch for candidate {} already running; not requeued", candidateId);
                return existing;
            }
            request.requeue(reason);
            return Optional.of(requests.save(request));
        }

        CandidateProfile profile = profiles.findById(candidateId).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(requests.save(new RematchRequest(profile, reason)));
        } catch (DataIntegrityViolationException raced) {
            // Another thread enqueued the same candidate between the lookup and
            // the insert. Its row is as good as the one this call would have made.
            return requests.findByCandidateId(candidateId);
        }
    }

    /** Whether this candidate's matches are currently being recalculated. */
    @Transactional(readOnly = true)
    public boolean isPending(UUID candidateId) {
        return requests.findByCandidateId(candidateId)
                .map(request -> request.getStatus() == RematchStatus.PENDING
                        || request.getStatus() == RematchStatus.RUNNING)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<RematchRequest> statusFor(UUID candidateId) {
        return requests.findByCandidateId(candidateId);
    }

    @Transactional(readOnly = true)
    public QueueDepth depth() {
        return new QueueDepth(
                requests.countByStatus(RematchStatus.PENDING),
                requests.countByStatus(RematchStatus.RUNNING),
                requests.countByStatus(RematchStatus.FAILED));
    }

    public record QueueDepth(long pending, long running, long failed) {
    }
}
