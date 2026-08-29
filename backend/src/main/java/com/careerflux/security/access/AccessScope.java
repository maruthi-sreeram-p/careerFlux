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

    /** A platform administrator: no institution, and no student visibility at all. */
    public static AccessScope platform(UUID userId) {
        return new AccessScope(userId, null, UserRole.PLATFORM_ADMIN, false, Set.of(), Set.of());
    }

    /** A student: scoped to themselves and nothing else. */
    public static AccessScope student(UUID userId, UUID institutionId) {
        return new AccessScope(userId, institutionId, UserRole.STUDENT, false, Set.of(), Set.of());
    }

    public boolean isPlatformAdmin() {
        return role == UserRole.PLATFORM_ADMIN;
    }

    /** True when this caller can see every student in their institution. */
    public boolean seesWholeInstitution() {
        return institutionWide;
    }

    /**
     * Whether a student sitting in the given department and batch falls inside
     * this scope.
     *
     * <p>A student with no department yet — newly registered, not assigned — is
     * only visible to an institution-wide caller. Being unassigned must not make
     * someone visible to everybody.
     */
    public boolean covers(UUID studentDepartmentId, UUID studentBatchId) {
        if (institutionWide) {
            return true;
        }
        if (studentDepartmentId != null && departmentIds.contains(studentDepartmentId)) {
            return true;
        }
        return studentBatchId != null && batchIds.contains(studentBatchId);
    }

    /** True when this caller has no way to see any student at all. */
    public boolean isEmpty() {
        return !institutionWide && departmentIds.isEmpty() && batchIds.isEmpty();
    }
}
