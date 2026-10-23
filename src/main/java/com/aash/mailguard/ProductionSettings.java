package com.aash.mailguard;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class ProductionSettings {
    ProductionSettings(Environment environment) {
        String database = environment.getProperty("spring.datasource.url", "");
        if (!database.startsWith("jdbc:postgresql://") || database.contains("@")
                || database.toLowerCase(Locale.ROOT).matches(".*[?&](user|password)=.*")) {
            throw new IllegalStateException("Production requires a PostgreSQL URL without embedded credentials");
        }
        required(environment, "spring.datasource.username", 1, 128);
        required(environment, "spring.datasource.password", 1, 256);
        required(environment, "jobs.api-key", 32, 256);
        required(environment, "spring.data.redis.password", 32, 256);
        required(environment, "rate-limit.identity-secret", 32, 256);
        if (!environment.getProperty("validation.require-api-key", Boolean.class, false)
                || !environment.getProperty("rate-limit.enabled", Boolean.class, false)
                || !environment.getProperty("management.health.redis.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Production requires authenticated validation, shared admission and Valkey health checks");
        }
        String host = environment.getProperty("spring.data.redis.host", "");
        if (host.isBlank() || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1")) {
            throw new IllegalStateException("Production requires a private, non-loopback Valkey host");
        }
        if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"))) {
            throw new IllegalStateException("Forwarded headers must not change the admission peer");
        }
    }

    private static void required(Environment environment, String property, int minimumBytes, int maximumBytes) {
        String value = environment.getProperty(property, "");
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < minimumBytes || bytes > maximumBytes || !value.equals(value.strip())
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalStateException("Invalid production setting: " + property);
        }
    }
}
