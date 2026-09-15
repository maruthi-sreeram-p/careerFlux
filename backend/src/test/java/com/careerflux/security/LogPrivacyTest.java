package com.careerflux.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.careerflux.bootstrap.AdminAccountSeeder;
import com.careerflux.candidate.service.ResumeTextExtractor;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ForbiddenException;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScopeResolver;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * What the application log may say about a person (Phase 2A, F13).
 *
 * <p>The log is read by whoever operates the platform, so it identifies people
 * by account id and never by address, and never repeats a resume's filename —
 * which is usually the student's name, and often their phone number.
 *
 * <p>Each test raises its logger to the level the line is written at, so an
 * absent line is a pass for the right reason rather than because nothing was
 * logged at all.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogPrivacyTest {

    private static void logAt(Class<?> type, Level level) {
        ((Logger) LoggerFactory.getLogger(type)).setLevel(level);
    }

    @Test
    @DisplayName("a refused permission is logged by account id, not by address")
    void denialsNameTheAccountNotTheAddress(CapturedOutput output) {
        logAt(AccessGuard.class, Level.WARN);
        User student = new User();
        student.setId(UUID.randomUUID());
        student.setEmail("priya.private@example.com");
        student.setRole(UserRole.STUDENT);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.require()).thenReturn(new AuthenticatedUser(student));
        AccessGuard guard = new AccessGuard(currentUser, mock(AccessScopeResolver.class));

        assertThatThrownBy(() -> guard.requirePermission(Permission.STAFF_MANAGE))
                .isInstanceOf(ForbiddenException.class);

        assertThat(output).contains("Denied STAFF_MANAGE to user " + student.getId())
                .doesNotContain("priya.private@example.com");
    }

    @Test
    @DisplayName("an unreadable resume is logged without its filename or the parser's words")
    void unreadableResumesAreNotNamed(CapturedOutput output) {
        logAt(ResumeTextExtractor.class, Level.WARN);
        byte[] notReallyAPdf = "%PDF-1.7 this is not a document".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ResumeTextExtractor()
                .extract(notReallyAPdf, "Priya_Sharma_9876543210_resume.pdf"))
                .isInstanceOf(BadRequestException.class);

        assertThat(output).contains("Could not read an uploaded resume")
                .doesNotContain("Priya_Sharma").doesNotContain("9876543210");
    }

    @Test
    @DisplayName("creating the administrator is logged by account id, not by address")
    void theAdministratorIsNamedById(CapturedOutput output) {
        logAt(AdminAccountSeeder.class, Level.INFO);
        UserRepository users = mock(UserRepository.class);
        UUID createdId = UUID.randomUUID();
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(createdId);
            return saved;
        });
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");

        new AdminAccountSeeder(users, encoder, "operator.private@careerflux.local",
                "a-long-enough-operator-password", "Operator").run(null);

        assertThat(output).contains("Created the administrator account " + createdId)
                .doesNotContain("operator.private@careerflux.local");
    }
}
