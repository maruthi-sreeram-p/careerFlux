package com.careerflux.matching.domain;

/**
 * Whether a candidate can reasonably apply, judged only on conditions the
 * posting explicitly states.
 *
 * <p>Deliberately independent of the compatibility score. A candidate can be a
 * superb technical fit for a role they are not allowed to take, and a single
 * number could never say both things at once.
 */
public enum EligibilityStatus {

    /** Nothing blocks the application and nothing required is missing. */
    ELIGIBLE,

    /** Nothing blocks the application, but something asked for is missing. */
    ELIGIBLE_WITH_GAPS,

    /** At least one explicitly stated condition is not met. */
    NOT_ELIGIBLE,

    /**
     * Eligibility could not be evaluated either way.
     *
     * <p>Either side can cause this: a posting that states no condition to
     * check, or a profile too incomplete to check one against. The second is
     * why this is not the same as {@link #ELIGIBLE} — a candidate who has
     * listed nothing is missing nothing, and would otherwise be told they
     * qualify on the strength of an empty profile.
     */
    UNKNOWN
}
