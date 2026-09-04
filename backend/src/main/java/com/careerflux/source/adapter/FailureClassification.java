package com.careerflux.source.adapter;

/**
 * Why a fetch did not produce jobs, and what that should cost the source.
 *
 * <p>Before this existed every failure was the same event: a deleted board, a
 * momentarily overloaded one, and one politely asking us to slow down all
 * incremented the same counter and walked the source toward PENDING_REVIEW —
 * out of service until a person looked at it. A 429 is not a broken source. It
 * is a working source telling us exactly what to do, and treating it as a fault
 * retires the healthiest, busiest boards first.
 *
 * <p>The distinction that matters is not "did it work" but <em>whether trying
 * again later is reasonable</em>:
 *
 * <ul>
 *   <li><b>Not attempted</b> — we chose not to call. Says nothing about the source.
 *   <li><b>Transient</b> — the source is there and may well answer next time.
 *       Worth showing an operator; never worth retiring the source over.
 *   <li><b>Permanent</b> — trying again unchanged will fail the same way, so
 *       repetition should eventually stop and ask for a human.
 * </ul>
 */
public enum FailureClassification {

    /**
     * CareerFlux's own pacing refused the request; nothing was sent.
     *
     * <p>Not a failure of any kind. Recording it against the source claims the
     * board was unreachable when we never asked it anything.
     */
    NOT_ATTEMPTED(false),

    /** HTTP 429. The source is working and asking us to slow down. */
    UPSTREAM_THROTTLED(false),

    /**
     * 5xx, 408, timeouts, connection failures.
     *
     * <p>Retrying on the normal schedule is the right response. A source that
     * stays broken becomes visible through repeated degradation without being
     * pulled out of service on the strength of an outage.
     */
    UPSTREAM_TRANSIENT(false),

    /**
     * 4xx other than 408 and 429 — most importantly 404, 401 and 403.
     *
     * <p>The board is gone, or we are not allowed in. Repeating the same request
     * will keep failing, so this is the class that should eventually stop and
     * ask for a person.
     */
    UPSTREAM_PERMANENT(true),

    /**
     * A 2xx whose body could not be read as the API it claims to be.
     *
     * <p>Permanent by default: a provider changing its schema does not fix
     * itself, and quietly retrying forever hides the fact that an adapter now
     * needs updating.
     */
    MALFORMED_RESPONSE(true),

    /** A 2xx with nothing in it. Same reasoning as malformed. */
    EMPTY_RESPONSE(true),

    /**
     * Refused before leaving the process — robots, access policy, or a redirect
     * into somewhere the SSRF guard will not follow.
     *
     * <p>Permanent, because the answer will not change until configuration or
     * the site does.
     */
    POLICY_BLOCK(true);

    private final boolean escalates;

    FailureClassification(boolean escalates) {
        this.escalates = escalates;
    }

    /**
     * Whether repetition should eventually take the source out of service.
     *
     * <p>Transient classes still degrade — an operator can see a source that
     * keeps throttling or timing out — but never reach PENDING_REVIEW, which
     * demands human attention the source does not need.
     */
    public boolean escalatesToReview() {
        return escalates;
    }

    /** Whether this says anything at all about the source's health. */
    public boolean isObservation() {
        return this != NOT_ATTEMPTED;
    }
}
