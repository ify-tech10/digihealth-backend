package com.digihealth.auth;

import java.time.Duration;

/** What a password link is for, and how long it lasts. */
public enum TokenPurpose {
    /** New account invite: /set-password */
    SET(Duration.ofHours(72), "/set-password", "72 hours"),
    /** Forgot password: /reset-password */
    RESET(Duration.ofHours(1), "/reset-password", "1 hour");

    public final Duration lifetime;
    public final String page;
    public final String label;

    TokenPurpose(Duration lifetime, String page, String label) {
        this.lifetime = lifetime;
        this.page = page;
        this.label = label;
    }
}
