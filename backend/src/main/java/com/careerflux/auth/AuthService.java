package com.careerflux.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** One message for an unknown address and a wrong password, so neither reveals the other. */
    private static final String BAD_CREDENTIALS = "Email or password is incorrect.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CandidateProfileService candidateProfileService;
    private final EnrolmentService enrolmentService;
    private final AuditService auditService;

    /**
     * A hash of a value nobody knows, checked against when the address is not
     * registered. Built on first use rather than at startup so the cost of one
     * BCrypt encoding is not paid by every context that never signs anybody in.
     */
    private volatile String timingEqualiserHash;

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
        auditService.recordAsAccount(user, "USER_REGISTERED", "User", user.getId(),
                "Self-service registration into " + institution.getSlug());
        log.info("Registered student {} into institution {}", user.getId(), institution.getSlug());
        return issueTokens(user, profile);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

        if (user == null) {
            // The same BCrypt work a real account costs. Returning before any
            // hashing made an unknown address answer a quarter-second faster than
            // a known one, which told anybody with a stopwatch who had an account.
            passwordEncoder.matches(request.password(), timingEqualiserHash());
            throw new ForbiddenException(BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ForbiddenException(BAD_CREDENTIALS);
        }
        // Only somebody who has just proved the password learns why they cannot
        // come in, so neither message below reveals anything to a guesser.
        if (!user.isActive()) {
            throw new ForbiddenException("This account has been disabled.");
        }
        if (!user.canHoldSession()) {
            throw new ForbiddenException(
                    "Your college's access to CareerFlux is suspended. Contact your placement office.");
        }

        user.setLastLoginAt(Instant.now());
        CandidateProfile profile = candidateProfileService.findByUserId(user.getId()).orElse(null);
        return issueTokens(user, profile);
    }

    /**
     * Swaps a refresh token for a fresh pair.
     *
     * <p>Held to the same conditions as a request: the account must still be
     * allowed a session, and the refresh token must postdate the session
     * watermark. Otherwise a refresh token stolen before a password reset would
     * simply mint new access tokens after it.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        JwtService.ParsedToken parsed = jwtService.parse(refreshToken, true);
        if (parsed == null) {
            throw new ForbiddenException("That session has expired. Sign in again.");
        }
        User user = userRepository.findById(parsed.userId())
                .filter(User::canHoldSession)
                .filter(candidate -> candidate.acceptsSessionIssuedAt(parsed.issuedAt()))
                .orElseThrow(() -> new ForbiddenException("That session is no longer valid."));
        CandidateProfile profile = candidateProfileService.findByUserId(user.getId()).orElse(null);
        return issueTokens(user, profile);
    }

    /**
     * Starts a password reset. Always succeeds from the caller's point of view so
     * the endpoint cannot be used to enumerate registered addresses.
     *
     * <p>Only a SHA-256 digest of the token is stored; the raw value is returned
     * to the caller and exists nowhere else. There is no mail transport wired up
     * yet, so the token reaches a person only under the dev and demo profiles
     * (see {@code AuthController}). It is never written to the log, and neither is
     * the address it was issued for.
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
        user.setPasswordResetToken(digest(token));
        user.setPasswordResetExpiresAt(Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
        // Anybody may ask for a reset for any address, so the event belongs to
        // the account's college but is not attributed to the account holder.
        auditService.recordAboutAccount(user, "PASSWORD_RESET_REQUESTED", "User", user.getId(), null);
        log.info("Password reset token issued for user {} (valid {} minutes)", user.getId(), RESET_TOKEN_TTL_MINUTES);
        return Optional.of(token);
    }

    /**
     * Completes a reset and ends every session the account had.
     *
     * <p>Revoking is the point of a reset as much as the new password is: the
     * person resetting may be doing it because somebody else is signed in as them.
     */
    @Transactional
    public void completePasswordReset(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("That reset link is not valid.");
        }
        User user = userRepository.findByPasswordResetToken(digest(token.strip()))
                .orElseThrow(() -> new BadRequestException("That reset link is not valid."));
        if (user.getPasswordResetExpiresAt() == null || user.getPasswordResetExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("That reset link has expired. Request a new one.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        user.revokeSessions();
        // Whoever held the link acted as the account, which is what the link is for.
        auditService.recordAsAccount(user, "PASSWORD_RESET_COMPLETED", "User", user.getId(), null);
    }

    /**
     * Changes a password and ends every session the account had, this one
     * included. The person is asked to sign in again with the new password,
     * which is also what tells anybody else holding a session that it is over.
     */
    @Transactional
    public void changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenException("Sign in to continue."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("Your current password is incorrect.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.revokeSessions();
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

    private String timingEqualiserHash() {
        String hash = timingEqualiserHash;
        if (hash == null) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            // A benign race: two first callers may each encode once. Both results
            // are equally unguessable and equally expensive to check against.
            hash = passwordEncoder.encode(HexFormat.of().formatHex(bytes));
            timingEqualiserHash = hash;
        }
        return hash;
    }

    /** What is stored for a reset token: its SHA-256, hex-encoded. */
    static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
