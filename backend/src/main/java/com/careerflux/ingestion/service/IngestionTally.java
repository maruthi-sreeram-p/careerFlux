package com.careerflux.ingestion.service;

/**
 * The running totals for one pass of the pipeline over one source.
 *
 * <p>These seven numbers used to be accumulated directly on the {@code
 * ingestion_runs} entity while the whole run sat inside a single transaction.
 * That works only for as long as one transaction spans everything: a managed
 * entity belongs to the persistence context that loaded it, so once the fetch
 * and the per-posting work move into transactions of their own, incrementing a
 * field on it from inside them is no longer something that can be relied upon.
 *
 * <p>So the arithmetic happens here instead, in memory. This class holds no
 * entity, no repository, no Spring bean and no transaction state, which is the
 * whole point — it can be carried across transaction boundaries because it does
 * not belong to any of them. The service copies the totals onto the run entity
 * once, at the end, where a single managed write is unambiguous.
 *
 * <p>Not thread-safe, and not required to be: one run accumulates on one thread.
 */
public final class IngestionTally {

    private int rawCount;
    private int normalizedCount;
    private int newCount;
    private int updatedCount;
    private int duplicateCount;
    private int closedCount;
    private int errorCount;

    /** How many postings the source presented. Set once, not accumulated. */
    public void recordRaw(int count) {
        this.rawCount = count;
    }

    /**
     * A posting that came through normalization intact.
     *
     * <p>Recorded the moment normalization returns, not when the posting is
     * finished with. A posting that normalizes and then fails further down is
     * counted here and in {@link #recordError()} both, which is what the
     * pipeline has always done.
     */
    public void recordNormalized() {
        this.normalizedCount++;
    }

    /** Applies one posting's outcome. {@code UPDATED} adds to the duplicate already recorded. */
    public void record(PostingOutcome outcome) {
        switch (outcome) {
            case NEW -> newCount++;
            case DUPLICATE -> duplicateCount++;
            case UPDATED -> updatedCount++;
        }
    }

    /** A posting we could not process, whether it was malformed or it threw. */
    public void recordError() {
        this.errorCount++;
    }

    /** How many jobs went CLOSED because no source lists them any more. Set once. */
    public void recordClosed(int count) {
        this.closedCount = count;
    }

    public int rawCount() {
        return rawCount;
    }

    public int normalizedCount() {
        return normalizedCount;
    }

    public int newCount() {
        return newCount;
    }

    public int updatedCount() {
        return updatedCount;
    }

    public int duplicateCount() {
        return duplicateCount;
    }

    public int closedCount() {
        return closedCount;
    }

    public int errorCount() {
        return errorCount;
    }

    /** True when every posting the source presented was processed without incident. */
    public boolean isClean() {
        return errorCount == 0;
    }

    @Override
    public String toString() {
        return "raw=" + rawCount + " normalized=" + normalizedCount + " new=" + newCount
                + " updated=" + updatedCount + " duplicate=" + duplicateCount
                + " closed=" + closedCount + " errors=" + errorCount;
    }
}
