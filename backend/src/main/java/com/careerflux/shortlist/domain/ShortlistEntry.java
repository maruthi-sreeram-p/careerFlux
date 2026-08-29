package com.careerflux.shortlist.domain;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.user.User;

import jakarta.persistence.Column;
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
}
