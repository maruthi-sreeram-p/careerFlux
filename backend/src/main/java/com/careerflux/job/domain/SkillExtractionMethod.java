package com.careerflux.job.domain;

/** How a job skill was identified, so the UI can distinguish stated facts from inferences. */
public enum SkillExtractionMethod {
    /** Matched against the canonical skill dictionary by exact term. */
    DICTIONARY,
    /** Proposed by the language model while reading the description. */
    AI,
    /** Supplied as a structured field by the source itself. */
    SOURCE
}
