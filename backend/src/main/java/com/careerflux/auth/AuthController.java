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
import com.careerflux.security.CurrentUser;
import com.careerflux.user.UserRepository;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

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
    private final boolean devProfile;

    public AuthController(AuthService authService,
                          CurrentUser currentUser,
                          UserRepository userRepository,
                          CandidateProfileService candidateProfileService,
                          Environment environment) {
        this.authService = authService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.candidateProfileService = candidateProfileService;
        this.devProfile = environment.matchesProfiles("dev", "demo");
    }

    @PostMapping("/register")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @SecurityRequirements
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * Always returns 202 regardless of whether the address is registered, so the
     * endpoint cannot be used to discover accounts. Outside production profiles
     * the token is echoed back, because there is no mail transport yet and
     * pretending an email was delivered would be a lie.
     */
    @PostMapping("/forgot-password")
    @SecurityRequirements
    public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        var token = authService.beginPasswordReset(request.email());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("message", "If that address has an account, a reset link is on its way.");
        if (devProfile && token.isPresent()) {
            body.put("devResetToken", token.get());
            body.put("devNotice", "Returned only outside production because email delivery is not wired up yet.");
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
