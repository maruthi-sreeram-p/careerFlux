package com.careerflux.matching.domain;

/** The axes a match is scored on. Weights live in {@code careerflux.matching.*}. */
public enum MatchDimension {

    SKILLS,
    EXPERIENCE,
    ROLE,
    LOCATION,
    SENIORITY,

    /** Remote, hybrid or on-site, against the candidate's stated preference. */
    WORK_MODE,

    /** Facts worth showing that carry no weight in the score. */
    CONTEXT
}
