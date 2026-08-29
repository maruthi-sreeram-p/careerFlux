package com.careerflux.matching.rematch;

/** Where a queued rescoring request has got to. */
public enum RematchStatus {

    /** Waiting for a worker. */
    PENDING,

    /** A worker has claimed it. */
    RUNNING,

    /** Finished; the candidate's matches reflect the current scorer version. */
    COMPLETED,

    /** Gave up after repeated failures. Visible to operators rather than silent. */
    FAILED
}
