package com.careerflux.source.domain;

/** Result of evaluating the site's robots.txt against the path CareerFlux would fetch. */
public enum RobotsStatus {
    NOT_CHECKED,
    ALLOWED,
    DISALLOWED,
    /** No robots.txt is published, which under the standard means no restriction. */
    NOT_PUBLISHED,
    /** The file could not be fetched or parsed; treated as restrictive until it can be. */
    UNAVAILABLE
}
