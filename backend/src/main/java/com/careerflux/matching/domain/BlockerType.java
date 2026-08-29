package com.careerflux.matching.domain;

/**
 * A condition a posting states that a candidate demonstrably fails.
 *
 * <p>Blockers are never inferred. A different city is not a blocker; a missing
 * skill is never a blocker. Only a stated condition with a checkable answer
 * belongs here, which is why the list is short — and why it stays short until
 * the data exists to extend it honestly.
 */
public enum BlockerType {

    /**
     * The posting states a minimum experience and the candidate is short of it
     * by more than the tolerated margin.
     */
    EXPERIENCE_BELOW_MINIMUM,

    /**
     * The posting is explicitly on-site somewhere the candidate has not said
     * they will work, and they are not open to relocation. Hybrid and
     * unspecified postings never produce this.
     */
    ONSITE_UNREACHABLE
}
