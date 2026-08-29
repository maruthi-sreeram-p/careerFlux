package com.careerflux.matching.domain;

/**
 * How a match is presented, derived from the score together with eligibility,
 * gaps and confidence.
 *
 * <p>The tier reads those inputs; it never alters the score. Compatibility
 * stays the plain arithmetic result so a candidate asking "why 84?" can be
 * answered with the sum rather than with a rule that adjusted it.
 */
public enum MatchTier {

    /**
     * Requires a high score, eligibility, nothing required missing, and high
     * confidence. This is the gate that answers the original complaint: a match
     * cannot be excellent while something the posting requires is absent.
     */
    EXCELLENT,

    STRONG,

    MODERATE,

    WEAK,

    /** An explicitly stated condition is unmet, whatever the score says. */
    NOT_ELIGIBLE,

    /** Too little known to publish a number. */
    UNAVAILABLE,

    /** Below the visibility floor. Stored for auditability, never shown. */
    HIDDEN;

    /**
     * Whether this match belongs in a recommendation feed.
     *
     * <p>Three tiers are stored but never recommended, for three different
     * reasons: the fit is too weak, the candidate is not permitted to apply, or
     * too little was known to have an opinion. Only the first was excluded
     * before rules-2 introduced the other two.
     */
    public boolean isRecommendable() {
        return this != HIDDEN && this != NOT_ELIGIBLE && this != UNAVAILABLE;
    }
}
