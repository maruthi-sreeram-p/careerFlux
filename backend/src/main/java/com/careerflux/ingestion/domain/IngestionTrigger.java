package com.careerflux.ingestion.domain;

public enum IngestionTrigger {
    SCHEDULED,
    MANUAL,
    /** Run immediately after a source was activated, to populate it. */
    ACTIVATION
}
