package com.careerflux.source.domain;

/** The access route CareerFlux would use, from most to least clearly permitted. */
public enum AccessPolicyType {
    /** A documented public API intended for programmatic use. */
    PUBLIC_API,
    /** A syndication feed published for exactly this purpose. */
    PUBLIC_FEED,
    /** A public page that robots.txt permits and terms do not restrict. */
    PUBLIC_PAGE,
    /** Access would need credentials, a session, or a paid tier. Not permitted. */
    RESTRICTED,
    /** Access would need bypassing a control. Never permitted. */
    FORBIDDEN,
    NOT_DETERMINED
}
