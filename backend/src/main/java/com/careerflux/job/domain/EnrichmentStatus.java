package com.careerflux.job.domain;

public enum EnrichmentStatus {
    PENDING,
    ENRICHED,
    /** Enrichment was attempted and failed. The job is still usable, just less structured. */
    FAILED,
    /** Nothing to enrich, e.g. the source already supplied every structured field. */
    SKIPPED
}
