package com.careerflux.source.domain;

import java.time.Instant;

import com.careerflux.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * A place CareerFlux gets jobs from, and everything it knows about whether it
 * may, whether it should, and whether it currently works.
 */
@Entity
@Table(name = "job_sources")
public class JobSource extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "base_url", nullable = false, unique = true, length = 500)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 48)
    private SourceType sourceType = SourceType.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_provider", nullable = false, length = 48)
    private AtsProvider atsProvider = AtsProvider.UNKNOWN;

    /** Which registered adapter handles this source. Null until classification succeeds. */
    @Column(name = "adapter_key", length = 64)
    private String adapterKey;

    /** The board token or company slug the adapter needs, e.g. the Greenhouse board name. */
    @Column(name = "external_identifier", length = 200)
    private String externalIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "discovery_method", nullable = false, length = 48)
    private DiscoveryMethod discoveryMethod = DiscoveryMethod.MANUAL_SUBMISSION;

    @Column(name = "discovery_detail", length = 600)
    private String discoveryDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private SourceState state = SourceState.DISCOVERED;

    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 24)
    private SourceHealthStatus healthStatus = SourceHealthStatus.UNKNOWN;

    @Column(name = "last_health_check_at")
    private Instant lastHealthCheckAt;

    @Column(name = "last_sync_attempt_at")
    private Instant lastSyncAttemptAt;

    @Column(name = "last_successful_sync_at")
    private Instant lastSuccessfulSyncAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "sync_success_count", nullable = false)
    private int syncSuccessCount;

    @Column(name = "sync_failure_count", nullable = false)
    private int syncFailureCount;

    @Column(name = "jobs_ingested_total", nullable = false)
    private int jobsIngestedTotal;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute = 20;

    @Column(name = "next_review_at")
    private Instant nextReviewAt;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt = Instant.now();

    @OneToOne(mappedBy = "source", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private SourceAccessPolicy accessPolicy;

    /**
     * Share of sync attempts that succeeded, or -1 when nothing has been attempted.
     * Computed from the counters rather than stored, so it can never be a number
     * nobody can account for.
     */
    public int reliabilityPercent() {
        int total = syncSuccessCount + syncFailureCount;
        return total == 0 ? -1 : Math.round((syncSuccessCount * 100f) / total);
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public AtsProvider getAtsProvider() {
        return atsProvider;
    }

    public void setAtsProvider(AtsProvider atsProvider) {
        this.atsProvider = atsProvider;
    }

    public String getAdapterKey() {
        return adapterKey;
    }

    public void setAdapterKey(String adapterKey) {
        this.adapterKey = adapterKey;
    }

    public String getExternalIdentifier() {
        return externalIdentifier;
    }

    public void setExternalIdentifier(String externalIdentifier) {
        this.externalIdentifier = externalIdentifier;
    }

    public DiscoveryMethod getDiscoveryMethod() {
        return discoveryMethod;
    }

    public void setDiscoveryMethod(DiscoveryMethod discoveryMethod) {
        this.discoveryMethod = discoveryMethod;
    }

    public String getDiscoveryDetail() {
        return discoveryDetail;
    }

    public void setDiscoveryDetail(String discoveryDetail) {
        this.discoveryDetail = discoveryDetail;
    }

    public SourceState getState() {
        return state;
    }

    public void setState(SourceState state) {
        this.state = state;
    }

    public Instant getStateChangedAt() {
        return stateChangedAt;
    }

    public void setStateChangedAt(Instant stateChangedAt) {
        this.stateChangedAt = stateChangedAt;
    }

    public SourceHealthStatus getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(SourceHealthStatus healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Instant getLastHealthCheckAt() {
        return lastHealthCheckAt;
    }

    public void setLastHealthCheckAt(Instant lastHealthCheckAt) {
        this.lastHealthCheckAt = lastHealthCheckAt;
    }

    public Instant getLastSyncAttemptAt() {
        return lastSyncAttemptAt;
    }

    public void setLastSyncAttemptAt(Instant lastSyncAttemptAt) {
        this.lastSyncAttemptAt = lastSyncAttemptAt;
    }

    public Instant getLastSuccessfulSyncAt() {
        return lastSuccessfulSyncAt;
    }

    public void setLastSuccessfulSyncAt(Instant lastSuccessfulSyncAt) {
        this.lastSuccessfulSyncAt = lastSuccessfulSyncAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public int getSyncSuccessCount() {
        return syncSuccessCount;
    }

    public void setSyncSuccessCount(int syncSuccessCount) {
        this.syncSuccessCount = syncSuccessCount;
    }

    public int getSyncFailureCount() {
        return syncFailureCount;
    }

    public void setSyncFailureCount(int syncFailureCount) {
        this.syncFailureCount = syncFailureCount;
    }

    public int getJobsIngestedTotal() {
        return jobsIngestedTotal;
    }

    public void setJobsIngestedTotal(int jobsIngestedTotal) {
        this.jobsIngestedTotal = jobsIngestedTotal;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public Instant getNextReviewAt() {
        return nextReviewAt;
    }

    public void setNextReviewAt(Instant nextReviewAt) {
        this.nextReviewAt = nextReviewAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public void setDiscoveredAt(Instant discoveredAt) {
        this.discoveredAt = discoveredAt;
    }

    public SourceAccessPolicy getAccessPolicy() {
        return accessPolicy;
    }

    public void setAccessPolicy(SourceAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
        if (accessPolicy != null) {
            accessPolicy.setSource(this);
        }
    }
}
