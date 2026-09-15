package com.careerflux.candidate.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.careerflux.common.BaseEntity;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.institution.domain.Institution;
import com.careerflux.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * The candidate's career intelligence profile: everything CareerFlux knows about
 * who they are and what they are looking for. Populated from a resume, then
 * corrected by the candidate; both paths write the same fields.
 */
@Entity
@Table(name = "candidate_profiles")
public class CandidateProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * The tenant, denormalised from the owning user. Reachable through
     * {@code user.institution}, but stored here because every institutional
     * query filters on it and a join on the hottest read path is a poor trade.
     * Written once at creation; a student does not change college.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "institution_id", updatable = false)
    private Institution institution;

    @Column(name = "headline", length = 200)
    private String headline;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "location", length = 160)
    private String location;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "linkedin_url", length = 300)
    private String linkedinUrl;

    @Column(name = "github_url", length = 300)
    private String githubUrl;

    @Column(name = "portfolio_url", length = 300)
    private String portfolioUrl;

    @Column(name = "primary_role", length = 120)
    private String primaryRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "seniority", length = 32)
    private Seniority seniority = Seniority.UNSPECIFIED;

    @Column(name = "years_experience", precision = 4, scale = 1)
    private BigDecimal yearsExperience;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_stage", nullable = false, length = 32)
    private OnboardingStage onboardingStage = OnboardingStage.RESUME_UPLOAD;

    @Column(name = "profile_completeness", nullable = false)
    private int profileCompleteness;

    /**
     * The student's cumulative grade average <b>as the college recorded it</b>,
     * or null when the college has not. This is the verified record and the only
     * CGPA eligibility reads. The student's own figure is {@link #reportedCgpa},
     * a separate field they can change without touching this one (V19).
     *
     * <p>Null is a real state and is not zero: a student with no CGPA on file is
     * unknown, not failing.
     *
     * <p>This is deliberately here rather than on {@code CandidateEducation}.
     * That is an ordered list of qualifications — school, higher secondary,
     * degree — with nothing marking which is current, and reading a hiring bar
     * off "the first row" would be a guess. This field is the student's standing
     * in their current enrolment at this college, alongside their department and
     * batch.
     */
    @Column(name = "cgpa", precision = 4, scale = 2)
    private BigDecimal cgpa;

    /** The maximum of the scale both CGPA figures are expressed on. Ten in India. */
    @Column(name = "cgpa_scale", nullable = false, precision = 4, scale = 2)
    private BigDecimal cgpaScale = Cgpa.DEFAULT_SCALE;

    /** Always INSTITUTION when {@link #cgpa} holds a value, and null when it does not. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cgpa_source", length = 24)
    private CgpaSource cgpaSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cgpa_recorded_by")
    private User cgpaRecordedBy;

    @Column(name = "cgpa_recorded_at")
    private Instant cgpaRecordedAt;

    /**
     * The figure the student entered for themselves, or null. Shown back to them
     * and to their placement staff, labelled as theirs. Never verified and never
     * read by eligibility — a student cannot answer a company's stated minimum
     * about themselves.
     */
    @Column(name = "reported_cgpa", precision = 4, scale = 2)
    private BigDecimal reportedCgpa;

    @Column(name = "reported_cgpa_recorded_at")
    private Instant reportedCgpaRecordedAt;

    @OneToMany(mappedBy = "candidate", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CandidateSkill> skills = new ArrayList<>();

    /**
     * Skills this student named that the shared dictionary does not know. Kept
     * here and nowhere else: never added to the dictionary, never shown to
     * staff, never matched. See {@link CandidateCustomSkill}.
     */
    @OneToMany(mappedBy = "candidate", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CandidateCustomSkill> customSkills = new ArrayList<>();

    @OneToMany(mappedBy = "candidate", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    private List<CandidateExperience> experiences = new ArrayList<>();

    @OneToMany(mappedBy = "candidate", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    private List<CandidateEducation> education = new ArrayList<>();

    @OneToOne(mappedBy = "candidate", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private CandidatePreferences preferences;

    public User getUser() {
        return user;
    }

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public java.util.UUID getInstitutionId() {
        return institution == null ? null : institution.getId();
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLinkedinUrl() {
        return linkedinUrl;
    }

    public void setLinkedinUrl(String linkedinUrl) {
        this.linkedinUrl = linkedinUrl;
    }

    public String getGithubUrl() {
        return githubUrl;
    }

    public void setGithubUrl(String githubUrl) {
        this.githubUrl = githubUrl;
    }

    public String getPortfolioUrl() {
        return portfolioUrl;
    }

    public void setPortfolioUrl(String portfolioUrl) {
        this.portfolioUrl = portfolioUrl;
    }

    public String getPrimaryRole() {
        return primaryRole;
    }

    public void setPrimaryRole(String primaryRole) {
        this.primaryRole = primaryRole;
    }

    public Seniority getSeniority() {
        return seniority;
    }

    public void setSeniority(Seniority seniority) {
        this.seniority = seniority == null ? Seniority.UNSPECIFIED : seniority;
    }

    public BigDecimal getYearsExperience() {
        return yearsExperience;
    }

    public void setYearsExperience(BigDecimal yearsExperience) {
        this.yearsExperience = yearsExperience;
    }

    public OnboardingStage getOnboardingStage() {
        return onboardingStage;
    }

    public void setOnboardingStage(OnboardingStage onboardingStage) {
        this.onboardingStage = onboardingStage;
    }

    public int getProfileCompleteness() {
        return profileCompleteness;
    }

    public void setProfileCompleteness(int profileCompleteness) {
        this.profileCompleteness = profileCompleteness;
    }

    public List<CandidateSkill> getSkills() {
        return skills;
    }

    public List<CandidateCustomSkill> getCustomSkills() {
        return customSkills;
    }

    public List<CandidateExperience> getExperiences() {
        return experiences;
    }

    public List<CandidateEducation> getEducation() {
        return education;
    }

    public CandidatePreferences getPreferences() {
        return preferences;
    }

    public void setPreferences(CandidatePreferences preferences) {
        this.preferences = preferences;
        if (preferences != null) {
            preferences.setCandidate(this);
        }
    }

    /** The college's recorded CGPA as stored. Prefer {@link #getVerifiedCgpa()} for any decision. */
    public BigDecimal getCgpa() {
        return cgpa;
    }

    public BigDecimal getCgpaScale() {
        return cgpaScale == null ? Cgpa.DEFAULT_SCALE : cgpaScale;
    }

    public void setCgpaScale(BigDecimal cgpaScale) {
        this.cgpaScale = cgpaScale;
    }

    public CgpaSource getCgpaSource() {
        return cgpaSource;
    }

    public User getCgpaRecordedBy() {
        return cgpaRecordedBy;
    }

    public Instant getCgpaRecordedAt() {
        return cgpaRecordedAt;
    }

    public BigDecimal getReportedCgpa() {
        return reportedCgpa;
    }

    public Instant getReportedCgpaRecordedAt() {
        return reportedCgpaRecordedAt;
    }

    /**
     * The CGPA eligibility may compare against, or null.
     *
     * <p>Verified means an institution recorded it. A student's own figure is
     * kept and shown to them, and never answers a company's stated minimum —
     * otherwise a student would be deciding their own eligibility for a drive.
     */
    public BigDecimal getVerifiedCgpa() {
        return cgpaSource != null && cgpaSource.isVerified() ? cgpa : null;
    }

    /**
     * The college recording its CGPA for this student, or clearing it.
     *
     * <p>Only this writes the verified record, and it never touches the
     * student's own figure. Clearing wipes the provenance too: a value with no
     * source could not be judged, and a source with no value means nothing.
     */
    public void recordVerifiedCgpa(BigDecimal value, User by) {
        this.cgpa = value;
        this.cgpaSource = value == null ? null : CgpaSource.INSTITUTION;
        this.cgpaRecordedBy = value == null ? null : by;
        this.cgpaRecordedAt = value == null ? null : Instant.now();
    }

    /**
     * The student recording their own figure, or clearing it.
     *
     * <p>Writes nothing but the student's own field. The verified record, its
     * source and who recorded it are out of reach from here, which is the whole
     * point: a student's entry can no longer replace the college's.
     */
    public void recordReportedCgpa(BigDecimal value) {
        this.reportedCgpa = value;
        this.reportedCgpaRecordedAt = value == null ? null : Instant.now();
    }
}
