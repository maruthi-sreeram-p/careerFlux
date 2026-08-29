package com.careerflux.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.source.domain.JobSource;

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
 * One pass of the pipeline over one source. The counters are the real numbers the
 * operations console shows; none of them is estimated or padded.
 */
@Entity
@Table(name = "ingestion_runs")
public class IngestionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_id")
    private JobSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24)
    private IngestionTrigger triggerType = IngestionTrigger.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IngestionStatus status = IngestionStatus.RUNNING;

    /** Ties every event this run emitted together in the logs. */
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "raw_count", nullable = false)
    private int rawCount;

    @Column(name = "normalized_count", nullable = false)
    private int normalizedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "closed_count", nullable = false)
    private int closedCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public UUID getId() {
        return id;
    }

    public JobSource getSource() {
        return source;
    }

    public void setSource(JobSource source) {
        this.source = source;
    }

    public IngestionTrigger getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(IngestionTrigger triggerType) {
        this.triggerType = triggerType;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(IngestionStatus status) {
        this.status = status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public int getRawCount() {
        return rawCount;
    }

    public void setRawCount(int rawCount) {
        this.rawCount = rawCount;
    }

    public int getNormalizedCount() {
        return normalizedCount;
    }

    public void setNormalizedCount(int normalizedCount) {
        this.normalizedCount = normalizedCount;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(int duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public int getNewCount() {
        return newCount;
    }

    public void setNewCount(int newCount) {
        this.newCount = newCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public void setUpdatedCount(int updatedCount) {
        this.updatedCount = updatedCount;
    }

    public int getClosedCount() {
        return closedCount;
    }

    public void setClosedCount(int closedCount) {
        this.closedCount = closedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
