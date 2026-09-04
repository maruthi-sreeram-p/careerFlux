package com.careerflux.source.adapter;

import com.careerflux.source.domain.SourceHealthStatus;

/** What a health probe observed. Every field is measured, never assumed. */
public record SourceHealthResult(
        SourceHealthStatus status,
        Integer httpStatus,
        Integer latencyMs,
        Integer jobsSeen,
        String message,
        FailureClassification classification,
        java.time.Duration retryAfter) {

    public static SourceHealthResult healthy(int httpStatus, int latencyMs, int jobsSeen) {
        return new SourceHealthResult(SourceHealthStatus.HEALTHY, httpStatus, latencyMs, jobsSeen,
                "Responded with " + jobsSeen + " postings in " + latencyMs + " ms.", null, null);
    }

    /** A failure that will keep happening: the board is gone, or we are not allowed in. */
    public static SourceHealthResult failing(Integer httpStatus, Integer latencyMs, String message) {
        return new SourceHealthResult(SourceHealthStatus.FAILING, httpStatus, latencyMs, null, message,
                FailureClassification.UPSTREAM_PERMANENT, null);
    }

    /** Nothing answered. Permanent by default; callers who know better say so. */
    public static SourceHealthResult unreachable(String message) {
        return new SourceHealthResult(SourceHealthStatus.UNREACHABLE, null, null, null, message,
                FailureClassification.UPSTREAM_PERMANENT, null);
    }

    /**
     * The source asked us to slow down.
     *
     * <p>Recorded so an operator can see it, but never counted toward taking the
     * source out of service: a 429 means the board is working.
     */
    public static SourceHealthResult throttled(Integer httpStatus, String message,
                                               java.time.Duration retryAfter) {
        return new SourceHealthResult(SourceHealthStatus.DEGRADED, httpStatus, null, null, message,
                FailureClassification.UPSTREAM_THROTTLED, retryAfter);
    }

    /** A 5xx, a timeout, or a dropped connection: worth trying again on schedule. */
    public static SourceHealthResult transientFailure(Integer httpStatus, Integer latencyMs, String message) {
        return new SourceHealthResult(SourceHealthStatus.DEGRADED, httpStatus, latencyMs, null, message,
                FailureClassification.UPSTREAM_TRANSIENT, null);
    }

    /**
     * We declined to make the request; the source was never contacted.
     *
     * <p>Carries no health status of its own, because a request nobody sent is
     * not evidence about anything.
     */
    public static SourceHealthResult notAttempted(String message) {
        return new SourceHealthResult(SourceHealthStatus.UNKNOWN, null, null, null, message,
                FailureClassification.NOT_ATTEMPTED, null);
    }

    /**
     * We looked, but there was nothing to measure — no adapter, or a lifecycle
     * state that forbids contact.
     *
     * <p>Distinct from {@link #notAttempted}: this <em>is</em> recorded, so the
     * source's last-checked timestamp advances and the monitor does not select
     * it again every cycle. It simply is not a failure.
     */
    public static SourceHealthResult unmeasurable(String message) {
        return new SourceHealthResult(SourceHealthStatus.UNKNOWN, null, null, null, message, null, null);
    }

    /** A response we could not read, or one refused by policy before it was sent. */
    public static SourceHealthResult unusable(Integer httpStatus, String message,
                                              FailureClassification classification) {
        return new SourceHealthResult(SourceHealthStatus.FAILING, httpStatus, null, null, message,
                classification, null);
    }

    /**
     * The observation a classified adapter failure amounts to.
     *
     * <p>One translation, used by both the health probe and ingestion, so the
     * two can never disagree about what a 429 means.
     */
    public static SourceHealthResult from(AdapterException ex, Integer latencyMs) {
        return switch (ex.getClassification()) {
            case NOT_ATTEMPTED -> notAttempted(ex.getMessage());
            case UPSTREAM_THROTTLED -> throttled(ex.getHttpStatus(), ex.getMessage(), ex.getRetryAfter());
            case UPSTREAM_TRANSIENT -> transientFailure(ex.getHttpStatus(), latencyMs, ex.getMessage());
            case MALFORMED_RESPONSE, EMPTY_RESPONSE, POLICY_BLOCK ->
                    unusable(ex.getHttpStatus(), ex.getMessage(), ex.getClassification());
            case UPSTREAM_PERMANENT -> ex.getHttpStatus() == null
                    ? unreachable(ex.getMessage())
                    : failing(ex.getHttpStatus(), latencyMs, ex.getMessage());
        };
    }

    /** Whether this observation counts against the source at all. */
    public boolean isFailure() {
        return classification != null && classification.isObservation();
    }
}
