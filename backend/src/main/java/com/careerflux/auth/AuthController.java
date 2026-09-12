package com.careerflux.auth;

import java.util.Map;

import com.careerflux.auth.AuthDtos.AuthResponse;
import com.careerflux.auth.AuthDtos.ChangePasswordRequest;
import com.careerflux.auth.AuthDtos.ForgotPasswordRequest;
import com.careerflux.auth.AuthDtos.LoginRequest;
import com.careerflux.auth.AuthDtos.RefreshRequest;
import com.careerflux.auth.AuthDtos.RegisterRequest;
import com.careerflux.auth.AuthDtos.ResetPasswordRequest;
import com.careerflux.auth.AuthDtos.SessionUser;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.config.DeploymentProfiles;
import com.careerflux.security.CurrentUser;
import com.careerflux.security.ratelimit.RateLimiter;
import com.careerflux.user.UserRepository;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final CandidateProfileService candidateProfileService;
    private final RateLimiter rateLimiter;
    private final boolean discloseResetToken;

    public AuthController(AuthService authService,
                          CurrentUser currentUser,
                          UserRepository userRepository,
                          CandidateProfileService candidateProfileService,
                          RateLimiter rateLimiter,
                          Environment environment) {
        this.authService = authService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.candidateProfileService = candidateProfileService;
        this.rateLimiter = rateLimiter;
        this.discloseResetToken = DeploymentProfiles.mayDiscloseResetTokens(environment);
    }

    @PostMapping("/register")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /**
     * Signing in, charged against a ceiling before the password is checked.
     *
     * <p>The charge happens here rather than in a filter because the key needs
     * the address being tried, which is in the request body; see
     * {@link com.careerflux.security.ratelimit.RateLimitFilter} for why that is
     * not worth buffering every request for. It still runs ahead of everything
     * that matters: no account lookup, no BCrypt, and no token involved.
     *
     * <p>The client address comes from the socket, never from
     * {@code X-Forwarded-For}. That header is written by whoever sent the
     * request unless a proxy is trusted to overwrite it, and honouring it here
     * would let an attacker mint a fresh allowance per attempt by changing one
     * header. If a reverse proxy is ever put in front of this application, the
     * proxy must strip the client-supplied header and Spring must be configured
     * to trust it, in that order.
     */
    @PostMapping("/login")
    @SecurityRequirements
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(http.getRemoteAddr(), request.email());
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * Always returns 202 regardless of whether the address is registered, so the
     * endpoint cannot be used to discover accounts.
     *
     * <p>There is no mail transport yet, so under the {@code dev} and
     * {@code demo} profiles the token is echoed back rather than pretending an
     * email was delivered. Never under {@code prod}, and never under any other
     * profile. An unnamed profile used to count as {@code dev} here, which put
     * the token in the response of any deployment that forgot to name one.
     */
    @PostMapping("/forgot-password")
    @SecurityRequirements
    public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        var token = authService.beginPasswordReset(request.email());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("message", "If that address has an account, a reset link is on its way.");
        if (discloseResetToken && token.isPresent()) {
            body.put("devResetToken", token.get());
            body.put("devNotice", "Returned only under the dev and demo profiles, because email delivery "
                    + "is not wired up yet.");
        }
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/reset-password")
    @SecurityRequirements
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.completePasswordReset(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentUser.requireId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public SessionUser me() {
        var principal = currentUser.require();
        var user = userRepository.findById(principal.getUserId()).orElseThrow();
        var profile = candidateProfileService.findByUserId(user.getId()).orElse(null);
        return authService.toSessionUser(user, profile);
    }
}
