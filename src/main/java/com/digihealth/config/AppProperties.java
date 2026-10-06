package com.digihealth.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Values under {@code app.*} in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String frontendUrl, Cors cors) {

    public record Cors(List<String> allowedOrigins) {
    }
}
