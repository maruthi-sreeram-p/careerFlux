package com.careerflux.source.domain;

/** What kind of endpoint a source is. Drives which adapter and which policy rules apply. */
public enum SourceType {
    /** A documented, public JSON endpoint published by an applicant tracking system. */
    ATS_PUBLIC_API,
    /** A company-hosted careers page rendered as HTML. */
    COMPANY_CAREER_PAGE,
    /** A job board with an official public API. */
    JOB_BOARD_API,
    /** An RSS or Atom feed published for syndication. */
    FEED,
    /** A public sector or government job portal. */
    GOVERNMENT_PORTAL,
    /** Local fixture data used for development and demos. Never presented as a real source. */
    LOCAL_FIXTURE,
    UNKNOWN
}
