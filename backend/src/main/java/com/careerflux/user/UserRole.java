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
 * The four actors CareerFlux recognises, and what each may do.
 *
 * <p>These are the product's actors, named the way the product names them. The
 * roles stored before migration V16 did not match: what the code called a
 * placement coordinator was department-scoped staff, and the college's own
 * administrator was split across a placement officer and a college
 * administrator. V16 maps every stored role onto these four:
 *
 * <ul>
 *   <li>{@code PLACEMENT_COORDINATOR} (department-scoped) → {@link #DEPARTMENT_COORDINATOR}
 *   <li>{@code PLACEMENT_OFFICER} and {@code COLLEGE_ADMIN} → {@link #PLACEMENT_COORDINATOR}
 *   <li>{@code PLATFORM_ADMIN} → {@link #PORTAL_ADMIN}
 *   <li>{@code STUDENT} → {@link #STUDENT}
 * </ul>
 *
 * <p>Two things are worth reading carefully here.
 *
 * <p><b>A department coordinator cannot open a resume.</b> They can see whether a
 * student is ready and how they match, which is what the job needs; the document
 * itself is the student's own. The placement coordinator can, because they run
 * the drives the document is submitted to. This is data minimisation expressed
 * in the type system rather than in a policy document.
 *
 * <p><b>Shortlisting and authoring a requirement are separate permissions.</b>
 * A department coordinator holds {@code PLACEMENT_SHORTLIST_MANAGE} and can put
 * their own department's students forward; they do not hold
 * {@code PLACEMENT_DRIVE_MANAGE} and cannot create, edit, publish or close a
 * company requirement. The distinction matters because requirement authoring is
 * unscoped by design — a requirement may target any department or none — while
 * shortlisting is checked against the caller's own scope on every write.
 *
 * <p>A Head of Department is not a role. "HOD" is at most a job title held by a
 * department coordinator, and nothing that decides access reads it.
 */
public enum UserRole {

    /** A job seeker. Sees only their own data. */
    STUDENT(EnumSet.of(
            SELF_PROFILE_MANAGE,
            SELF_JOBS_MANAGE,
            SELF_AI_USE,
            SELF_ACCOUNT_DELETE)),

    /**
     * Placement staff scoped to departments or batches within one college. Sees
     * readiness for the students they support, and the market those students are
     * entering.
     */
    DEPARTMENT_COORDINATOR(EnumSet.of(
            STUDENT_READ_SCOPED,
            ANALYTICS_VIEW,
            JOB_MARKET_VIEW,
            PLACEMENT_DRIVE_VIEW,
            // Shortlisting, but not authoring. A department coordinator decides
            // who from their department goes forward; what the college is hiring
            // for is not theirs to write. The scope that makes this safe is not in
            // this list — every shortlist write is checked against the
            // coordinator's own AccessScope, so this permission reaches exactly
            // the students STUDENT_READ_SCOPED already lets them see.
            PLACEMENT_SHORTLIST_MANAGE,
            ANNOUNCEMENT_SEND,
            STAFF_MANAGE)),

    /**
     * The college's own administrator, who also runs its placement: departments,
     * batches, staff, enrolment, drives and institution-wide placement activity.
     * One institution, never another.
     *
     * <p>Exactly what the old placement officer and college administrator roles
     * held between them. Keeping those apart left the person who administers a
     * college unable to see the students whose placement they administer, which
     * is not a split the product has.
     */
    PLACEMENT_COORDINATOR(EnumSet.of(
            STUDENT_READ_SCOPED,
            STUDENT_READ_INSTITUTION,
            STUDENT_RESUME_READ,
            STUDENT_MANAGE,
            ANALYTICS_VIEW,
            JOB_MARKET_VIEW,
            PLACEMENT_DRIVE_VIEW,
            PLACEMENT_DRIVE_MANAGE,
            PLACEMENT_SHORTLIST_MANAGE,
            PLACEMENT_ELIGIBILITY_MANAGE,
            ANNOUNCEMENT_SEND,
            INSTITUTION_SETTINGS_MANAGE,
            DEPARTMENT_MANAGE,
            BATCH_MANAGE,
            STAFF_MANAGE,
            AUDIT_READ_INSTITUTION)),

    /**
     * Operates CareerFlux itself. Belongs to no institution, and holds no
     * student-read permission: owning the platform is not a reason to read a
     * college's students.
     */
    PORTAL_ADMIN(EnumSet.of(
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
        return this != PORTAL_ADMIN;
    }

    /** True for roles that act on other people's data and therefore need a scope. */
    public boolean isStaff() {
        return this == PLACEMENT_COORDINATOR || this == DEPARTMENT_COORDINATOR;
    }
}
