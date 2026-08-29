package com.careerflux.candidate.domain;

/** Discriminator for the single multi-valued preference table. */
public enum PreferenceType {
    TARGET_ROLE,
    INDUSTRY,
    LOCATION,
    WORK_MODE,
    EMPLOYMENT_TYPE,
    PREFERRED_COMPANY
}
