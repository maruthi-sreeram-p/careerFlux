package com.careerflux.candidate.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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

/**
 * A skill a student named that the shared dictionary does not know.
 *
 * <p>Held on the student's own profile and nowhere else. The dictionary
 * ({@code skills}) is shared by every college and matched against every job
 * posting, so a student's own words are never added to it. Nothing that serves
 * staff, discovery, matching or ingestion reads this table: a skill here is the
 * student's record of themselves, shown back to them and to nobody else.
 */
@Entity
@Table(name = "candidate_custom_skills")
public class CandidateCustomSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    /** As the student wrote it, after the same clean-up the dictionary applies. */
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** The name's skill slug: one entry per spelling, for this student only. */
    @Column(name = "name_key", nullable = false, length = 120)
    private String nameKey;

    @Column(name = "proficiency", length = 24)
    private String proficiency;

    @Column(name = "years", precision = 4, scale = 1)
    private BigDecimal years;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 24)
    private SkillOrigin origin = SkillOrigin.MANUAL;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public CandidateProfile getCandidate() {
        return candidate;
    }

    public void setCandidate(CandidateProfile candidate) {
        this.candidate = candidate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public void setNameKey(String nameKey) {
        this.nameKey = nameKey;
    }

    public String getProficiency() {
        return proficiency;
    }

    public void setProficiency(String proficiency) {
        this.proficiency = proficiency;
    }

    public BigDecimal getYears() {
        return years;
    }

    public void setYears(BigDecimal years) {
        this.years = years;
    }

    public SkillOrigin getOrigin() {
        return origin;
    }

    public void setOrigin(SkillOrigin origin) {
        this.origin = origin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
