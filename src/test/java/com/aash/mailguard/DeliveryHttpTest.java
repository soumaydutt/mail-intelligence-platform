package com.aash.mailguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = false)
class DeliveryHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "synthetic-delivery-key";
    private static final String SOURCE = "synthetic-admin-source";
    private static final String ADDRESS = "Private@fixture.test";
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private ConfigurableApplicationContext api;
    private JdbcTemplate jdbc;
    private HttpClient http;
    private int port;

    @BeforeEach
    void start() {
        http = HttpClient.newHttpClient();
        restart(KEY);
    }

    @AfterEach
    void stop() {
        if (api != null) api.close();
        http.close();
    }

    @Test
    void allRoutesRejectMissingWrongAndUnconfiguredKeysBeforeParsing() throws Exception {
        UUID id = UUID.randomUUID();
        List<String[]> routes = List.of(
                new String[] {"POST", "/api/delivery-events"},
                new String[] {"POST", "/api/delivery-events/" + id + "/revoke"},
                new String[] {"POST", "/api/delivery-evidence/lookup"},
                new String[] {"GET", "/api/suppressions"},
                new String[] {"POST", "/api/suppressions"},
                new String[] {"DELETE", "/api/suppressions/" + id});
        for (String[] route : routes) {
            for (String supplied : new String[] {null, "wrong-key"}) {
                fixedError(send(route[0], route[1], "{malformed", supplied), 401);
            }
        }
        assertEquals(0, count("delivery_event"));
        assertEquals(0, count("delivery_suppression"));
        restart("");
        for (String[] route : routes) {
            fixedError(send(route[0], route[1], "{malformed", KEY), 401);
        }
        assertEquals(0, count("delivery_event"));
        assertEquals(0, count("delivery_suppression"));
    }

    @Test
    void strictJsonAndOversizeRejectBeforeAnyWrite() throws Exception {
        String valid = event(ADDRESS, "HARD_BOUNCE", "strict-1", "5.1.1");
        for (String invalid : List.of("{", "[]", "{}", "{\"email\":17}",
                valid.replace("\"HARD_BOUNCE\"", "true"),
                valid.replace("\"HARD_BOUNCE\"", "\"INVALID\""),
                valid.replace("\"externalEventId\"", "\"untrustedSource\""),
                valid.substring(0, valid.length() - 1) + ",\"sourceType\":\"ADMIN_JSON\"}",
                valid.substring(0, valid.length() - 1) + ",\"email\":\"changed@fixture.test\"}")) {
            fixedError(send("POST", "/api/delivery-events", invalid, KEY), 400);
        }
        fixedError(send("POST", "/api/delivery-events", "{\"padding\":\"" + "x".repeat(8192) + "\"}", KEY), 413);
        HttpRequest streamed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/delivery-events"))
                .header("X-Job-API-Key", KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(
                        ("{\"email\":\"" + "x".repeat(8192) + "\"}").getBytes(StandardCharsets.UTF_8))))
                .build();
        fixedError(http.send(streamed, HttpResponse.BodyHandlers.ofString()), 413);
        fixedError(send("POST", "/api/delivery-evidence/lookup", "{\"email\":null}", KEY), 400);
        fixedError(send("POST", "/api/suppressions", "{\"email\":\"" + ADDRESS + "\",\"reasonCode\":false}", KEY), 400);
        fixedError(send("GET", "/api/suppressions?limit=101", null, KEY), 400);
        fixedError(send("GET", "/api/suppressions?limit=1&limit=2", null, KEY), 400);
        fixedError(send("POST", "/api/delivery-events/private%40fixture.test/revoke",
                "{\"reasonCode\":\"CORRECTION\"}", KEY), 400);
        fixedError(send("POST", "/api/delivery-events/" + UUID.randomUUID() + "/revoke", "{\"reasonCode\":\"CORRECTION\"}", KEY), 404);
        fixedError(send("DELETE", "/api/suppressions/" + UUID.randomUUID(), "{\"reasonCode\":\"REQUEST\"}", KEY), 404);
        assertEquals(0, count("delivery_event"));
        assertEquals(0, count("delivery_suppression"));
    }

    @Test
    void replayConflictCorrectionAndDeliveryDoNotEraseSuppression() throws Exception {
        String bounce = event(ADDRESS, "HARD_BOUNCE", "bounce-1", "5.1.1");
        HttpResponse<String> first = send("POST", "/api/delivery-events", bounce, KEY);
        assertEquals(201, first.statusCode());
        noStore(first);
        UUID id = UUID.fromString(JSON.readTree(first.body()).get("id").asText());
        HttpResponse<String> replay = send("POST", "/api/delivery-events", bounce, KEY);
        assertEquals(200, replay.statusCode());
        assertEquals(id.toString(), JSON.readTree(replay.body()).get("id").asText());
        assertTrue(JSON.readTree(replay.body()).get("reused").asBoolean());
        fixedError(send("POST", "/api/delivery-events", event(ADDRESS, "HARD_BOUNCE", "bounce-1", "5.2.1"), KEY), 409);
        assertEquals(1, count("delivery_event"));
        assertEquals(1, count("delivery_suppression"));
        assertEquals("synthetic-admin-source", jdbc.queryForObject(
                "SELECT source_identifier FROM delivery_event WHERE id = ?", String.class, id));
        assertEquals("ADMIN_JSON", jdbc.queryForObject(
                "SELECT source_type FROM delivery_event WHERE id = ?", String.class, id));
        assertEquals("RECIPIENT_HARD_BOUNCE", jdbc.queryForObject(
                "SELECT interpretation FROM delivery_event WHERE id = ?", String.class, id));
        JsonNode snapshot = lookup("Private@FIXTURE.TEST");
        assertEquals(id.toString(), snapshot.get("eventId").asText());
        assertEquals("FRESH", snapshot.get("freshness").asText());
        assertTrue(snapshot.get("suppressed").asBoolean());
        assertEquals(SOURCE, snapshot.get("sourceIdentifier").asText());

        UUID complaint = ingest(ADDRESS, "COMPLAINT", "complaint-1", null);
        ingest(ADDRESS, "DELIVERED", "delivery-1", null);
        assertTrue(lookup(ADDRESS).get("suppressed").asBoolean());
        assertEquals(2, count("delivery_suppression"));
        HttpResponse<String> revoked = send("POST", "/api/delivery-events/" + id + "/revoke", "{\"reasonCode\":\"CORRECTION\"}", KEY);
        assertEquals(204, revoked.statusCode());
        noStore(revoked);
        assertEquals("CORRECTION", jdbc.queryForObject(
                "SELECT revocation_reason FROM delivery_event WHERE id = ?", String.class, id));
        assertEquals("CORRECTION", jdbc.queryForObject(
                "SELECT lifting_reason FROM delivery_suppression WHERE source_event_id = ?", String.class, id));
        assertTrue(lookup(ADDRESS).get("suppressed").asBoolean());
        fixedError(send("POST", "/api/delivery-events/" + id + "/revoke", "{\"reasonCode\":\"CORRECTION\"}", KEY), 409);
        HttpResponse<String> revokedReplay = send("POST", "/api/delivery-events", bounce, KEY);
        assertEquals(200, revokedReplay.statusCode());
        assertTrue(JSON.readTree(revokedReplay.body()).get("reused").asBoolean());
        assertEquals(2, count("delivery_suppression"));
        HttpResponse<String> lifted = send("DELETE", "/api/suppressions/" + jdbc.queryForObject(
                "SELECT id FROM delivery_suppression WHERE source_event_id = ?", UUID.class, complaint),
                "{\"reasonCode\":\"CORRECTION\"}", KEY);
        assertEquals(204, lifted.statusCode());
        noStore(lifted);
        assertFalse(lookup(ADDRESS).get("suppressed").asBoolean());
        assertEquals(3, count("delivery_event"));
        assertEquals(2, count("delivery_suppression"));
    }

    @Test
    void onlyRecipientSpecificHardBounceSuppressesAndInputMustHaveValidTime() throws Exception {
        fixedError(send("POST", "/api/delivery-events",
                event(ADDRESS, "DELIVERED", "future-1", null)
                        .replaceFirst("\"occurredAt\":\"[^\"]+\"", "\"occurredAt\":\"2100-01-01T00:00:00Z\""),
                KEY), 400);
        fixedError(send("POST", "/api/delivery-events",
                event(ADDRESS, "DELIVERED", "ancient-1", null)
                        .replaceFirst("\"occurredAt\":\"[^\"]+\"", "\"occurredAt\":\"1900-01-01T00:00:00Z\""),
                KEY), 400);
        ingest(ADDRESS, "HARD_BOUNCE", "other-hard", "5.2.1");
        assertEquals("UNCERTAIN_BOUNCE", lookup(ADDRESS).get("interpretation").asText());
        assertFalse(lookup(ADDRESS).get("suppressed").asBoolean());
        ingest(ADDRESS, "HARD_BOUNCE", "malformed-hard", "5.1.1?");
        assertEquals("UNCERTAIN_BOUNCE", jdbc.queryForObject(
                "SELECT interpretation FROM delivery_event WHERE external_event_id = 'malformed-hard'", String.class));
        assertEquals(32, jdbc.queryForObject(
                "SELECT octet_length(status_fingerprint) FROM delivery_event WHERE external_event_id = 'malformed-hard'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM delivery_event WHERE external_event_id = 'malformed-hard' "
                        + "AND enhanced_status_code IS NOT NULL", Integer.class));
        fixedError(send("POST", "/api/delivery-events",
                event(ADDRESS, "HARD_BOUNCE", "diagnostic", "mailbox not found"), KEY), 400);
        ingest(ADDRESS, "SOFT_BOUNCE", "temporary", "4.2.1");
        assertEquals("TRANSIENT_BOUNCE", jdbc.queryForObject(
                "SELECT interpretation FROM delivery_event WHERE external_event_id = 'temporary'", String.class));
        assertEquals(0, count("delivery_suppression"));
    }

    @Test
    void manualSuppressionsListInBoundedPagesAndPersistLift() throws Exception {
        assertFalse(lookup(ADDRESS).get("available").asBoolean());
        assertFalse(lookup(ADDRESS).get("suppressed").asBoolean());
        assertEquals("NONE", lookup(ADDRESS).get("freshness").asText());
        HttpResponse<String> created = send("POST", "/api/suppressions",
                "{\"email\":\"" + ADDRESS + "\",\"reasonCode\":\"ADMIN_BLOCK\"}", KEY);
        assertEquals(201, created.statusCode());
        noStore(created);
        UUID id = UUID.fromString(JSON.readTree(created.body()).get("id").asText());
        assertTrue(lookup("Private@FIXTURE.TEST").get("suppressed").asBoolean());
        assertEquals("NONE", lookup(ADDRESS).get("freshness").asText());
        assertEquals("MANUAL", jdbc.queryForObject(
                "SELECT reason FROM delivery_suppression WHERE id = ?", String.class, id));
        assertEquals("ADMIN_BLOCK", jdbc.queryForObject(
                "SELECT manual_reason FROM delivery_suppression WHERE id = ?", String.class, id));
        HttpResponse<String> page = send("GET", "/api/suppressions?limit=1", null, KEY);
        noStore(page);
        JsonNode entries = JSON.readTree(page.body());
        assertEquals(1, entries.size());
        assertEquals(id.toString(), entries.get(0).get("id").asText());
        assertEquals("MANUAL", entries.get(0).get("reason").asText());
        assertEquals("Private@fixture.test", entries.get(0).get("email").asText());
        assertEquals(0, JSON.readTree(send("GET", "/api/suppressions?after=" + id + "&limit=1", null, KEY).body()).size());
        HttpResponse<String> lift = send("DELETE", "/api/suppressions/" + id,
                "{\"reasonCode\":\"ADMIN_RELEASE\"}", KEY);
        assertEquals(204, lift.statusCode());
        noStore(lift);
        assertEquals("ADMIN_RELEASE", jdbc.queryForObject(
                "SELECT lifting_reason FROM delivery_suppression WHERE id = ?", String.class, id));
        assertEquals(1, count("delivery_suppression"));
        assertEquals(0, count("delivery_event"));
        fixedError(send("DELETE", "/api/suppressions/" + id,
                "{\"reasonCode\":\"ADMIN_RELEASE\"}", KEY), 409);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void sensitiveInputNeverAppearsInErrorsOrApplicationLogs(CapturedOutput logs) throws Exception {
        String email = "secret-" + UUID.randomUUID() + "@fixture.test";
        String externalId = "private-" + UUID.randomUUID();
        ingest(email, "HARD_BOUNCE", externalId, "5.1.1");
        fixedError(send("POST", "/api/delivery-events", event(email, "SOFT_BOUNCE", externalId, "4.2.1"), KEY), 409);
        fixedError(send("POST", "/api/delivery-evidence/lookup", "{\"email\":\"" + email + "\",\"secret\":1}", KEY), 400);
        assertFalse(logs.toString().contains(email));
        assertFalse(logs.toString().contains(externalId));
    }

    private UUID ingest(String email, String type, String externalId, String code) throws Exception {
        HttpResponse<String> response = send("POST", "/api/delivery-events", event(email, type, externalId, code), KEY);
        assertEquals(201, response.statusCode());
        noStore(response);
        return UUID.fromString(JSON.readTree(response.body()).get("id").asText());
    }

    private JsonNode lookup(String email) throws Exception {
        HttpResponse<String> response = send("POST", "/api/delivery-evidence/lookup",
                JSON.writeValueAsString(Map.of("email", email)), KEY);
        assertEquals(200, response.statusCode());
        noStore(response);
        return JSON.readTree(response.body());
    }

    private static String event(String email, String type, String id, String code) throws Exception {
        String instant = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS).toString();
        return "{\"email\":\"" + email + "\",\"eventType\":\"" + type
                + "\",\"externalEventId\":\"" + id + "\",\"occurredAt\":\"" + instant + "\""
                + (code == null ? "}" : ",\"enhancedStatusCode\":\"" + code + "\"}");
    }

    private void restart(String configuredKey) {
        if (api != null) api.close();
        api = new SpringApplicationBuilder(MailGuardApplication.class).run("--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--jobs.api-key=" + configuredKey, "--delivery.source-id=" + SOURCE,
                "--jobs.worker.enabled=false");
        port = api.getEnvironment().getProperty("local.server.port", Integer.class);
        jdbc = api.getBean(JdbcTemplate.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (key != null) request.header("X-Job-API-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void fixedError(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        noStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertEquals(switch (status) {
            case 400 -> "Invalid request";
            case 401 -> "Unauthorized";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 413 -> "Request too large";
            default -> throw new AssertionError("Unexpected test status");
        }, body.get("message").asText());
        assertTrue(List.of("/api/delivery-events", "/api/delivery-evidence/lookup", "/api/suppressions")
                .contains(body.get("path").asText()));
        assertFalse(response.body().contains(ADDRESS));
        assertFalse(response.body().contains("synthetic-admin-source"));
    }

    private static void noStore(HttpResponse<String> response) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
    }
}
