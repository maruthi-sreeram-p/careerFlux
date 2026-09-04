package com.careerflux.source.adapter;

import java.time.Duration;

/**
 * Signals that an adapter could not read its source.
 *
 * <p>Carries a {@link FailureClassification} so the caller can tell a source
 * that has gone from one that is merely busy. Without it every failure looked
 * alike, and a board answering 429 was retired on the same schedule as a board
 * that had been deleted.
 */
public class AdapterException extends RuntimeException {

    private final Integer httpStatus;
    private final FailureClassification classification;
    private final Duration retryAfter;

    public AdapterException(String message) {
        this(message, null, FailureClassification.UPSTREAM_PERMANENT, null, null);
    }

    public AdapterException(String message, Integer httpStatus) {
        this(message, httpStatus, FailureClassification.UPSTREAM_PERMANENT, null, null);
    }

    public AdapterException(String message, Integer httpStatus, Throwable cause) {
        this(message, httpStatus, FailureClassification.UPSTREAM_PERMANENT, null, cause);
    }

    public AdapterException(String message, Integer httpStatus, FailureClassification classification,
                            Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.classification = classification == null
                ? FailureClassification.UPSTREAM_PERMANENT : classification;
        this.retryAfter = retryAfter;
    }

    /** Convenience for the classified cases, which rarely have a cause. */
    public static AdapterException of(String message, Integer httpStatus,
                                      FailureClassification classification) {
        return new AdapterException(message, httpStatus, classification, null, null);
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public FailureClassification getClassification() {
        return classification;
    }

    /** How long the source asked us to wait, already capped. Null when it did not say. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
