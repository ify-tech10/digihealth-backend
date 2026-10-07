package com.digihealth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /auth/*. Field names match the frontend. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Same rule as the frontend: 8+ characters with a letter and a number. 72 is BCrypt's limit. */
    public static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    public static final String PASSWORD_MESSAGE = "Use 8 to 72 characters, with at least one letter and one number.";

    public record LoginRequest(
            @NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email address.") String email,
            @NotBlank(message = "Enter your password.") @Size(max = 200) String password,
            Boolean remember) {

        public boolean rememberMe() {
            return Boolean.TRUE.equals(remember);
        }
    }

    public record LoginResponse(String accessToken, String role, String name, String email, Long id) {
    }

    public record AccessTokenResponse(String accessToken) {
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email address.") String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "This link is incomplete.") @Size(max = 200) String token,
            @NotBlank(message = "Enter a new password.") @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password.") @Size(max = 200) String currentPassword,
            @NotBlank(message = "Enter a new password.") @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record MessageResponse(String message) {
    }
}
