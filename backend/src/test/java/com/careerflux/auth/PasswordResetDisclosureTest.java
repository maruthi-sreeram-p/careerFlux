package com.careerflux.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.careerflux.audit.AuditService;
import com.careerflux.auth.AuthDtos.ForgotPasswordRequest;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.institution.service.EnrolmentService;
import com.careerflux.security.CurrentUser;
import com.careerflux.security.JwtService;
import com.careerflux.security.ratelimit.RateLimiter;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Where a password-reset token may appear, and where it never may.
 *
 * <p>With no email delivery yet, the dev and demo profiles hand the token back
 * in the response so a reset can be finished at all. Anywhere else, and above
 * all under prod, the token must reach nobody but the account holder, so it
 * appears in neither the response nor the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetDisclosureTest {

    /** Made up for this test; it resets nothing. */
    private static final String TOKEN = "not-a-real-reset-token-0123456789abcdef";

    private static Map<String, Object> forgotPasswordUnder(String profiles) {
        AuthService authService = mock(AuthService.class);
        when(authService.beginPasswordReset(anyString())).thenReturn(Optional.of(TOKEN));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles.split(","));

        AuthController controller = new AuthController(authService, mock(CurrentUser.class),
                mock(UserRepository.class), mock(CandidateProfileService.class), mock(RateLimiter.class),
                environment);
        return controller.forgotPassword(new ForgotPasswordRequest("student@example.com")).getBody();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"prod", "prod,postgres", "prod,demo", "prod,dev", "postgres", "test", "kafka"})
    @DisplayName("the response never carries the token outside dev and demo")
    void tokenWithheld(String profiles) {
        Map<String, Object> body = forgotPasswordUnder(profiles);

        assertThat(body).containsEntry("status", "accepted");
        assertThat(body).doesNotContainKey("devResetToken");
        assertThat(body.values()).doesNotContain(TOKEN);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "demo", "postgres,demo"})
    @DisplayName("dev and demo still get the token, since no email can carry it yet")
    void tokenReturnedForDevelopmentAndDemos(String profiles) {
        assertThat(forgotPasswordUnder(profiles)).containsEntry("devResetToken", TOKEN);
    }

    @Test
    @DisplayName("issuing a token never writes it to the log")
    void tokenNeverLogged(CapturedOutput output) {
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setEmail("student@example.com");
        when(users.findByEmailIgnoreCase("student@example.com")).thenReturn(Optional.of(user));
        AuthService service = new AuthService(users, mock(PasswordEncoder.class), mock(JwtService.class),
                mock(CandidateProfileService.class), mock(EnrolmentService.class), mock(AuditService.class));

        String issued = service.beginPasswordReset("student@example.com").orElseThrow();

        // Proves the log was captured at all, so the absence below means something.
        assertThat(output).contains("Password reset token issued");
        assertThat(output).doesNotContain(issued);
    }
}
