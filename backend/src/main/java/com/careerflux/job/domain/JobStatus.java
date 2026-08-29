package com.careerflux.job.domain;

public enum JobStatus {
    /** Currently listed by at least one source. */
    OPEN,
    /** Disappeared from every source that used to list it. */
    CLOSED,
    /** Still listed but not observed for long enough that it is probably stale. */
    EXPIRED,
    /** Was closed and has appeared again. */
    REOPENED
}
