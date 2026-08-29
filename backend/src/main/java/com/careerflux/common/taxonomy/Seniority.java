package com.careerflux.common.taxonomy;

/**
 * Ordered seniority ladder. The ordinal matters: matching measures the distance
 * between a candidate level and a job level, so the declaration order is part of
 * the contract and must stay lowest-to-highest.
 */
public enum Seniority {
    INTERN,
    ENTRY,
    JUNIOR,
    MID,
    SENIOR,
    LEAD,
    PRINCIPAL,
    UNSPECIFIED;

    public boolean isKnown() {
        return this != UNSPECIFIED;
    }

    /** Number of rungs between two levels, or -1 when either side is unknown. */
    public int distanceTo(Seniority other) {
        if (!isKnown() || other == null || !other.isKnown()) {
            return -1;
        }
        return Math.abs(ordinal() - other.ordinal());
    }
}
