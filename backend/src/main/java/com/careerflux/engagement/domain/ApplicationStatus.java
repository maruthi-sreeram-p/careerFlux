package com.careerflux.engagement.domain;

/** Where an application has reached. Only meaningful on APPLIED interactions. */
public enum ApplicationStatus {
    APPLIED,
    IN_REVIEW,
    INTERVIEWING,
    OFFER,
    REJECTED,
    WITHDRAWN
}
