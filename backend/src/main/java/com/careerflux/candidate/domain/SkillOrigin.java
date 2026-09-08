package com.careerflux.candidate.domain;

/** Where a candidate skill came from, so the UI can show what was inferred versus stated. */
public enum SkillOrigin {

    /** Read out of the resume by the built-in parser. */
    RESUME,

    /** The candidate typed it, or edited what was proposed to them. */
    MANUAL,

    /** Derived by CareerFlux from something else the candidate said. */
    INFERRED,

    /**
     * A language model read it out of the resume and the candidate approved it.
     *
     * <p>This records the SOURCE, not the confirmation. Confirmation is a
     * separate fact and lives on the proposal the candidate reviewed, which
     * carries who approved it and when; nothing reaches this table unapproved,
     * because an unapproved suggestion is not a skill the candidate has, it is
     * a question they have not answered yet.
     *
     * <p>It is distinct from {@link #RESUME} because "the model read this" and
     * "a regular expression matched this" are different claims about how much
     * the value should be trusted, and a student is entitled to see which one
     * they agreed to.
     */
    AI_SUGGESTION
}
