package com.aash.mailguard;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class ProductionSettingsTest {
    @TempDir Path secrets;

    @Configuration(proxyBeanMethods = false)
    @Import(ProductionSettings.class)
    static class OnlyProductionSettings { }

    private String[] settings(String... overrides) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("spring.profiles.active", "prod");
        values.put("spring.config.import", "configtree:" + secrets.toAbsolutePath().toString().replace('\\', '/') + "/");
        values.put("spring.datasource.url", "jdbc:postgresql://postgres:5432/mailguard");
        values.put("spring.datasource.username", "mailguard");
        values.put("spring.data.redis.host", "valkey");
        values.put("rate-limit.enabled", "true");
        for (String override : overrides) {
            int separator = override.indexOf('=');
            values.put(override.substring(2, separator), override.substring(separator + 1));
        }
        return values.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private void mountedSecrets() throws Exception {
        Files.writeString(secrets.resolve("DB_PASSWORD"), "postgres-fixture-secret");
        Files.writeString(secrets.resolve("JOBS_API_KEY"), "synthetic-administrator-key-32-characters");
        Files.writeString(secrets.resolve("VALKEY_PASSWORD"), "synthetic-valkey-password-32-characters");
        Files.writeString(secrets.resolve("RATE_LIMIT_IDENTITY_SECRET"), "synthetic-peer-hmac-secret-32-characters");
    }

    private ConfigurableApplicationContext launch(String... overrides) {
        return new SpringApplicationBuilder(OnlyProductionSettings.class).web(WebApplicationType.NONE)
                .logStartupInfo(false).run(settings(overrides));
    }

    @Test
    void mountedConfigtreeSecretsAreUsedWithoutCommandLineCredentials() throws Exception {
        mountedSecrets();
        try (var context = launch()) {
            assertEquals("postgres-fixture-secret", context.getEnvironment().getProperty("spring.datasource.password"));
            assertEquals("synthetic-administrator-key-32-characters",
                    context.getEnvironment().getProperty("jobs.api-key"));
            assertEquals("synthetic-valkey-password-32-characters",
                    context.getEnvironment().getProperty("spring.data.redis.password"));
            assertTrue(context.containsBean("com.aash.mailguard.ProductionSettings"));
        }
    }

    @Test
    void missingOrWeakSecretsFailBeforeApplicationStarts() throws Exception {
        mountedSecrets();
        Files.delete(secrets.resolve("JOBS_API_KEY"));
        assertThrows(RuntimeException.class, this::launch);
        mountedSecrets();
        Files.writeString(secrets.resolve("VALKEY_PASSWORD"), "weak");
        assertThrows(RuntimeException.class, this::launch);
        mountedSecrets();
        Files.writeString(secrets.resolve("RATE_LIMIT_IDENTITY_SECRET"), "bad\n");
        assertThrows(RuntimeException.class, this::launch);
    }

    @Test
    void disabledAdmissionUnsafeDatabaseAndForwardedPeerModeFailClosed() throws Exception {
        mountedSecrets();
        assertThrows(RuntimeException.class, () -> launch("--validation.require-api-key=false"));
        assertThrows(RuntimeException.class, () -> launch("--rate-limit.enabled=false"));
        assertThrows(RuntimeException.class, () -> launch("--spring.datasource.url=jdbc:h2:mem:unexpected"));
        assertThrows(RuntimeException.class, () -> launch("--spring.data.redis.host=127.0.0.1"));
        assertThrows(RuntimeException.class, () -> launch("--server.forward-headers-strategy=framework"));
    }

    @Test
    void developmentProfileDoesNotRequireProductionSecrets() {
        try (var context = new SpringApplicationBuilder(OnlyProductionSettings.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run()) {
            assertFalse(context.getBeansOfType(ProductionSettings.class).size() > 0);
        }
    }
}
