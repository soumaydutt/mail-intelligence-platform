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
