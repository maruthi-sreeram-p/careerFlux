package com.careerflux.user;

/**
 * A single thing a principal is allowed to do.
 *
 * <p>Permissions are granted through roles, and the mapping lives in
 * {@link UserRole} rather than in a database table. That is a deliberate choice:
 * there are five fixed roles whose capabilities are a product decision, not
 * something a college administrator should be able to redefine at runtime. A
 * permissions table would suggest an editor that does not and should not exist.
 *
 * <p>What <em>is</em> configurable per institution is <em>scope</em> — which
 * students a staff member can see — and that lives in
 * {@link com.careerflux.institution.domain.StaffScope}.
 *
 * <p>Each constant becomes a Spring Security authority, so it can be used
 * directly in {@code @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")}.
 */
public enum Permission {

    // --- Own data -----------------------------------------------------------
    /** Read and write one's own candidate profile, resume and preferences. */
    SELF_PROFILE_MANAGE,
    /** Save, dismiss and track jobs for oneself. */
    SELF_JOBS_MANAGE,
    /** Use AI features against one's own quota. */
    SELF_AI_USE,
    /** Request deletion of one's own account and derived data. */
    SELF_ACCOUNT_DELETE,

    // --- Student data seen by staff -----------------------------------------
    /**
     * Read student records within the caller's scope. Deliberately distinct from
     * reading a resume: a coordinator can see readiness without opening the
     * document.
     */
    STUDENT_READ_SCOPED,
    /** Read the full institution's students, ignoring department and batch scope. */
    STUDENT_READ_INSTITUTION,
    /** Open a student's actual resume file. The most sensitive student permission. */
    STUDENT_RESUME_READ,
    /** Create, edit and deactivate student accounts. */
    STUDENT_MANAGE,

    // --- Institutional intelligence -----------------------------------------
    /** Aggregate analytics: skill supply, demand, readiness. No individual records. */
    ANALYTICS_VIEW,
    /** Job-market intelligence across the corpus. */
    JOB_MARKET_VIEW,

    // --- Placement ----------------------------------------------------------
    PLACEMENT_DRIVE_VIEW,
    /**
     * Author a company requirement: create it, edit it, publish it, close it.
     *
     * <p>Deciding what the college is hiring for, which is an institution-wide
     * act. Creation applies no departmental scope — a requirement may target any
     * department or none at all — so this belongs to whoever speaks for the whole
     * placement office.
     */
    PLACEMENT_DRIVE_MANAGE,
    /**
     * Put a candidate forward for a requirement, or take one off.
     *
     * <p>Separate from {@link #PLACEMENT_DRIVE_MANAGE} because the two answer
     * different questions and need different reach. Authoring a requirement is
     * unscoped; shortlisting is scoped, and already is — every write runs through
     * {@code DiscoveryScope} against the caller's own {@code AccessScope}, so a
     * coordinator holding this can only ever act on the students they can already
     * see, and a request naming somebody else's student is answered as not-found.
     *
     * <p>They were one permission until a coordinator needed to shortlist within
     * their department. Granting the drive permission would have worked, and would
     * also have handed every coordinator the ability to write and publish company
     * requirements for the whole college — authority nobody asked for, acquired as
     * a side effect. Splitting them lets the narrow thing be granted narrowly.
     */
    PLACEMENT_SHORTLIST_MANAGE,
    /** Decide who is officially eligible for a drive, as distinct from who matches. */
    PLACEMENT_ELIGIBILITY_MANAGE,
    ANNOUNCEMENT_SEND,

    // --- Institution administration -----------------------------------------
    INSTITUTION_SETTINGS_MANAGE,
    DEPARTMENT_MANAGE,
    BATCH_MANAGE,
    /** Create staff accounts and assign their roles and scopes. */
    STAFF_MANAGE,
    /** Read the institution's own audit trail. */
    AUDIT_READ_INSTITUTION,

    // --- Platform operation -------------------------------------------------
    SOURCE_VIEW,
    SOURCE_MANAGE,
    INGESTION_MANAGE,
    SYSTEM_HEALTH_VIEW,
    /** Create and configure institutions. Platform-level only. */
    INSTITUTION_PROVISION,
    AUDIT_READ_PLATFORM,
    AI_USAGE_MANAGE
}
