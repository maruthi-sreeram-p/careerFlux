package com.careerflux.ai.quota;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * How many AI requests one subject has made today.
 *
 * <p>One row per subject per day. The row is the unit of contention: two
 * concurrent requests for the same student both target it, and the database
 * serialises them, which is what makes the limit hold under load.
 */
@Entity
@Table(name = "ai_usage_counters")
public class AiUsageCounter {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private AiQuotaScope scope;

    /** The user id or the institution id, depending on {@link #scope}. */
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiUsageCounter() {
    }

    public AiUsageCounter(AiQuotaScope scope, UUID scopeId, LocalDate usageDate, int usedCount) {
        this.id = UUID.randomUUID();
        this.scope = scope;
        this.scopeId = scopeId;
        this.usageDate = usageDate;
        this.usedCount = usedCount;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public AiQuotaScope getScope() {
        return scope;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public int getUsedCount() {
        return usedCount;
    }
}
