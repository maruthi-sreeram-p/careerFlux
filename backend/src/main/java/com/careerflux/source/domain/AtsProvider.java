package com.careerflux.source.domain;

/** The applicant tracking system behind a source, when one could be identified. */
public enum AtsProvider {
    GREENHOUSE,
    LEVER,
    ASHBY,
    SMARTRECRUITERS,
    RECRUITEE,
    WORKDAY,
    WORKABLE,
    BREEZY,
    ORACLE_HCM,
    /** Hosted boards on applytojob.com. Recognised in discovery; its API needs the employer's own key. */
    JAZZHR,
    /** Hosted career portals on icims.com. Recognised in discovery; its API needs partner credentials. */
    ICIMS,
    OTHER,
    NONE,
    UNKNOWN
}
