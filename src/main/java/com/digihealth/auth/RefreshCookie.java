package com.digihealth.auth;

import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import com.digihealth.config.AppProperties;

/*
 * The dh_refresh cookie: HttpOnly (scripts can't read it), Secure, SameSite=Strict
 * and Path=/api/auth (only sent to the auth endpoints).
 * "Remember me" adds Max-Age; otherwise it's a session cookie that ends when
 * the browser closes.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "dh_refresh";

    private final AppProperties props;

    public RefreshCookie(AppProperties props) {
        this.props = props;
    }

    public ResponseCookie issue(String rawToken, boolean remember, HttpServletRequest request) {
        ResponseCookie.ResponseCookieBuilder cookie = base(rawToken, request);
        if (remember) {
            cookie.maxAge(props.tokens().rememberTtl());
        }
        return cookie.build();
    }

    public ResponseCookie clear(HttpServletRequest request) {
        return base("", request).maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        return cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()
            ? Optional.empty()
            : Optional.of(cookie.getValue());
    }

    private ResponseCookie.ResponseCookieBuilder base(String value, HttpServletRequest request) {
        return ResponseCookie.from(NAME, value)
            .httpOnly(true)
            .secure(props.tokens().cookieSecure())
            .sameSite("Strict")
            .path(request.getContextPath() + "/auth");
    }
}
