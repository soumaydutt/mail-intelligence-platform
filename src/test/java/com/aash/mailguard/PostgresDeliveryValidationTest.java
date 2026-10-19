package com.aash.mailguard;

import com.aash.mailguard.delivery.DeliveryEvidenceService;
import com.aash.mailguard.delivery.DeliveryEvidenceService.EventInput;
import com.aash.mailguard.delivery.DeliveryEvidenceService.EventType;
import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.dns.DnsObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
class PostgresDeliveryValidationTest {
    private static final String KEY = "synthetic-delivery-validation-key";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "email", "domain", "formatValid", "mxRecordFound", "smtpVerified", "status", "message");
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private ConfigurableApplicationContext api;
    private ConfigurableApplicationContext worker;
    private HttpClient http;
    private JdbcTemplate jdbc;
    private int port;

    @BeforeEach
    void startApi() {
        http = HttpClient.newHttpClient();
        api = start(false);
        jdbc = api.getBean(JdbcTemplate.class);
        port = api.getEnvironment().getProperty("local.server.port", Integer.class);
        when(api.getBean(DnsLookupService.class).lookup("fixture.test")).thenReturn(route());
    }

    @AfterEach
    void stopProcesses() {
        if (worker != null) worker.close();
        if (api != null) api.close();
        if (http != null) http.close();
    }

    @Test
    void authenticatedSingleValidationEnrichesWithoutExposingEvidenceToLegacyCaller() throws Exception {
        String email = "User@fixture.test";
        UUID event = ingest(email, EventType.COMPLAINT, "sync-complaint");
        JsonNode legacy = JSON.readTree(send("POST", "/api/email/validate",
                "{\"email\":\"" + email + "\"}", null).body());
        assertEquals(LEGACY_FIELDS, JSON.convertValue(legacy, Map.class).keySet());
        assertEquals("UNVERIFIED", legacy.get("status").asText());
        assertTrue(legacy.get("smtpVerified").isNull());
        JsonNode authorized = JSON.readTree(send("POST", "/api/email/validate",
                "{\"email\":\"" + email + "\"}", KEY).body());
        assertTrue(authorized.has("historicalEvidence"));
        assertEquals("UNVERIFIED", authorized.get("status").asText());
        assertTrue(authorized.get("mxRecordFound").asBoolean());
        assertTrue(authorized.get("historicalEvidence").get("suppressed").asBoolean());
        assertEquals("COMPLAINT", authorized.get("historicalEvidence").get("eventType").asText());
        assertEquals(event.toString(), authorized.get("historicalEvidence").get("eventId").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM email_validation_history WHERE email = ? AND status = 'UNVERIFIED'",
                Integer.class, email));
        assertEquals(401, send("POST", "/api/email/validate",
                "{\"email\":\"" + email + "\"}", "incorrect").statusCode());
    }

    @Test
    void asyncJobKeepsSevenFieldsAndDurableHistoricalSnapshotAcrossApiRestart() throws Exception {
        String email = "job@fixture.test";
        UUID event = ingest(email, EventType.DELIVERED, "job-delivery");
        worker = start(true);
        when(worker.getBean(DnsLookupService.class).lookup("fixture.test")).thenReturn(route());
        JsonNode accepted = JSON.readTree(send("POST", "/api/jobs", "{\"email\":\"" + email + "\"}", KEY).body());
        UUID job = UUID.fromString(accepted.get("id").asText());
        JsonNode completed = await("/api/jobs/" + job, "state", "COMPLETED", 15);
        JsonNode result = completed.get("result");
        for (String field : LEGACY_FIELDS) assertTrue(result.has(field), field);
        assertEquals("UNVERIFIED", result.get("status").asText());
        assertEquals(event.toString(), result.get("historicalEvidence").get("eventId").asText());
        assertEquals("DELIVERED", jdbc.queryForObject(
                "SELECT result_historical_evidence ->> 'eventType' FROM verification_job WHERE id = ?",
                String.class, job));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, job));
        worker.close();
        worker = null;
        api.close();
        api = start(false);
        port = api.getEnvironment().getProperty("local.server.port", Integer.class);
        JsonNode restored = JSON.readTree(send("GET", "/api/jobs/" + job, null, KEY).body());
        assertEquals(event.toString(), restored.get("result").get("historicalEvidence").get("eventId").asText());
    }
    private UUID ingest(String email, EventType type, String id) {
        return api.getBean(DeliveryEvidenceService.class).ingest(
                new EventInput(email, type, id, Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS), null)).id();
    }

    private JsonNode await(String path, String field, String expected, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode state = JSON.readTree(send("GET", path, null, KEY).body());
            if (expected.equals(state.get(field).asText())) return state;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + expected);
    }

    private HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (key != null) request.header("X-Job-API-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static ConfigurableApplicationContext start(boolean worker) {
        return new SpringApplicationBuilder(MailGuardApplication.class, DnsFixture.class)
                .web(worker ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--jobs.api-key=" + KEY, "--delivery.source-id=validation-fixture",
                        "--jobs.worker.enabled=" + worker, "--jobs.worker.poll-millis=50",
                        "--imports.worker.poll-millis=50");
    }

    private static DnsObservation route() {
        return new DnsObservation(DnsObservation.Outcome.MAIL_ROUTE, DnsObservation.Reason.MX_PRESENT,
                "fixture.test", List.of(new DnsObservation.MxHost(10, "mx.fixture.test")), false,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DnsFixture {
        @Bean
        @Primary
        DnsLookupService offlineDns() {
            return Mockito.mock(DnsLookupService.class);
        }
    }
}
