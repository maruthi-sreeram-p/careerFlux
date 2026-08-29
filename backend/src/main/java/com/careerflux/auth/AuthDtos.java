package com.careerflux.auth;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response payloads for the authentication endpoints, grouped in one
 * place because they are only ever used together.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * {@code institutionCode} is optional: a student registering from a college
     * email address is recognised by domain. It exists for everyone else.
     */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 10, max = 128, message = "Use at least 10 characters") String password,
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 40) String institutionCode) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 128, message = "Use at least 10 characters") String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 128, message = "Use at least 10 characters") String newPassword) {
    }

    /** Issued on register, login and refresh. */
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            SessionUser user) {
    }

    public record SessionUser(
            UUID id,
            String email,
            String fullName,
            String role,
            java.util.Set<String> permissions,
            UUID institutionId,
            String institutionName,
            UUID candidateId,
            String onboardingStage) {
    }
}
