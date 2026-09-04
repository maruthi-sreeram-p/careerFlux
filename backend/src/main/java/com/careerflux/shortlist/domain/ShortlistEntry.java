package com.careerflux.shortlist.domain;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Version;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One student the placement team chose to put forward for one requirement.
 *
 * <p>A decision, not a derivation. CareerFlux ranks students and states whether
 * the company's conditions are met; it never converts either of those into a
 * row here. Every entry exists because a person with placement authority acted.
 *
 * <p>It holds no copy of the score, the eligibility verdict or the profile.
 * Those are live facts about a student and they move; a snapshot taken at the
 * moment of shortlisting would become a second version of the truth that
 * nothing keeps current. Opening a shortlist next month shows next month's
 * candidates, which is what a placement officer actually wants to see before a
 * drive.
 *
 * <p>The corollary matters too: a student whose score drops, or who stops
 * meeting a stated condition, is <em>not</em> silently removed. A human put
 * them on the list and only a human takes them off.
 */
@Entity
@Table(name = "company_requirement_shortlists")
public class ShortlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private CompanyRequirement requirement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    /** Nullable: the decision outlives the account that made it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Where this candidate has reached in the drive.
     *
     * <p>Every row starts here, because being put forward is the first thing
     * that happens. Nothing advances it except an authorised person deciding to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    private PlacementStage stage = PlacementStage.SHORTLISTED;

    /**
     * When the stage last moved, or null if it never has.
     *
     * <p>Deliberately not defaulted to {@code createdAt}: a row that has only
     * ever been shortlisted has not had a stage change, and saying it did would
     * claim a decision nobody made.
     */
    @Column(name = "stage_changed_at")
    private Instant stageChangedAt;

    /**
     * Guards two people acting on the same candidate at once.
     *
     * <p>Both would otherwise read the same stage, both find the transition
     * legal, and both write — leaving one row and two history entries claiming
     * to be the move. The second writer loses here instead, and is told the
     * candidate has already moved.
     */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public UUID getId() {
        return id;
    }

    public CompanyRequirement getRequirement() {
        return requirement;
    }

    public void setRequirement(CompanyRequirement requirement) {
        this.requirement = requirement;
    }

    public CandidateProfile getCandidate() {
        return candidate;
    }

    public void setCandidate(CandidateProfile candidate) {
        this.candidate = candidate;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PlacementStage getStage() {
        return stage;
    }

    public void setStage(PlacementStage stage) {
        this.stage = stage;
    }

    public Instant getStageChangedAt() {
        return stageChangedAt;
    }

    public void setStageChangedAt(Instant stageChangedAt) {
        this.stageChangedAt = stageChangedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
