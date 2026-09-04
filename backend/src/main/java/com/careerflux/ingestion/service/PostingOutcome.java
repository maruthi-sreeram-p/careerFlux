package com.careerflux.ingestion.service;

/**
 * What processing one posting turned out to be.
 *
 * <p>These were implicit before: the pipeline read them off the deduplicator's
 * resolution and incremented a counter on the run entity at the point it found
 * out. Naming them makes the mapping to counters reviewable, and lets posting
 * processing report its result instead of reaching into a JPA entity — which is
 * what has to change before the work can be split across transactions.
 *
 * <p>Note that these are not mutually exclusive categories of posting, because
 * the counters they feed never were. A repeat posting the employer has edited is
 * both a {@link #DUPLICATE} and an {@link #UPDATED}: it is counted once in each,
 * exactly as it is today. {@link #UPDATED} is therefore a refinement of
 * {@code DUPLICATE} rather than an alternative to it.
 *
 * <p>There is deliberately no {@code ERROR} constant. A posting that fails is
 * signalled by an exception, which the caller catches — that is the existing
 * control flow, and it also carries the message the run records. Turning it into
 * a return value would change how failures propagate, which this slice must not
 * do.
 */
public enum PostingOutcome {

    /** A job we had not seen before. Feeds {@code newCount}. */
    NEW,

    /** A repeat sighting of a job we already hold. Feeds {@code duplicateCount}. */
    DUPLICATE,

    /**
     * A repeat sighting from the same source that carried a real change.
     * Feeds {@code updatedCount}, on top of the {@code DUPLICATE} already
     * recorded for it.
     */
    UPDATED
}
