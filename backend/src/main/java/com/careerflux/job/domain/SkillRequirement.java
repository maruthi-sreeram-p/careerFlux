package com.careerflux.job.domain;

/**
 * How firmly a posting asks for a skill.
 *
 * <p>Ordered strongest first, which {@code SkillRequirementClassifier} relies on
 * when a skill is mentioned more than once: the strongest evidence in the
 * document wins.
 *
 * <p>Matching weights these very differently. A missing REQUIRED skill is a real
 * gap in the candidate's fit; a missing OPTIONAL one usually means the posting
 * mentioned a technology in passing.
 */
public enum SkillRequirement {

    /** Stated as necessary: a requirements list, "must have", "3+ years of". */
    REQUIRED,

    /** Stated as welcome but not necessary: "nice to have", "a plus", "bonus". */
    PREFERRED,

    /**
     * Mentioned without being asked for — named in the responsibilities, the
     * team description, or the company's stack. The default when a posting gives
     * no signal, because inventing a requirement is the more damaging error.
     */
    OPTIONAL
}
