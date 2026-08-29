package com.careerflux.source.domain;

/** How CareerFlux came to know about a source. Shown verbatim on the source detail page. */
public enum DiscoveryMethod {
    /** Part of the curated starting registry. */
    SEED,
    /** Found by probing a known public ATS endpoint pattern for a company identifier. */
    ATS_PROBE,
    /** Found by inspecting a company domain for a careers page. */
    DOMAIN_INSPECTION,
    /** Submitted by an operator through the admin console. */
    MANUAL_SUBMISSION,
    /** Reached from an apply URL on a job that arrived through another source. */
    REFERRAL_FROM_JOB
}
