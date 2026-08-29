package com.careerflux.job.domain;

/**
 * The kinds of change CareerFlux detects between observations of the same job.
 * These are what make the product more interesting than a job board: a candidate
 * can see that a requirement was added or a location moved.
 */
public enum JobChangeType {
    CREATED,
    UPDATED,
    CLOSED,
    REOPENED,
    TITLE_CHANGED,
    LOCATION_CHANGED,
    REQUIREMENTS_CHANGED,
    SALARY_CHANGED,
    WORK_MODE_CHANGED,
    NEW_SOURCE_OBSERVED
}
