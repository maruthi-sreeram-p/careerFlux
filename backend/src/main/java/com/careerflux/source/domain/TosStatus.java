package com.careerflux.source.domain;

/** Outcome of the terms-of-service review. Only a human sets anything other than NOT_REVIEWED. */
public enum TosStatus {
    NOT_REVIEWED,
    /** Terms explicitly permit automated access to this endpoint. */
    PERMITTED,
    /** Permitted with conditions, e.g. attribution or a rate ceiling. */
    RESTRICTED,
    /** Terms prohibit automated access. The source can never become ACTIVE. */
    PROHIBITED,
    /** Reviewed but genuinely ambiguous. Treated as prohibitive. */
    UNCLEAR
}
