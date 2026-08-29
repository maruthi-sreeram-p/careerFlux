package com.careerflux.matching.rematch;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One candidate's place in the rescoring queue.
 *
 * <p>There is at most one row per candidate, reused rather than appended to.
 * Rescoring is idempotent, so a second request arriving while one is pending is
 * the same work; collapsing them is what stops a burst of profile edits from
 * queueing the whole corpus several times over.
 */
@Entity
@Table(name = "match_rematch_queue")
public class RematchRequest {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private RematchReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RematchStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** The scorer version this request was completed under. */
    @Column(name = "scorer_version", length = 32)
    private String scorerVersion;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected RematchRequest() {
    }

    public RematchRequest(CandidateProfile candidate, RematchReason reason) {
        this.id = UUID.randomUUID();
        this.candidate = candidate;
        this.reason = reason;
        this.status = RematchStatus.PENDING;
        this.requestedAt = Instant.now();
    }

    /** Puts an existing row back in the queue, keeping its identity and history. */
    public void requeue(RematchReason newReason) {
        this.reason = newReason;
        this.status = RematchStatus.PENDING;
        this.attempts = 0;
        this.error = null;
        this.requestedAt = Instant.now();
        this.startedAt = null;
        this.finishedAt = null;
    }

    public void markRunning() {
        this.status = RematchStatus.RUNNING;
        this.startedAt = Instant.now();
        this.attempts++;
    }

    public void markCompleted(String version) {
        this.status = RematchStatus.COMPLETED;
        this.scorerVersion = version;
        this.finishedAt = Instant.now();
        this.error = null;
    }

    /** Back to PENDING for another attempt, or terminal FAILED once out of them. */
    public void markFailed(String message, boolean retryable) {
        this.error = message == null ? null : message.substring(0, Math.min(1000, message.length()));
        if (retryable) {
            this.status = RematchStatus.PENDING;
            this.startedAt = null;
        } else {
            this.status = RematchStatus.FAILED;
            this.finishedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public CandidateProfile getCandidate() {
        return candidate;
    }

    public RematchReason getReason() {
        return reason;
    }

    public RematchStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getScorerVersion() {
        return scorerVersion;
    }

    public String getError() {
        return error;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
