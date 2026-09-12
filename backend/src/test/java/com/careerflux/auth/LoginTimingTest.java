package com.careerflux.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.audit.AuditService;
import com.careerflux.auth.AuthDtos.LoginRequest;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.error.ForbiddenException;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.service.EnrolmentService;
import com.careerflux.security.JwtService;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Signing in must not tell a stranger which addresses have accounts.
 *
 * <p>The message was always the same for an unknown address and a wrong
 * password. The time was not: an unknown address returned before any hashing,
 * about a quarter-second faster than a real one. Timing cannot be asserted
 * reliably in a unit test, so this asserts its cause instead — that exactly one
 * BCrypt comparison happens on every path a stranger can reach.
 */
class LoginTimingTest {

    private static final String BAD_CREDENTIALS = "Email or password is incorrect.";
    private static final String EQUALISER = "$2a$12$equaliserHashForUnknownAddressesOnly";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthService service = new AuthService(users, encoder, mock(JwtService.class),
            mock(CandidateProfileService.class), mock(EnrolmentService.class), mock(AuditService.class));

    private User account(UserStatus status, InstitutionStatus college) {
        Institution institution = new Institution();
        institution.setStatus(college);
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("real@example.com");
        user.setPasswordHash("$2a$12$theRealAccountsHash");
        user.setRole(UserRole.STUDENT);
        user.setStatus(status);
        user.setInstitution(institution);
        return user;
    }

    @Test
    @DisplayName("an unknown address still costs one BCrypt comparison, and gets the ordinary answer")
    void unknownAddressIsHashedToo() {
        when(users.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn(EQUALISER);

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "Guess12345!")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BAD_CREDENTIALS);
        verify(encoder).matches("Guess12345!", EQUALISER);
    }

    @Test
    @DisplayName("the stand-in hash is built once and then reused")
    void equaliserIsBuiltOnce() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn(EQUALISER);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "Guess12345!")))
                    .isInstanceOf(ForbiddenException.class);
        }
        verify(encoder, times(1)).encode(anyString());
        verify(encoder, times(3)).matches("Guess12345!", EQUALISER);
    }

    @Test
    @DisplayName("a wrong password for a real account gets exactly the same answer")
    void wrongPasswordLooksTheSame() {
        User user = account(UserStatus.ACTIVE, InstitutionStatus.ACTIVE);
        when(users.findByEmailIgnoreCase("real@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("real@example.com", "Guess12345!")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BAD_CREDENTIALS);
        verify(encoder).matches("Guess12345!", user.getPasswordHash());
    }

    @Test
    @DisplayName("a disabled account is revealed only to somebody who has just proved its password")
    void disabledIsNotRevealedToAGuesser() {
        User user = account(UserStatus.DISABLED, InstitutionStatus.ACTIVE);
        when(users.findByEmailIgnoreCase("real@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("real@example.com", "Guess12345!")))
                .hasMessage(BAD_CREDENTIALS);
    }

    @Test
    @DisplayName("a suspended college is revealed only to somebody who has just proved the password")
    void suspensionIsNotRevealedToAGuesser() {
        User user = account(UserStatus.ACTIVE, InstitutionStatus.SUSPENDED);
        when(users.findByEmailIgnoreCase("real@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("real@example.com", "Guess12345!")))
                .hasMessage(BAD_CREDENTIALS);

        when(encoder.matches("Correct12345!", user.getPasswordHash())).thenReturn(true);
        assertThatThrownBy(() -> service.login(new LoginRequest("real@example.com", "Correct12345!")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("suspended");
    }
}
