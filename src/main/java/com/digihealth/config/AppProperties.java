package com.digihealth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Values under {@code app.*} in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        Cors cors,
        BootstrapAdmin bootstrapAdmin,
        Tokens tokens,
        Mail mail,
        Cloudinary cloudinary) {

    public record Cors(List<String> allowedOrigins) {
    }

    /** First super admin, created on startup if no admin exists. */
    public record BootstrapAdmin(String email, String name) {
    }

    /** Access token (JWT) and refresh cookie settings. */
    public record Tokens(
            String jwtSecret,
            Duration accessTtl,
            Duration rememberTtl,
            Duration sessionTtl,
            boolean cookieSecure) {
    }

    public record Mail(boolean enabled, String from, String fromName) {
    }

    /** cloudinary://<api_key>:<api_secret>@<cloud_name> */
    public record Cloudinary(String url) {
    }
}
