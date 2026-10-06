package com.digihealth.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Values under {@code app.*} in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String frontendUrl, Cors cors, BootstrapAdmin bootstrapAdmin) {

    public record Cors(List<String> allowedOrigins) {
    }

    /** First super admin, created on startup if no admin exists. */
    public record BootstrapAdmin(String email, String name) {
    }
}
