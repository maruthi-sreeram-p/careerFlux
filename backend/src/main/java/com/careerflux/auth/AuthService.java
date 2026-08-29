package com.careerflux.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import com.careerflux.audit.AuditService;
import com.careerflux.auth.AuthDtos.AuthResponse;
import com.careerflux.auth.AuthDtos.LoginRequest;
import com.careerflux.auth.AuthDtos.RegisterRequest;
import com.careerflux.auth.AuthDtos.SessionUser;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.ForbiddenException;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.service.EnrolmentService;
import com.careerflux.security.JwtService;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RESET_TOKEN_TTL_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CandidateProfileService candidateProfileService;
    private final EnrolmentService enrolmentService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       CandidateProfileService candidateProfileService,
                       EnrolmentService enrolmentService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.candidateProfileService = candidateProfileService;
        this.enrolmentService = enrolmentService;
        this.auditService = auditService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with that email already exists.");
        }

        // Which college this person belongs to is decided before the account
        // exists, not after. Self-registration into an institution you cannot
        // prove membership of is the whole risk of a multi-tenant product.
        Institution institution =
                enrolmentService.resolveForRegistration(email, request.institutionCode());

        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        userRepository.save(user);

        CandidateProfile profile = candidateProfileService.createForUser(user);
        auditService.recordSystem(email, "USER_REGISTERED", "User", user.getId(),
                "Self-service registration into " + institution.getSlug());
        log.info("Registered student {} into institution {}", user.getId(), institution.getSlug());
        return issueTokens(user, profile);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ForbiddenException("Email or password is incorrect."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Deliberately the same message as an unknown email: do not reveal which accounts exist.
            throw new ForbiddenException("Email or password is incorrect.");
        }
        if (!user.isActive()) {
            throw new ForbiddenException("This account has been disabled.");
        }

        user.setLastLoginAt(Instant.now());
        CandidateProfile profile = candidateProfileService.findByUserId(user.getId()).orElse(null);
        return issueTokens(user, profile);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        JwtService.ParsedToken parsed = jwtService.parse(refreshToken, true);
        if (parsed == null) {
            throw new ForbiddenException("That session has expired. Sign in again.");
        }
        User user = userRepository.findById(parsed.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new ForbiddenException("That session is no longer valid."));
        CandidateProfile profile = candidateProfileService.findByUserId(user.getId()).orElse(null);
        return issueTokens(user, profile);
    }

    /**
     * Starts a password reset. Always succeeds from the caller's point of view so
     * the endpoint cannot be used to enumerate registered addresses. There is no
     * mail transport wired up yet, so the token is returned to the operator
     * through the log rather than pretending an email was sent.
     */
    @Transactional
    public Optional<String> beginPasswordReset(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Optional<User> maybeUser = userRepository.findByEmailIgnoreCase(email);
        if (maybeUser.isEmpty()) {
            return Optional.empty();
        }
        User user = maybeUser.get();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        user.setPasswordResetToken(token);
        user.setPasswordResetExpiresAt(Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
        auditService.recordSystem(email, "PASSWORD_RESET_REQUESTED", "User", user.getId(), null);
        log.info("Password reset token issued for {} (valid {} minutes)", email, RESET_TOKEN_TTL_MINUTES);
        return Optional.of(token);
    }

    @Transactional
    public void completePasswordReset(String token, String newPassword) {
        User user = userRepository.findByPasswordResetToken(token)
                .orElseThrow(() -> new BadRequestException("That reset link is not valid."));
        if (user.getPasswordResetExpiresAt() == null || user.getPasswordResetExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("That reset link has expired. Request a new one.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        auditService.recordSystem(user.getEmail(), "PASSWORD_RESET_COMPLETED", "User", user.getId(), null);
    }

    @Transactional
    public void changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenException("Sign in to continue."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("Your current password is incorrect.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        auditService.record("PASSWORD_CHANGED", "User", user.getId(), null);
    }

    private AuthResponse issueTokens(User user, CandidateProfile profile) {
        return new AuthResponse(
                jwtService.issueAccessToken(user),
                jwtService.issueRefreshToken(user),
                jwtService.accessTokenSeconds(),
                toSessionUser(user, profile));
    }

    public SessionUser toSessionUser(User user, CandidateProfile profile) {
        // Permissions travel with the session so the frontend can hide what the
        // caller cannot do. The backend never trusts this; it is presentation.
        java.util.Set<String> permissions = user.getRole().getPermissions().stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        return new SessionUser(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                permissions,
                user.getInstitutionId(),
                user.getInstitution() == null ? null : user.getInstitution().getName(),
                profile == null ? null : profile.getId(),
                profile == null ? null : profile.getOnboardingStage().name());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
