package com.careerflux.ai.proposal;

/**
 * Where a proposal is in its one-way lifecycle.
 *
 * <p>Every proposal starts PENDING and leaves it exactly once. Only PENDING is
 * open; every other status is terminal. A student who changes their mind uploads
 * their resume again, which produces a new reading rather than reopening an old
 * decision.
 */
public enum ProposalStatus {

    /** Waiting for the student. The only status from which anything can happen. */
    PENDING,

    /** The student reviewed it and some or all of it was written to the profile. */
    APPROVED,

    /** The student reviewed it and nothing was written. */
    REJECTED,

    /**
     * Retired without ever being reviewed, because a newer resume was read.
     * Not a decision, so it records no reviewer and no review time.
     */
    SUPERSEDED,

    /**
     * The reading itself did not finish, so there was never anything to review.
     *
     * <p>Recorded rather than left absent. A student whose extraction failed has
     * a stored resume and no proposal, and "no proposal" looks exactly like
     * "never uploaded anything" on the review screen. This row says what
     * actually happened. It carries no items, is not a human decision, and — like
     * every status that is not PENDING — cannot be approved.
     */
    FAILED;

    /** Whether a student's decision can still change anything. */
    public boolean isOpen() {
        return this == PENDING;
    }
}
