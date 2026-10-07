package com.digihealth.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.auth.dto.AuthDtos.LoginRequest;
import com.digihealth.auth.dto.AuthDtos.LoginResponse;
import com.digihealth.common.ApiException;
import com.digihealth.config.AppProperties;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Two tabs refreshing at the same moment: the second one gets a pass. */
    private static final Duration REUSE_GRACE = Duration.ofSeconds(30);
    private static final Duration LINK_COOLDOWN = Duration.ofMinutes(1);

    static final String BAD_LOGIN = "Incorrect email or password.";
    static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";
    static final String BAD_LINK = "This link has expired or was already used. Request a new one from the login page.";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordTokenRepository passwordTokens;
    private final PasswordLinks passwordLinks;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginAttempts attempts;
    private final AppProperties props;
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordTokenRepository passwordTokens, PasswordLinks passwordLinks,
                       PasswordEncoder encoder, JwtService jwt, LoginAttempts attempts, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordTokens = passwordTokens;
        this.passwordLinks = passwordLinks;
        this.encoder = encoder;
        this.jwt = jwt;
        this.attempts = attempts;
        this.props = props;
        /* Checked against when the email doesn't exist, so both cases take the same time. */
        this.dummyHash = encoder.encode("timing-equaliser-" + UUID.randomUUID());
    }

    public record LoginResult(LoginResponse body, String refreshToken, boolean remember) {
    }

    /** refreshToken is null when the cookie should stay as it is. */
    public record RefreshResult(String accessToken, String refreshToken, boolean remember) {
    }

    /* ── Sign in ── */

    @Transactional
    public LoginResult login(LoginRequest req) {
        String email = User.normalizeEmail(req.email());
        attempts.check(email);

        User user = users.findByEmail(email).orElse(null);
        boolean ok;
        if (user != null && user.hasPassword()) {
            ok = encoder.matches(req.password(), user.getPasswordHash());
        } else {
            encoder.matches(req.password(), dummyHash);
            ok = false;
        }
        if (!ok) {
            attempts.failed(email);
            throw ApiException.unauthorized(BAD_LOGIN);
        }
        attempts.succeeded(email);

        if (!user.isActive()) {
            throw ApiException.forbidden("Your account has been deactivated. Please contact DiGi Health support.");
        }

        Instant now = Instant.now();
        user.setLastLoginAt(now);

        String raw = newRefreshToken(user, UUID.randomUUID(), req.rememberMe(), now);
        LoginResponse body = new LoginResponse(
            jwt.issue(user), user.getRole().name(), user.getFullName(), user.getEmail(), user.getId());
        return new LoginResult(body, raw, req.rememberMe());
    }

    /* ── Refresh (rotate the cookie) ── */

    @Transactional(noRollbackFor = ApiException.class)
    public RefreshResult refresh(String rawCookie) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokens.findForUpdate(Tokens.hash(rawCookie))
            .orElseThrow(() -> ApiException.unauthorized(SESSION_EXPIRED));
        User user = token.getUser();

        if (token.getRevokedAt() != null) {
            throw ApiException.unauthorized(SESSION_EXPIRED);
        }

        if (token.getRotatedAt() != null) {
            if (token.getRotatedAt().isAfter(now.minus(REUSE_GRACE)) && user.isActive()) {
                /* Another tab refreshed a moment ago and the browser already has the new cookie. */
                return new RefreshResult(jwt.issue(user), null, token.isRemember());
            }
            /* An old token came back: it may have been stolen. End the whole session. */
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; session revoked.", user.getId());
            throw ApiException.unauthorized(SESSION_EXPIRED);
        }

        if (token.getExpiresAt().isBefore(now) || !user.isActive()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw ApiException.unauthorized(SESSION_EXPIRED);
        }

        token.setRotatedAt(now);
        String raw = newRefreshToken(user, token.getFamilyId(), token.isRemember(), now);
        return new RefreshResult(jwt.issue(user), raw, token.isRemember());
    }

    /* ── Sign out ── */

    @Transactional
    public void logout(String rawCookie) {
        refreshTokens.findByTokenHash(Tokens.hash(rawCookie))
            .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /* ── Forgot password: always the same answer, so it can't reveal who has an account ── */

    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = User.normalizeEmail(rawEmail);
        Optional<User> found = users.findByEmail(email).filter(User::isActive);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        if (passwordTokens.sentSince(user.getId(), Instant.now().minus(LINK_COOLDOWN))) {
            return;
        }
        /* Someone who never set a password gets the "set your password" email instead. */
        passwordLinks.send(user, user.hasPassword() ? TokenPurpose.RESET : TokenPurpose.SET);
    }

    /* ── Set or reset password from an emailed link ── */

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = Instant.now();
        PasswordToken token = passwordTokens.findForUpdate(Tokens.hash(rawToken))
            .orElseThrow(() -> ApiException.gone(BAD_LINK));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw ApiException.gone(BAD_LINK);
        }
        User user = token.getUser();
        if (!user.isActive()) {
            throw ApiException.forbidden("Your account has been deactivated. Please contact DiGi Health support.");
        }

        user.setPasswordHash(encoder.encode(newPassword));
        passwordTokens.invalidateAllForUser(user.getId(), now);
        refreshTokens.revokeAllForUser(user.getId(), now);
        attempts.succeeded(user.getEmail());
    }

    /* ── Change password while signed in ── */

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword, String rawCookie) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized(SESSION_EXPIRED));
        if (!user.hasPassword() || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Your current password is incorrect.");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Choose a password different from your current one.");
        }
        user.setPasswordHash(encoder.encode(newPassword));

        /* Sign out every other device; keep this one signed in. */
        Instant now = Instant.now();
        Optional<RefreshToken> current = rawCookie == null
            ? Optional.empty()
            : refreshTokens.findByTokenHash(Tokens.hash(rawCookie));
        if (current.isPresent() && current.get().getUser().getId().equals(userId)) {
            refreshTokens.revokeOthersForUser(userId, current.get().getFamilyId(), now);
        } else {
            refreshTokens.revokeAllForUser(userId, now);
        }
    }

    /* ── Nightly clean-up of long-expired tokens ── */

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void deleteExpiredTokens() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        int refresh = refreshTokens.deleteExpiredBefore(cutoff);
        int links = passwordTokens.deleteExpiredBefore(cutoff);
        if (refresh + links > 0) {
            log.info("Deleted {} expired refresh tokens and {} expired password links", refresh, links);
        }
    }

    private String newRefreshToken(User user, UUID family, boolean remember, Instant now) {
        Duration lifetime = remember ? props.tokens().rememberTtl() : props.tokens().sessionTtl();
        String raw = Tokens.newRaw();
        refreshTokens.save(new RefreshToken(user, Tokens.hash(raw), family, remember, now.plus(lifetime)));
        return raw;
    }
}
