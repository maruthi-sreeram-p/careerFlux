package com.careerflux.ai.proposal;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * What a resume reader thinks the profile should say, waiting for the student.
 *
 * <p>This is the record of a question, not of a change. Nothing here has touched
 * the profile; approving it is a separate, explicit act, and until that happens
 * the profile is exactly as the student left it.
 *
 * <p>The proposed information lives in {@link #payload} as the diff the student
 * was shown — see the V14 migration for why it is one document rather than a
 * table of fields, and for the list of things deliberately not kept in it.
 */
@Entity
@Table(name = "ai_profile_proposals")
public class AiProfileProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProposalStatus status = ProposalStatus.PENDING;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "engine", nullable = false, length = 64)
    private String engine;

    @Column(name = "ai_assisted", nullable = false)
    private boolean aiAssisted;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /**
     * Makes a double-approved proposal impossible rather than unlikely.
     *
     * <p>Checking that the status is still PENDING inside the transaction is not
     * enough on its own: two requests can both read PENDING before either
     * commits, and both would then apply their decisions to the profile. With a
     * version the second commit fails outright, which is the behaviour that
     * matters — a student who double-clicks Approve gets one profile update.
     */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Null while pending, and null forever for a proposal retired by a newer upload. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    /**
     * Moves the proposal out of PENDING because a person decided something.
     *
     * <p>Kept on the entity so that a status and its review stamp cannot be set
     * apart from each other — the database rejects an APPROVED row with no
     * review time, and this is what makes sure the code never tries.
     */
    public void reviewedAs(ProposalStatus decision, User reviewer, Instant when) {
        this.status = decision;
        this.reviewedBy = reviewer;
        this.reviewedAt = when;
        this.updatedAt = when;
    }

    /**
     * Records that the reading failed. Not a review, so no reviewer is named
     * and no review time is set — the database's CHECK only demands one of
     * those for a decision a person actually made.
     */
    public void markFailed(Instant when) {
        this.status = ProposalStatus.FAILED;
        this.updatedAt = when;
    }

    /** Retires the proposal because a newer resume was read. Not a review. */
    public void supersede(Instant when) {
        this.status = ProposalStatus.SUPERSEDED;
        this.updatedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public CandidateProfile getCandidate() {
        return candidate;
    }

    public void setCandidate(CandidateProfile candidate) {
        this.candidate = candidate;
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public boolean isAiAssisted() {
        return aiAssisted;
    }

    public void setAiAssisted(boolean aiAssisted) {
        this.aiAssisted = aiAssisted;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }
}
