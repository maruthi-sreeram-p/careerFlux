package com.careerflux.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.ScopeType;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.security.AuthenticatedUser;
import com.careerflux.user.User;
import com.careerflux.user.UserRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How far each of the four roles can see, decided without a database.
 *
 * <p>The integration tests prove the boundary through the real filter chain. This
 * pins the rule itself, and in particular the decision taken for the
 * four-actor model: a department coordinator's INSTITUTION grant is never
 * honoured, whatever else they hold.
 */
class AccessScopeResolverTest {

    private final StaffScopeRepository grants = mock(StaffScopeRepository.class);
    private final AccessScopeResolver resolver = new AccessScopeResolver(grants);

    private static User user(UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "@example.com");
        user.setRole(role);
        return user;
    }

    private static Department department() {
        Department department = mock(Department.class);
        when(department.getId()).thenReturn(UUID.randomUUID());
        return department;
    }

    private static Batch batch() {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        return batch;
    }

    private static StaffScope grant(User holder, ScopeType type, Department department, Batch batch) {
        StaffScope scope = new StaffScope();
        scope.setUser(holder);
        scope.setScopeType(type);
        scope.setDepartment(department);
        scope.setBatch(batch);
        return scope;
    }

    private AccessScope resolve(User user, StaffScope... held) {
        when(grants.findByUserId(user.getId())).thenReturn(List.of(held));
        return resolver.resolve(new AuthenticatedUser(user));
    }

    @Nested
    @DisplayName("roles that do not read the scope table")
    class ByRole {

        @Test
        @DisplayName("the portal administrator has no institution and sees no student")
        void portalAdministrator() {
            AccessScope scope = resolve(user(UserRole.PORTAL_ADMIN));

            assertThat(scope.institutionId()).isNull();
            assertThat(scope.seesWholeInstitution()).isFalse();
            assertThat(scope.isEmpty()).isTrue();
            assertThat(scope.covers(UUID.randomUUID(), UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("a student sees nobody through a scope")
        void student() {
            AccessScope scope = resolve(user(UserRole.STUDENT));

            assertThat(scope.seesWholeInstitution()).isFalse();
            assertThat(scope.covers(UUID.randomUUID(), UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("a placement coordinator covers the whole institution without consulting any grant")
        void placementCoordinator() {
            User coordinator = user(UserRole.PLACEMENT_COORDINATOR);

            AccessScope scope = resolver.resolve(new AuthenticatedUser(coordinator));

            assertThat(scope.seesWholeInstitution()).isTrue();
            assertThat(scope.covers(null, null))
                    .describedAs("even a student nobody has enrolled yet")
                    .isTrue();
            verify(grants, never()).findByUserId(any());
        }
    }

    @Nested
    @DisplayName("a department coordinator")
    class DepartmentCoordinator {

        @Test
        @DisplayName("sees the department granted, and no other")
        void departmentGrant() {
            User coordinator = user(UserRole.DEPARTMENT_COORDINATOR);
            Department cse = department();

            AccessScope scope = resolve(coordinator, grant(coordinator, ScopeType.DEPARTMENT, cse, null));

            assertThat(scope.covers(cse.getId(), null)).isTrue();
            assertThat(scope.covers(UUID.randomUUID(), null)).isFalse();
            assertThat(scope.seesWholeInstitution()).isFalse();
        }

        @Test
        @DisplayName("is not given a scope by a batch grant alone: a batch narrows a department")
        void batchGrantAloneSeesNobody() {
            // This used to assert that a batch grant opened that batch across
            // every department. The locked rule is that a department coordinator
            // is department-scoped and a batch only narrows that.
            User coordinator = user(UserRole.DEPARTMENT_COORDINATOR);
            Batch classOf2027 = batch();

            AccessScope scope = resolve(coordinator, grant(coordinator, ScopeType.BATCH, null, classOf2027));

            assertThat(scope.isEmpty()).isTrue();
            assertThat(scope.seesWholeInstitution()).isFalse();
            assertThat(scope.covers(UUID.randomUUID(), classOf2027.getId())).isFalse();
            assertThat(scope.covers(null, classOf2027.getId())).isFalse();
        }

        @Test
        @DisplayName("is narrowed to a granted batch inside their department, and never past it")
        void batchNarrowsTheDepartment() {
            User coordinator = user(UserRole.DEPARTMENT_COORDINATOR);
            Department cse = department();
            Batch classOf2027 = batch();

            AccessScope scope = resolve(coordinator,
                    grant(coordinator, ScopeType.DEPARTMENT, cse, null),
                    grant(coordinator, ScopeType.BATCH, null, classOf2027));

            assertThat(scope.isEmpty()).isFalse();
            assertThat(scope.hasBatchRestriction()).isTrue();
            assertThat(scope.covers(cse.getId(), classOf2027.getId())).isTrue();
            assertThat(scope.covers(cse.getId(), UUID.randomUUID()))
                    .describedAs("the department does not reach past the batch").isFalse();
            assertThat(scope.covers(cse.getId(), null))
                    .describedAs("a student with no batch is outside a batch-narrowed scope").isFalse();
            assertThat(scope.covers(UUID.randomUUID(), classOf2027.getId()))
                    .describedAs("the batch does not reach into another department").isFalse();
        }

        @Test
        @DisplayName("is not widened by an INSTITUTION grant; it is ignored, not obeyed")
        void institutionGrantIsNotHonoured() {
            User coordinator = user(UserRole.DEPARTMENT_COORDINATOR);

            AccessScope scope = resolve(coordinator, grant(coordinator, ScopeType.INSTITUTION, null, null));

            assertThat(scope.seesWholeInstitution()).isFalse();
            assertThat(scope.isEmpty())
                    .describedAs("an institution grant alone leaves the coordinator seeing nobody")
                    .isTrue();
            assertThat(scope.covers(UUID.randomUUID(), UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("keeps exactly their department when an INSTITUTION grant sits beside it")
        void institutionGrantBesideADepartment() {
            User coordinator = user(UserRole.DEPARTMENT_COORDINATOR);
            Department cse = department();

            AccessScope scope = resolve(coordinator,
                    grant(coordinator, ScopeType.INSTITUTION, null, null),
                    grant(coordinator, ScopeType.DEPARTMENT, cse, null));

            assertThat(scope.seesWholeInstitution()).isFalse();
            assertThat(scope.departmentIds()).containsExactly(cse.getId());
            assertThat(scope.covers(UUID.randomUUID(), null)).isFalse();
        }

        @Test
        @DisplayName("with no grant at all, sees nobody")
        void noGrantsSeesNobody() {
            AccessScope scope = resolve(user(UserRole.DEPARTMENT_COORDINATOR));

            assertThat(scope.isEmpty()).isTrue();
            assertThat(scope.covers(null, null)).isFalse();
        }
    }
}
