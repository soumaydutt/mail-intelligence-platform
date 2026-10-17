package com.aash.mailguard;

import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.dns.DnsObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
class PostgresHttpEndToEndTest {
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "email", "domain", "formatValid", "mxRecordFound", "smtpVerified", "status", "message");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private ConfigurableApplicationContext application;
    private DnsLookupService dns;
    private JdbcTemplate jdbc;
    private HttpClient http;
    private int port;

    @BeforeEach
    void startFreshApplication() {
        http = HttpClient.newHttpClient();
        application = startApplication();
        jdbc.update("DELETE FROM email_validation_history");
    }

    @AfterEach
    void stopApplication() {
        if (application != null) {
            application.close();
        }
        http.close();
    }

    @Test
    void validationOverHttpPreservesSevenFieldsAndNullableHistory() throws Exception {
        JsonNode invalid = validate("invalid", 200);
        assertResult(invalid, "invalid", null, false, null, "INVALID", "Invalid email format");
        assertRow(invalid);
        verifyNoInteractions(dns);

        when(dns.lookup("mx.test")).thenReturn(observation(DnsObservation.Outcome.MAIL_ROUTE,
                DnsObservation.Reason.MX_PRESENT, "mx.test"));
        JsonNode mx = validate("a@mx.test", 200);
        assertResult(mx, "a@mx.test", "mx.test", true, true, "UNVERIFIED",
                "MX record observed; mailbox not verified.");
        assertRow(mx);

        when(dns.lookup("absent.test")).thenReturn(observation(DnsObservation.Outcome.NO_MAIL_ROUTE,
                DnsObservation.Reason.NO_MAIL_ROUTE, "absent.test"));
        JsonNode absent = validate("b@absent.test", 200);
        assertResult(absent, "b@absent.test", "absent.test", true, false, "UNVERIFIED",
                "No MX record observed; mailbox not verified.");
        assertRow(absent);

        when(dns.lookup("unknown.test")).thenReturn(observation(DnsObservation.Outcome.UNKNOWN,
                DnsObservation.Reason.DNS_TEMPORARY_FAILURE, "unknown.test"));
        JsonNode unknown = validate("c@unknown.test", 200);
        assertResult(unknown, "c@unknown.test", "unknown.test", true, null, "UNVERIFIED",
                "DNS lookup unavailable; mailbox not verified.");
        assertRow(unknown);
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));
    }

    @Test
    void testRouteAndRejectedBodiesNeverCreateHistory() throws Exception {
        HttpResponse<String> health = post("/api/email/test", "");
        assertEquals(200, health.statusCode());
        assertEquals("MailGuard API Working", health.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));

        HttpResponse<String> oversized = post("/api/email/validate",
                JSON.writeValueAsString(Map.of("email", "x".repeat(256))));
        assertEquals(400, oversized.statusCode());
        assertEquals("Invalid request", JSON.readTree(oversized.body()).get("message").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));
        HttpResponse<String> malformed = post("/api/email/validate", "{\"email\":\"unfinished");
        assertEquals(400, malformed.statusCode());
        assertEquals("Invalid request", JSON.readTree(malformed.body()).get("message").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));

        when(dns.lookup("broken.test")).thenThrow(new IllegalStateException("private-error-marker"));
        HttpResponse<String> failed = post("/api/email/validate", "{\"email\":\"a@broken.test\"}");
        assertEquals(500, failed.statusCode());
        assertEquals("Internal server error", JSON.readTree(failed.body()).get("message").asText());
        assertFalse(failed.body().contains("private-error-marker"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));
    }

    @Test
    void historySurvivesClosingAndRestartingTheRunningApplication() throws Exception {
        when(dns.lookup("restart.test")).thenReturn(observation(DnsObservation.Outcome.MAIL_ROUTE,
                DnsObservation.Reason.MX_PRESENT, "restart.test"));
        JsonNode before = validate("a@restart.test", 200);
        assertRow(before);
        Long id = jdbc.queryForObject("SELECT id FROM email_validation_history WHERE email = ?",
                Long.class, "a@restart.test");
        assertNotNull(id);

        application.close();
        application = startApplication();
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
        assertEquals(id, jdbc.queryForObject("SELECT id FROM email_validation_history WHERE email = ?",
                Long.class, "a@restart.test"));
        assertRow(before);
        JsonNode after = validate("invalid-after-restart", 200);
        assertResult(after, "invalid-after-restart", null, false, null, "INVALID", "Invalid email format");
        assertRow(after);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));
        verifyNoInteractions(dns);
    }

    @Test
    void concurrentHttpRequestsPersistAccuratelyWithoutDroppingRows() throws Exception {
        when(dns.lookup("concurrent.test")).thenReturn(observation(DnsObservation.Outcome.MAIL_ROUTE,
                DnsObservation.Reason.MX_PRESENT, "concurrent.test"));

        int concurrency = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    return post("/api/email/validate",
                            JSON.writeValueAsString(Map.of("email", "user" + idx + "@concurrent.test")));
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        try {
            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            for (int i = 0; i < futures.size(); i++) {
                HttpResponse<String> response = futures.get(i).get();
                assertEquals(200, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals("user" + i + "@concurrent.test", body.get("email").asText());
                assertEquals("UNVERIFIED", body.get("status").asText());
                assertTrue(body.get("formatValid").asBoolean());
                assertTrue(body.get("mxRecordFound").asBoolean());
            }
            assertEquals(concurrency, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));
            assertEquals(concurrency, jdbc.queryForObject("SELECT count(DISTINCT email) FROM email_validation_history", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private ConfigurableApplicationContext startApplication() {
        ConfigurableApplicationContext started = new SpringApplicationBuilder(
                MailGuardApplication.class, DnsFixture.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true");
        application = started;
        dns = started.getBean(DnsLookupService.class);
        jdbc = started.getBean(JdbcTemplate.class);
        port = started.getEnvironment().getProperty("local.server.port", Integer.class);
        return started;
    }

    private JsonNode validate(String email, int status) throws Exception {
        HttpResponse<String> response = post("/api/email/validate",
                JSON.writeValueAsString(Map.of("email", email)));
        assertEquals(status, response.statusCode());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static DnsObservation observation(DnsObservation.Outcome outcome, DnsObservation.Reason reason,
                                               String domain) {
        List<DnsObservation.MxHost> hosts = reason == DnsObservation.Reason.MX_PRESENT
                ? List.of(new DnsObservation.MxHost(10, "mx.fixture.test")) : List.of();
        return new DnsObservation(outcome, reason, domain, hosts,
                reason == DnsObservation.Reason.DNS_TEMPORARY_FAILURE, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static void assertResult(JsonNode result, String email, String domain, boolean formatValid,
                                     Boolean mxFound, String status, String message) {
        Set<String> fields = new HashSet<>();
        result.fieldNames().forEachRemaining(fields::add);
        assertEquals(RESPONSE_FIELDS, fields);
        assertEquals(email, result.get("email").asText());
        if (domain == null) {
            assertTrue(result.get("domain").isNull());
        } else {
            assertEquals(domain, result.get("domain").asText());
        }
        assertEquals(formatValid, result.get("formatValid").booleanValue());
        if (mxFound == null) {
            assertTrue(result.get("mxRecordFound").isNull());
        } else {
            assertEquals(mxFound, result.get("mxRecordFound").booleanValue());
        }
        assertTrue(result.get("smtpVerified").isNull());
        assertEquals(status, result.get("status").asText());
        assertEquals(message, result.get("message").asText());
    }

    private void assertRow(JsonNode response) {
        Map<String, Object> row = jdbc.queryForMap("SELECT email, domain, format_valid, mx_record_found, "
                + "smtp_verified, status, message, created_at FROM email_validation_history WHERE email = ?",
                response.get("email").asText());
        assertEquals(response.get("email").asText(), row.get("email"));
        assertEquals(response.get("domain").isNull() ? null : response.get("domain").asText(), row.get("domain"));
        assertEquals(response.get("formatValid").booleanValue(), row.get("format_valid"));
        assertEquals(response.get("mxRecordFound").isNull() ? null : response.get("mxRecordFound").booleanValue(),
                row.get("mx_record_found"));
        assertNull(row.get("smtp_verified"));
        assertEquals(response.get("status").asText(), row.get("status"));
        assertEquals(response.get("message").asText(), row.get("message"));
        assertNotNull(row.get("created_at"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DnsFixture {
        @Bean
        @Primary
        DnsLookupService dnsFixture() {
            return Mockito.mock(DnsLookupService.class);
        }
    }
}
