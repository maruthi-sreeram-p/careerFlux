package com.careerflux.source.domain;

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
 * One transition in a source's lifecycle. Together these rows are the complete,
 * timestamped story of how a source reached its current state and who decided it.
 */
@Entity
@Table(name = "source_lifecycle_events")
public class SourceLifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private JobSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32)
    private SourceState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 32)
    private SourceState toState;

    @Column(name = "reason", length = 600)
    private String reason;

    @Column(name = "actor", nullable = false, length = 160)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public JobSource getSource() {
        return source;
    }

    public void setSource(JobSource source) {
        this.source = source;
    }

    public SourceState getFromState() {
        return fromState;
    }

    public void setFromState(SourceState fromState) {
        this.fromState = fromState;
    }

    public SourceState getToState() {
        return toState;
    }

    public void setToState(SourceState toState) {
        this.toState = toState;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
