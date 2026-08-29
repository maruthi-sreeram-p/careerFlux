package com.careerflux.source.domain;

/** Observed operational health. Always derived from real sync and probe results. */
public enum SourceHealthStatus {
    /** Never checked. Shown as "not yet checked", never as healthy. */
    UNKNOWN,
    HEALTHY,
    /** Responding, but slowly or with fewer results than the last successful run. */
    DEGRADED,
    /** Reachable but returning errors. */
    FAILING,
    /** No response at all. */
    UNREACHABLE
}
