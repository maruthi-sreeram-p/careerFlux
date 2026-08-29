package com.careerflux.ai.quota;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageCounterRepository extends JpaRepository<AiUsageCounter, UUID> {

    /**
     * Consumes one unit of allowance, if there is any left.
     *
     * <p>This is the whole concurrency story. The check and the increment are a
     * single statement, so the database — not the application — decides who gets
     * the last unit. Reading the count and then writing it back would let two
     * requests both observe 24 of 25 and both proceed.
     *
     * @return 1 when a unit was consumed, 0 when the row is missing or the
     *         allowance is already spent
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiUsageCounter c
               set c.usedCount = c.usedCount + 1,
                   c.updatedAt = :now
             where c.scope = :scope
               and c.scopeId = :scopeId
               and c.usageDate = :usageDate
               and c.usedCount < :limit
            """)
    int consumeOne(@Param("scope") AiQuotaScope scope,
                   @Param("scopeId") UUID scopeId,
                   @Param("usageDate") LocalDate usageDate,
                   @Param("limit") int limit,
                   @Param("now") Instant now);

    /**
     * Gives a unit back after the AI call failed.
     *
     * <p>Guarded so a refund can never take a counter below zero, which would
     * hand out free allowance if it were ever called twice for one request.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiUsageCounter c
               set c.usedCount = c.usedCount - 1,
                   c.updatedAt = :now
             where c.scope = :scope
               and c.scopeId = :scopeId
               and c.usageDate = :usageDate
               and c.usedCount > 0
            """)
    int refundOne(@Param("scope") AiQuotaScope scope,
                  @Param("scopeId") UUID scopeId,
                  @Param("usageDate") LocalDate usageDate,
                  @Param("now") Instant now);

    Optional<AiUsageCounter> findByScopeAndScopeIdAndUsageDate(
            AiQuotaScope scope, UUID scopeId, LocalDate usageDate);

    /** Housekeeping: yesterday's counters are never read again. */
    @Modifying
    @Query("delete from AiUsageCounter c where c.usageDate < :before")
    int deleteOlderThan(@Param("before") LocalDate before);
}
