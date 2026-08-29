package com.careerflux.candidate.domain;

public enum ResumeParseStatus {
    /** Stored, text not yet extracted. */
    PENDING,
    /** Text extraction and structured parsing in flight. */
    PARSING,
    /** Structured profile produced. */
    PARSED,
    /** Text was extracted but structuring failed; the candidate fills the profile manually. */
    NEEDS_REVIEW,
    FAILED
}
