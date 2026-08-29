package com.careerflux.candidate.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Scalar preferences. Everything multi-valued (target roles, industries,
 * locations, work modes, employment types, preferred companies) lives in
 * {@link CandidatePreferenceValue} rather than in six parallel tables.
 */
@Entity
@Table(name = "candidate_preferences")
public class CandidatePreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    private CandidateProfile candidate;

    @Column(name = "salary_min", precision = 12, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 12, scale = 2)
    private BigDecimal salaryMax;

    @Column(name = "salary_currency", length = 8)
    private String salaryCurrency;

    @Column(name = "salary_period", length = 16)
    private String salaryPeriod;

    @Column(name = "open_to_relocation", nullable = false)
    private boolean openToRelocation;

    @Column(name = "min_experience_years", precision = 4, scale = 1)
    private BigDecimal minExperienceYears;

    @Column(name = "max_experience_years", precision = 4, scale = 1)
    private BigDecimal maxExperienceYears;

    @Column(name = "notification_channel", nullable = false, length = 24)
    private String notificationChannel = "IN_APP";

    @Column(name = "immediate_alerts", nullable = false)
    private boolean immediateAlerts = true;

    @Column(name = "daily_digest", nullable = false)
    private boolean dailyDigest = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
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

    public BigDecimal getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(BigDecimal salaryMin) {
        this.salaryMin = salaryMin;
    }

    public BigDecimal getSalaryMax() {
        return salaryMax;
    }

    public void setSalaryMax(BigDecimal salaryMax) {
        this.salaryMax = salaryMax;
    }

    public String getSalaryCurrency() {
        return salaryCurrency;
    }

    public void setSalaryCurrency(String salaryCurrency) {
        this.salaryCurrency = salaryCurrency;
    }

    public String getSalaryPeriod() {
        return salaryPeriod;
    }

    public void setSalaryPeriod(String salaryPeriod) {
        this.salaryPeriod = salaryPeriod;
    }

    public boolean isOpenToRelocation() {
        return openToRelocation;
    }

    public void setOpenToRelocation(boolean openToRelocation) {
        this.openToRelocation = openToRelocation;
    }

    public BigDecimal getMinExperienceYears() {
        return minExperienceYears;
    }

    public void setMinExperienceYears(BigDecimal minExperienceYears) {
        this.minExperienceYears = minExperienceYears;
    }

    public BigDecimal getMaxExperienceYears() {
        return maxExperienceYears;
    }

    public void setMaxExperienceYears(BigDecimal maxExperienceYears) {
        this.maxExperienceYears = maxExperienceYears;
    }

    public String getNotificationChannel() {
        return notificationChannel;
    }

    public void setNotificationChannel(String notificationChannel) {
        this.notificationChannel = notificationChannel;
    }

    public boolean isImmediateAlerts() {
        return immediateAlerts;
    }

    public void setImmediateAlerts(boolean immediateAlerts) {
        this.immediateAlerts = immediateAlerts;
    }

    public boolean isDailyDigest() {
        return dailyDigest;
    }

    public void setDailyDigest(boolean dailyDigest) {
        this.dailyDigest = dailyDigest;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
