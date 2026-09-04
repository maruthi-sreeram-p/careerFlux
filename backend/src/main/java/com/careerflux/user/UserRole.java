package com.careerflux.user;

import java.util.EnumSet;
import java.util.Set;

import static com.careerflux.user.Permission.AI_USAGE_MANAGE;
import static com.careerflux.user.Permission.ANALYTICS_VIEW;
import static com.careerflux.user.Permission.ANNOUNCEMENT_SEND;
import static com.careerflux.user.Permission.AUDIT_READ_INSTITUTION;
import static com.careerflux.user.Permission.AUDIT_READ_PLATFORM;
import static com.careerflux.user.Permission.BATCH_MANAGE;
import static com.careerflux.user.Permission.DEPARTMENT_MANAGE;
import static com.careerflux.user.Permission.INGESTION_MANAGE;
import static com.careerflux.user.Permission.INSTITUTION_PROVISION;
import static com.careerflux.user.Permission.INSTITUTION_SETTINGS_MANAGE;
import static com.careerflux.user.Permission.JOB_MARKET_VIEW;
import static com.careerflux.user.Permission.PLACEMENT_DRIVE_MANAGE;
import static com.careerflux.user.Permission.PLACEMENT_DRIVE_VIEW;
import static com.careerflux.user.Permission.PLACEMENT_ELIGIBILITY_MANAGE;
import static com.careerflux.user.Permission.PLACEMENT_SHORTLIST_MANAGE;
import static com.careerflux.user.Permission.SELF_ACCOUNT_DELETE;
import static com.careerflux.user.Permission.SELF_AI_USE;
import static com.careerflux.user.Permission.SELF_JOBS_MANAGE;
import static com.careerflux.user.Permission.SELF_PROFILE_MANAGE;
import static com.careerflux.user.Permission.SOURCE_MANAGE;
import static com.careerflux.user.Permission.SOURCE_VIEW;
import static com.careerflux.user.Permission.STAFF_MANAGE;
import static com.careerflux.user.Permission.STUDENT_MANAGE;
import static com.careerflux.user.Permission.STUDENT_READ_INSTITUTION;
import static com.careerflux.user.Permission.STUDENT_READ_SCOPED;
import static com.careerflux.user.Permission.STUDENT_RESUME_READ;
import static com.careerflux.user.Permission.SYSTEM_HEALTH_VIEW;

/**
 * The five roles CareerFlux recognises, and what each may do.
 *
 * <p>Two things are worth reading carefully here.
 *
 * <p><b>A coordinator cannot open a resume.</b> They can see whether a student
 * is ready and how they match, which is what the job needs; the document itself
 * is the student's own. A placement officer can, because they run the drives the
 * document is submitted to. This is data minimisation expressed in the type
 * system rather than in a policy document.
 *
 * <p><b>A college administrator is not a super-user.</b> They configure the
 * institution and manage accounts, but they are not granted student reads,
 * because administering a college is not a reason to read its students' career
 * profiles. If an administrator also needs that, they are given the placement
 * officer role as well.
 *
 * <p><b>Shortlisting and authoring a requirement are separate permissions.</b>
 * A coordinator holds {@code PLACEMENT_SHORTLIST_MANAGE} and can put their own
 * department's students forward; they do not hold
 * {@code PLACEMENT_DRIVE_MANAGE} and cannot create, edit, publish or close a
 * company requirement. The distinction matters because requirement authoring is
 * unscoped by design — a requirement may target any department or none — while
 * shortlisting is checked against the caller's own scope on every write.
 */
public enum UserRole {

    /** A job seeker. Sees only their own data. */
    STUDENT(EnumSet.of(
            SELF_PROFILE_MANAGE,
            SELF_JOBS_MANAGE,
            SELF_AI_USE,
            SELF_ACCOUNT_DELETE)),

    /**
     * Placement staff scoped to departments or batches. Sees readiness for the
     * students they support, and the market those students are entering.
     */
    PLACEMENT_COORDINATOR(EnumSet.of(
            STUDENT_READ_SCOPED,
            ANALYTICS_VIEW,
            JOB_MARKET_VIEW,
            PLACEMENT_DRIVE_VIEW,
            // Shortlisting, but not authoring. A coordinator decides who from
            // their department goes forward; what the college is hiring for is
            // not theirs to write. The scope that makes this safe is not in this
            // list — every shortlist write is checked against the coordinator's
            // own AccessScope, so this permission reaches exactly the students
            // STUDENT_READ_SCOPED already lets them see.
            PLACEMENT_SHORTLIST_MANAGE,
            ANNOUNCEMENT_SEND)),

    /** Runs placement for the whole institution. */
    PLACEMENT_OFFICER(EnumSet.of(
            STUDENT_READ_SCOPED,
            STUDENT_READ_INSTITUTION,
            STUDENT_RESUME_READ,
            ANALYTICS_VIEW,
            JOB_MARKET_VIEW,
            PLACEMENT_DRIVE_VIEW,
            PLACEMENT_DRIVE_MANAGE,
            PLACEMENT_SHORTLIST_MANAGE,
            PLACEMENT_ELIGIBILITY_MANAGE,
            ANNOUNCEMENT_SEND,
            AUDIT_READ_INSTITUTION)),

    /** Configures the college and manages its people. */
    COLLEGE_ADMIN(EnumSet.of(
            INSTITUTION_SETTINGS_MANAGE,
            DEPARTMENT_MANAGE,
            BATCH_MANAGE,
            STAFF_MANAGE,
            STUDENT_MANAGE,
            ANALYTICS_VIEW,
            AUDIT_READ_INSTITUTION)),

    /** Operates CareerFlux itself. Belongs to no institution. */
    PLATFORM_ADMIN(EnumSet.of(
            SOURCE_VIEW,
            SOURCE_MANAGE,
            INGESTION_MANAGE,
            SYSTEM_HEALTH_VIEW,
            INSTITUTION_PROVISION,
            AUDIT_READ_PLATFORM,
            AI_USAGE_MANAGE,
            JOB_MARKET_VIEW));

    private final Set<Permission> permissions;

    UserRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** True for the roles that belong to a college rather than to CareerFlux. */
    public boolean isInstitutional() {
        return this != PLATFORM_ADMIN;
    }

    /** True for roles that act on other people's data and therefore need a scope. */
    public boolean isStaff() {
        return this == PLACEMENT_COORDINATOR
                || this == PLACEMENT_OFFICER
                || this == COLLEGE_ADMIN;
    }
}
