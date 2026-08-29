package com.careerflux.institution.domain;

public enum InstitutionStatus {
    /** Onboarding: staff exist, students are not yet using it. */
    PROVISIONING,
    ACTIVE,
    /** Retained for reporting, but nobody can sign in. */
    SUSPENDED
}
