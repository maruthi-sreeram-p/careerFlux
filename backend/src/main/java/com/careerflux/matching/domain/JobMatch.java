package com.careerflux.matching.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.institution.domain.Institution;
import com.careerflux.job.domain.Job;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * One candidate's fit against one job.
 *
 * <p>The overall score is never stored on its own. The five sub-scores that
 * produced it are here, and the individual reasons are in
 * {@link MatchComponent}, so the question "why did this job get 92%?" always has
 * a complete answer that does not require re-running the scorer.
 *
 * <p>{@code scorerVersion} records which version of the scoring rules produced
 * the row, so a rules change can be identified rather than silently reinterpreted.
 */
@Entity
@Table(name = "job_matches")
public class JobMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    /** Denormalised tenant key. Every placement-dashboard query starts here. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "institution_id")
    private Institution institution;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Column(name = "skill_score", nullable = false)
    private int skillScore;

    @Column(name = "experience_score", nullable = false)
    private int experienceScore;

    @Column(name = "role_score", nullable = false)
    private int roleScore;

    @Column(name = "location_score", nullable = false)
    private int locationScore;

    @Column(name = "seniority_score", nullable = false)
    private int seniorityScore;

    @Column(name = "work_mode_score", nullable = false)
    private int workModeScore;

    /** Whether the candidate can apply at all, judged only on stated conditions. */
    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility", length = 24)
    private EligibilityStatus eligibility = EligibilityStatus.UNKNOWN;

    /** How much of the model's weight could actually be compared. */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", length = 16)
    private ConfidenceLevel confidenceLevel = ConfidenceLevel.INSUFFICIENT;

    @Column(name = "confidence_coverage", nullable = false)
    private int confidenceCoverage;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 24)
    private MatchTier tier = MatchTier.HIDDEN;

    /** The "why this matters" paragraph. */
    @Column(name = "narrative", length = 2000)
    private String narrative;

    /** Which engine wrote the narrative: a model id, or "rules". */
    @Column(name = "narrative_engine", length = 64)
    private String narrativeEngine;

    @Column(name = "scorer_version", nullable = false, length = 32)
    private String scorerVersion;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    private List<MatchComponent> components = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public CandidateProfile getCandidate() {
        return candidate;
    }

    public void setCandidate(CandidateProfile candidate) {
        this.candidate = candidate;
        // Kept in step with the candidate so it can never disagree with it.
        this.institution = candidate == null ? null : candidate.getInstitution();
    }

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public int getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(int overallScore) {
        this.overallScore = overallScore;
    }

    public int getSkillScore() {
        return skillScore;
    }

    public void setSkillScore(int skillScore) {
        this.skillScore = skillScore;
    }

    public int getExperienceScore() {
        return experienceScore;
    }

    public void setExperienceScore(int experienceScore) {
        this.experienceScore = experienceScore;
    }

    public int getRoleScore() {
        return roleScore;
    }

    public void setRoleScore(int roleScore) {
        this.roleScore = roleScore;
    }

    public int getLocationScore() {
        return locationScore;
    }

    public void setLocationScore(int locationScore) {
        this.locationScore = locationScore;
    }

    public int getSeniorityScore() {
        return seniorityScore;
    }

    public int getWorkModeScore() {
        return workModeScore;
    }

    public void setWorkModeScore(int workModeScore) {
        this.workModeScore = workModeScore;
    }

    public EligibilityStatus getEligibility() {
        return eligibility;
    }

    public void setEligibility(EligibilityStatus eligibility) {
        this.eligibility = eligibility;
    }

    public ConfidenceLevel getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(ConfidenceLevel confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public int getConfidenceCoverage() {
        return confidenceCoverage;
    }

    public void setConfidenceCoverage(int confidenceCoverage) {
        this.confidenceCoverage = confidenceCoverage;
    }

    public void setSeniorityScore(int seniorityScore) {
        this.seniorityScore = seniorityScore;
    }

    public MatchTier getTier() {
        return tier;
    }

    public void setTier(MatchTier tier) {
        this.tier = tier;
    }

    public String getNarrative() {
        return narrative;
    }

    public void setNarrative(String narrative) {
        this.narrative = narrative;
    }

    public String getNarrativeEngine() {
        return narrativeEngine;
    }

    public void setNarrativeEngine(String narrativeEngine) {
        this.narrativeEngine = narrativeEngine;
    }

    public String getScorerVersion() {
        return scorerVersion;
    }

    public void setScorerVersion(String scorerVersion) {
        this.scorerVersion = scorerVersion;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }

    public List<MatchComponent> getComponents() {
        return components;
    }
}
