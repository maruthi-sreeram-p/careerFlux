package com.careerflux.ai.proposal;

/**
 * Where a proposal is in its one-way lifecycle.
 *
 * <p>Every proposal starts PENDING and leaves it exactly once. Nothing returns
 * to PENDING: a student who changes their mind uploads their resume again,
 * which produces a new reading rather than reopening an old decision.
 *
 * <p>There is no FAILED. When extraction fails there is nothing to review, so no
 * proposal is written at all and the failure is recorded on the resume, which
 * already has a FAILED status for exactly this. A proposal that could never be
 * created in that state would be a name for something the system does not do.
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
    SUPERSEDED;

    /** Whether a student's decision can still change anything. */
    public boolean isOpen() {
        return this == PENDING;
    }
}
