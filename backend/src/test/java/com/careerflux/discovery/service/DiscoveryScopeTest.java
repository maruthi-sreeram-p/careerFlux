package com.careerflux.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.institution.domain.Department;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.security.access.AccessScope;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The discovery scope fails closed (Phase 2A, F9).
 *
 * <p>It used to ignore batch grants and read an empty department set as "every
 * department", so a coordinator holding only a batch grant was handed the whole
 * college. "All departments" is now only ever the placement coordinator's
 * answer; a department coordinator gets their departments, narrowed to their
 * batches, or nobody.
 */
class DiscoveryScopeTest {

    private final UserRepository users = mock(UserRepository.class);
    private final DiscoveryScope discoveryScope = new DiscoveryScope(users);

    private final UUID institution = UUID.randomUUID();
    private final UUID cse = UUID.randomUUID();
    private final UUID mech = UUID.randomUUID();
    private final UUID classOf2026 = UUID.randomUUID();

    private static Department department(UUID id) {
        Department department = mock(Department.class);
        when(department.getId()).thenReturn(id);
        return department;
    }

    private static CompanyRequirement requirement(UUID... targets) {
        CompanyRequirement requirement = new CompanyRequirement();
        Set<Department> departments = new LinkedHashSet<>();
        for (UUID target : targets) {
            departments.add(department(target));
        }
        requirement.setDepartments(departments);
        return requirement;
    }

    private AccessScope departmentCoordinator(Set<UUID> departments, Set<UUID> batches) {
        return new AccessScope(UUID.randomUUID(), institution, UserRole.DEPARTMENT_COORDINATOR, false,
                departments, batches);
    }

    @Test
    @DisplayName("a coordinator holding only a batch grant is given nobody, not the college")
    void batchOnlyIsNobody() {
        AccessScope batchOnly = departmentCoordinator(Set.of(), Set.of(classOf2026));

        DiscoveryScope.Criteria criteria = discoveryScope.criteriaFor(requirement(), institution, batchOnly);

        assertThat(criteria.empty()).isTrue();
        assertThat(criteria.allDepartments()).isFalse();
        assertThat(discoveryScope.studentIds(requirement(), institution, batchOnly)).isEmpty();
        assertThat(discoveryScope.covers(requirement(), institution, batchOnly, UUID.randomUUID())).isFalse();
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("a coordinator granted nothing is given nobody")
    void noGrantIsNobody() {
        AccessScope nothing = departmentCoordinator(Set.of(), Set.of());

        assertThat(discoveryScope.criteriaFor(requirement(), institution, nothing).empty()).isTrue();
        assertThat(discoveryScope.criteriaFor(requirement(cse), institution, nothing).empty()).isTrue();
    }

    @Test
    @DisplayName("a department coordinator is never given every department")
    void departmentCoordinatorIsNeverAllDepartments() {
        AccessScope cseOnly = departmentCoordinator(Set.of(cse), Set.of());

        DiscoveryScope.Criteria criteria = discoveryScope.criteriaFor(requirement(), institution, cseOnly);

        assertThat(criteria.empty()).isFalse();
        assertThat(criteria.allDepartments()).isFalse();
        assertThat(criteria.departmentIds()).containsExactly(cse);
        assertThat(criteria.anyGrantedBatch()).isTrue();
    }

    @Test
    @DisplayName("the company's departments are intersected with the coordinator's, never unioned")
    void targetsAreIntersected() {
        AccessScope cseOnly = departmentCoordinator(Set.of(cse), Set.of());

        assertThat(discoveryScope.criteriaFor(requirement(cse, mech), institution, cseOnly).departmentIds())
                .containsExactly(cse);
        assertThat(discoveryScope.criteriaFor(requirement(mech), institution, cseOnly).empty()).isTrue();
    }

    @Test
    @DisplayName("a batch grant narrows the coordinator's departments to that batch")
    void batchGrantNarrows() {
        AccessScope narrowed = departmentCoordinator(Set.of(cse), Set.of(classOf2026));

        DiscoveryScope.Criteria criteria = discoveryScope.criteriaFor(requirement(), institution, narrowed);

        assertThat(criteria.allDepartments()).isFalse();
        assertThat(criteria.departmentIds()).containsExactly(cse);
        assertThat(criteria.anyGrantedBatch()).isFalse();
        assertThat(criteria.grantedBatchIds()).containsExactly(classOf2026);
    }

    @Test
    @DisplayName("only the placement coordinator is given the whole college")
    void placementCoordinatorSeesTheCollege() {
        AccessScope wholeCollege = new AccessScope(UUID.randomUUID(), institution,
                UserRole.PLACEMENT_COORDINATOR, true, Set.of(), Set.of());

        DiscoveryScope.Criteria criteria = discoveryScope.criteriaFor(requirement(), institution, wholeCollege);

        assertThat(criteria.allDepartments()).isTrue();
        assertThat(criteria.anyGrantedBatch()).isTrue();
        assertThat(discoveryScope.criteriaFor(requirement(mech), institution, wholeCollege).departmentIds())
                .containsExactly(mech);
    }

    @Test
    @DisplayName("what the scope decides is what the query is asked")
    void theQueryReceivesTheNarrowing() {
        AccessScope narrowed = departmentCoordinator(Set.of(cse), Set.of(classOf2026));
        when(users.findStudentsForDiscovery(any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()))
                .thenReturn(List.of());

        discoveryScope.studentIds(requirement(), institution, narrowed);

        org.mockito.Mockito.verify(users).findStudentsForDiscovery(institution, false, Set.of(cse),
                true, null, false, Set.of(classOf2026));
    }
}
