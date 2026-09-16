package com.careerflux.privacy.erasure;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.careerflux.user.UserRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One erasure request and, once carried out, its ledger entry.
 *
 * <p>It says whose account by id, who asked by role, and what was removed as
 * counts. It holds no name, address or anything the student wrote, so the
 * record of an erasure is not itself a copy of what was erased.
 */
@Entity
@Table(name = "account_erasures")
public class AccountErasure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "subject_user_id", nullable = false, updatable = false)
    private UUID subjectUserId;

    /** Equal to the subject while the request is open, null once it is closed. */
    @Column(name = "open_subject_user_id")
    private UUID openSubjectUserId;

    @Column(name = "institution_id", updatable = false)
    private UUID institutionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_by_role", nullable = false, length = 40, updatable = false)
    private UserRole requestedByRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ErasureStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "grace_ends_at", nullable = false, updatable = false)
    private Instant graceEndsAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by_role", length = 40)
    private UserRole cancelledByRole;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** An exception class name or a fixed code. Never an exception message. */
    @Column(name = "failure_code", length = 120)
    private String failureCode;

    /** What was removed, as a JSON object of counts. */
    @Column(name = "category_counts", length = 2000)
    private String categoryCounts;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected AccountErasure() {
    }

    public static AccountErasure request(UUID subjectUserId, UUID institutionId, UserRole requestedBy,
                                         Instant at, Duration gracePeriod) {
        AccountErasure erasure = new AccountErasure();
        erasure.subjectUserId = subjectUserId;
        erasure.openSubjectUserId = subjectUserId;
        erasure.institutionId = institutionId;
        erasure.requestedByRole = requestedBy;
        erasure.status = ErasureStatus.GRACE_PERIOD;
        erasure.requestedAt = at;
        erasure.graceEndsAt = at.plus(gracePeriod);
        return erasure;
    }

    /**
     * Whether it can still be called off. Nothing has been removed until the
     * worker claims it, so that is the boundary, not the grace date itself.
     */
    public boolean isCancellable() {
        return status == ErasureStatus.GRACE_PERIOD;
    }

    /**
     * @param stalledBefore a claim older than this never finished, because the
     *                      process stopped part-way, and is taken up again
     */
    public boolean isDue(Instant now, Instant stalledBefore) {
        return status == ErasureStatus.FAILED
                || (status == ErasureStatus.GRACE_PERIOD && !graceEndsAt.isAfter(now))
                || (status == ErasureStatus.PROCESSING && processingStartedAt != null
                        && processingStartedAt.isBefore(stalledBefore));
    }

    public void cancel(UserRole by, Instant at) {
        if (!isCancellable()) {
            throw new IllegalStateException("Only a request still in its grace period can be cancelled.");
        }
        status = ErasureStatus.CANCELLED;
        cancelledByRole = by;
        cancelledAt = at;
        openSubjectUserId = null;
    }

    public void claim(Instant at) {
        status = ErasureStatus.PROCESSING;
        processingStartedAt = at;
        attempts++;
    }

    public void complete(Instant at, String counts) {
        status = ErasureStatus.COMPLETED;
        completedAt = at;
        categoryCounts = counts;
        failureCode = null;
        openSubjectUserId = null;
    }

    public void fail(String code) {
        status = ErasureStatus.FAILED;
        failureCode = code;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubjectUserId() {
        return subjectUserId;
    }

    public UUID getInstitutionId() {
        return institutionId;
    }

    public UserRole getRequestedByRole() {
        return requestedByRole;
    }

    public ErasureStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getGraceEndsAt() {
        return graceEndsAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UserRole getCancelledByRole() {
        return cancelledByRole;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getCategoryCounts() {
        return categoryCounts;
    }
}
