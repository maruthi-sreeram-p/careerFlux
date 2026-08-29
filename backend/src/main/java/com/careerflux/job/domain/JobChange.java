package com.careerflux.job.domain;

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
 * One detected difference between successive observations of a job. The
 * candidate-facing value is the summary line, which is written to be readable on
 * its own: "Spring Boot added to the requirements".
 */
@Entity
@Table(name = "job_changes")
public class JobChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "observation_id")
    private JobObservation observation;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 40)
    private JobChangeType changeType;

    @Column(name = "field_name", length = 80)
    private String fieldName;

    @Column(name = "previous_value", length = 2000)
    private String previousValue;

    @Column(name = "new_value", length = 2000)
    private String newValue;

    @Column(name = "summary", nullable = false, length = 600)
    private String summary;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public JobObservation getObservation() {
        return observation;
    }

    public void setObservation(JobObservation observation) {
        this.observation = observation;
    }

    public JobChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(JobChangeType changeType) {
        this.changeType = changeType;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public void setPreviousValue(String previousValue) {
        this.previousValue = previousValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }
}
