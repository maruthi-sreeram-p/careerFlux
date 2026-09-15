package com.careerflux.security.access;

import java.util.Set;
import java.util.UUID;

import com.careerflux.user.UserRole;

/**
 * Which slice of which institution the caller may act on, resolved once per
 * request from their role and their {@link
 * com.careerflux.institution.domain.StaffScope} grants.
 *
 * <p>This is the value every authorization decision is made against. Keeping it
 * immutable and computed up-front means a check can never accidentally consult
 * a half-loaded entity graph, and it makes the rules testable without a database.
 *
 * <p><b>A department coordinator is department-scoped.</b> Department grants say
 * which students they may see; batch grants can only narrow that to particular
 * batches inside those departments. A batch grant never adds a student on its
 * own, never reaches across departments and never reaches the whole college, so
 * a coordinator holding batch grants and no department sees nobody.
 */
public record AccessScope(
        UUID userId,
        UUID institutionId,
        UserRole role,
        boolean institutionWide,
        Set<UUID> departmentIds,
        Set<UUID> batchIds) {

    public AccessScope {
        departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
        batchIds = batchIds == null ? Set.of() : Set.copyOf(batchIds);
    }

    /** A portal administrator: no institution, and no student visibility at all. */
    public static AccessScope platform(UUID userId) {
        return new AccessScope(userId, null, UserRole.PORTAL_ADMIN, false, Set.of(), Set.of());
    }

    /** A student: scoped to themselves and nothing else. */
    public static AccessScope student(UUID userId, UUID institutionId) {
        return new AccessScope(userId, institutionId, UserRole.STUDENT, false, Set.of(), Set.of());
    }

    /** True when this caller can see every student in their institution. */
    public boolean seesWholeInstitution() {
        return institutionWide;
    }

    /**
     * Whether batch grants narrow this caller to particular batches inside their
     * departments. Never true for an institution-wide caller.
     */
    public boolean hasBatchRestriction() {
        return !institutionWide && !batchIds.isEmpty();
    }

    /**
     * Whether a student sitting in the given department and batch falls inside
     * this scope.
     *
     * <p>A student with no department yet — newly registered, not assigned — is
     * only visible to an institution-wide caller. Being unassigned must not make
     * someone visible to everybody. For the same reason a student with no batch
     * is outside a scope that is narrowed to batches.
     */
    public boolean covers(UUID studentDepartmentId, UUID studentBatchId) {
        if (institutionWide) {
            return true;
        }
        if (studentDepartmentId == null || !departmentIds.contains(studentDepartmentId)) {
            return false;
        }
        return batchIds.isEmpty() || (studentBatchId != null && batchIds.contains(studentBatchId));
    }

    /**
     * True when this caller has no way to see any student at all — including a
     * department coordinator whose only grants are batches, because a batch
     * grant is a restriction, not a scope.
     */
    public boolean isEmpty() {
        return !institutionWide && departmentIds.isEmpty();
    }
}
