package com.careerflux.ingestion.domain;

public enum IngestionStatus {
    RUNNING,
    SUCCEEDED,
    /** Some postings were ingested and some failed. */
    PARTIAL,
    FAILED,
    /** Not attempted: the source was not in a state that permits fetching. */
    SKIPPED
}
