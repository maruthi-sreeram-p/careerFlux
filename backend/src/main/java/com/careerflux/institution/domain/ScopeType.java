package com.careerflux.institution.domain;

/**
 * How wide a staff member's view of their institution is.
 *
 * <p>Scopes are additive. A coordinator holding two DEPARTMENT scopes sees both
 * departments and nothing else; one holding an INSTITUTION scope sees everyone.
 */
public enum ScopeType {
    INSTITUTION,
    DEPARTMENT,
    BATCH
}
