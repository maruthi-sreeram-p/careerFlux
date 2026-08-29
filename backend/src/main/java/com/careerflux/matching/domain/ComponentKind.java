package com.careerflux.matching.domain;

/** What a single explanation row is saying about the match. */
public enum ComponentKind {

    /** Something that lines up. */
    STRENGTH,

    /** Something missing or short, which lowers the score but permits applying. */
    GAP,

    /** A fact worth stating that moves the score neither way. */
    NEUTRAL,

    /**
     * A stated condition the candidate does not meet. Distinct from a GAP: a gap
     * is a weaker application, a blocker is not an application at all.
     */
    BLOCKER,

    /**
     * A dimension that could not be compared. Carries no score and no weight —
     * it exists so the explanation can say what was not known, and by whom.
     */
    UNKNOWN
}
