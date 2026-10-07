package com.digihealth.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The signed-in user's id, taken from the access token ("sub" claim). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Long id = idOrNull();
        if (id == null) {
            throw ApiException.unauthorized("Please sign in to continue.");
        }
        return id;
    }

    /** Null when nobody is signed in (public endpoints, scheduled jobs). */
    public static Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Long.valueOf(jwt.getToken().getSubject());
        }
        return null;
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }
}
