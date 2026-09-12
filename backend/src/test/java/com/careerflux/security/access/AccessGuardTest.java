package com.careerflux.security.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.careerflux.common.error.NotFoundException;
import com.careerflux.security.AuthenticatedUser;
import com.careerflux.security.CurrentUser;
import com.careerflux.user.User;
import com.careerflux.user.UserRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The institution check on its own.
 *
 * <p>Through the API this branch cannot be reached by a portal administrator:
 * the only caller asks for a student-read permission first, which that role does
 * not hold. That is exactly why the old pass-through was dangerous and why it is
 * tested here directly — a regression would be invisible to every integration
 * test until something else changed.
 */
class AccessGuardTest {

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AccessScopeResolver resolver = mock(AccessScopeResolver.class);
    private final AccessGuard guard = new AccessGuard(currentUser, resolver);

    private void signedInAs(AccessScope scope) {
        User user = new User();
        user.setId(scope.userId());
        user.setEmail("someone@example.com");
        user.setRole(scope.role());
        AuthenticatedUser principal = new AuthenticatedUser(user);
        when(currentUser.require()).thenReturn(principal);
        when(currentUser.requireId()).thenReturn(scope.userId());
        when(resolver.resolve(any())).thenReturn(scope);
    }

    private static AccessScope placementCoordinatorOf(UUID institution) {
        return new AccessScope(UUID.randomUUID(), institution, UserRole.PLACEMENT_COORDINATOR,
                true, Set.of(), Set.of());
    }

    @Test
    @DisplayName("a portal administrator is not waved through: no college's record belongs to them")
    void portalAdministratorIsNotAnException() {
        signedInAs(AccessScope.platform(UUID.randomUUID()));

        assertThatThrownBy(() -> guard.requireSameInstitution(UUID.randomUUID(), "Candidate", "c-1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a record in the caller's own college passes")
    void sameInstitutionPasses() {
        UUID college = UUID.randomUUID();
        signedInAs(placementCoordinatorOf(college));

        assertThatCode(() -> guard.requireSameInstitution(college, "Candidate", "c-1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a record in another college is not found, not forbidden")
    void anotherInstitutionIsNotFound() {
        signedInAs(placementCoordinatorOf(UUID.randomUUID()));

        assertThatThrownBy(() -> guard.requireSameInstitution(UUID.randomUUID(), "Candidate", "c-1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a record that names no college belongs to nobody's")
    void recordWithoutAnInstitutionIsNotFound() {
        signedInAs(placementCoordinatorOf(UUID.randomUUID()));

        assertThatThrownBy(() -> guard.requireSameInstitution(null, "Candidate", "c-1"))
                .isInstanceOf(NotFoundException.class);
    }
}
