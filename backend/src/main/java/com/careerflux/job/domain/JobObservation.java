package com.careerflux.job.domain;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.source.domain.JobSource;

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
 * Provenance: one row per (source, external id) sighting of a canonical job.
 *
 * <p>Deduplication in CareerFlux never deletes. When two sources list the same
 * opening, the second one becomes another observation of the same {@link Job},
 * and both remain visible. That is what lets a job detail page answer "where did
 * this come from, and when did we first and last see it there?".
 */
@Entity
@Table(name = "job_observations")
public class JobObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private JobSource source;

    @Column(name = "external_job_id", nullable = false, length = 200)
    private String externalJobId;

    @Column(name = "requisition_id", length = 120)
    private String requisitionId;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    /** The source's own payload, kept so the pipeline can be replayed against it. */
    @Column(name = "raw_payload", columnDefinition = "text")
    private String rawPayload;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "observation_count", nullable = false)
    private int observationCount = 1;

    /** False once the source stops listing this job. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "first_observed_at", nullable = false)
    private Instant firstObservedAt = Instant.now();

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public JobSource getSource() {
        return source;
    }

    public void setSource(JobSource source) {
        this.source = source;
    }

    public String getExternalJobId() {
        return externalJobId;
    }

    public void setExternalJobId(String externalJobId) {
        this.externalJobId = externalJobId;
    }

    public String getRequisitionId() {
        return requisitionId;
    }

    public void setRequisitionId(String requisitionId) {
        this.requisitionId = requisitionId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public int getObservationCount() {
        return observationCount;
    }

    public void setObservationCount(int observationCount) {
        this.observationCount = observationCount;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }

    public void setFirstObservedAt(Instant firstObservedAt) {
        this.firstObservedAt = firstObservedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(Instant lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }
}
