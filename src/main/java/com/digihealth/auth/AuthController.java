package com.digihealth.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.auth.dto.AuthDtos.AccessTokenResponse;
import com.digihealth.auth.dto.AuthDtos.ChangePasswordRequest;
import com.digihealth.auth.dto.AuthDtos.ForgotPasswordRequest;
import com.digihealth.auth.dto.AuthDtos.LoginRequest;
import com.digihealth.auth.dto.AuthDtos.LoginResponse;
import com.digihealth.auth.dto.AuthDtos.MessageResponse;
import com.digihealth.auth.dto.AuthDtos.ResetPasswordRequest;
import com.digihealth.common.ApiException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    /* The frontend adds this header to /auth/refresh and /auth/logout. A plain
       cross-site form can't, so it blocks CSRF on the cookie endpoints. */
    private static final String AJAX_HEADER = "X-Requested-With";

    private final AuthService auth;
    private final RefreshCookie cookie;

    public AuthController(AuthService auth, RefreshCookie cookie) {
        this.auth = auth;
        this.cookie = cookie;
    }

    /** POST /auth/login { email, password, remember } */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AuthService.LoginResult result = auth.login(body);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.issue(result.refreshToken(), result.remember(), request).toString())
            .body(result.body());
    }

    /** POST /auth/refresh (cookie only) -> { accessToken } and a rotated cookie */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        requireAjax(request);
        String raw = cookie.read(request).orElse(null);
        if (raw == null) {
            return expired(request);
        }
        try {
            AuthService.RefreshResult result = auth.refresh(raw);
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (result.refreshToken() != null) {
                ok.header(HttpHeaders.SET_COOKIE,
                    cookie.issue(result.refreshToken(), result.remember(), request).toString());
            }
            return ok.body(new AccessTokenResponse(result.accessToken()));
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.UNAUTHORIZED) {
                return expired(request);
            }
            throw ex;
        }
    }

    /** POST /auth/logout: revoke the session and clear the cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        requireAjax(request);
        cookie.read(request).ifPresent(auth::logout);
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookie.clear(request).toString())
            .build();
    }

    /** POST /auth/forgot-password { email } - always 200. */
    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        auth.forgotPassword(body.email());
        return new MessageResponse("If an account exists for that email, we've sent a link to reset the password.");
    }

    /** POST /auth/reset-password { token, password } - used by /reset-password and /set-password. */
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        auth.resetPassword(body.token(), body.password());
        return new MessageResponse("Your password has been saved. You can now sign in.");
    }

    /** POST /auth/change-password { currentPassword, newPassword } - signed in. */
    @PostMapping("/change-password")
    public MessageResponse changePassword(@AuthenticationPrincipal Jwt principal,
                                          @Valid @RequestBody ChangePasswordRequest body,
                                          HttpServletRequest request) {
        auth.changePassword(Long.valueOf(principal.getSubject()), body.currentPassword(), body.newPassword(),
            cookie.read(request).orElse(null));
        return new MessageResponse("Your password has been changed.");
    }

    private ResponseEntity<?> expired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.SET_COOKIE, cookie.clear(request).toString())
            .body(new MessageResponse(AuthService.SESSION_EXPIRED));
    }

    private static void requireAjax(HttpServletRequest request) {
        String header = request.getHeader(AJAX_HEADER);
        if (header == null || header.isBlank()) {
            throw ApiException.forbidden("This request was blocked.");
        }
    }
}
