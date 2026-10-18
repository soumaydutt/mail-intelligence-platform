package com.aash.mailguard;

import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.dns.DnsObservation;
import com.aash.mailguard.jobs.JobStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
class PostgresJobsHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "synthetic-only-test-key";
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private ConfigurableApplicationContext api;
    private ConfigurableApplicationContext worker;
    private JdbcTemplate jdbc;
    private HttpClient http;
    private int port;

    @BeforeEach
    void startApi() {
        http = HttpClient.newHttpClient();
        api = start(false);
        jdbc = api.getBean(JdbcTemplate.class);
        jdbc.update("DELETE FROM email_validation_history WHERE job_id IS NOT NULL");
        jdbc.update("DELETE FROM verification_job");
    }

    @AfterEach
    void stopApplications() {
        if (worker != null) worker.close();
        if (api != null) api.close();
        http.close();
    }

    @Test
    void submissionSurvivesActualApiRestartAndIndependentWorkerCompletesInvalidSyntaxOnce() throws Exception {
        JsonNode accepted = submit("invalid-address", null);
        UUID id = UUID.fromString(accepted.get("id").asText());
        assertEquals("PENDING", get(id).get("state").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));

        api.close();
        api = start(false);
        assertEquals("PENDING", get(id).get("state").asText());
        worker = start(true);
        JsonNode result = awaitState(id, "COMPLETED");
        assertEquals("INVALID", result.get("result").get("status").asText());
        assertFalse(result.get("result").get("formatValid").booleanValue());
        assertEquals(Set.of("email", "domain", "formatValid", "mxRecordFound", "smtpVerified", "status", "message"),
                JSON.convertValue(result.get("result"), Map.class).keySet());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));
        assertEquals("MailGuard API Working", post("/api/email/test", "", null, null).body());
    }

    @Test
    void validDnsResultKeepsSevenFieldContractAndNeverProbesSmtp() throws Exception {
        worker = start(true);
        DnsLookupService dns = worker.getBean(DnsLookupService.class);
        when(dns.lookup("fixture.test")).thenReturn(observation());
        UUID id = UUID.fromString(submit("user@fixture.test", null).get("id").asText());
        JsonNode result = awaitState(id, "COMPLETED").get("result");
        assertEquals("UNVERIFIED", result.get("status").asText());
        assertTrue(result.get("mxRecordFound").booleanValue());
        assertTrue(result.get("smtpVerified").isNull());
        assertEquals("user@fixture.test", result.get("email").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));
        Mockito.verify(dns).lookup("fixture.test");
    }

    @Test
    void retryDueTimePersistsAcrossWorkerRestartAndOnlyFinalResultCreatesHistory() throws Exception {
        worker = start(true);
        when(worker.getBean(DnsLookupService.class).lookup("retry.test")).thenReturn(
                new DnsObservation(DnsObservation.Outcome.UNKNOWN, DnsObservation.Reason.DNS_TEMPORARY_FAILURE,
                        "retry.test", List.of(), true, Instant.parse("2026-01-01T00:00:00Z")));
        UUID id = UUID.fromString(submit("user@retry.test", null).get("id").asText());
        JsonNode retry = awaitState(id, "RETRY");
        assertEquals(1, retry.get("attempts").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
        jdbc.update("UPDATE verification_job SET due_at = clock_timestamp() + interval '1 minute' WHERE id = ?", id);
        worker.close();
        worker = start(true);
        assertEquals("RETRY", get(id).get("state").asText());
        when(worker.getBean(DnsLookupService.class).lookup("retry.test")).thenReturn(
                new DnsObservation(DnsObservation.Outcome.MAIL_ROUTE, DnsObservation.Reason.MX_PRESENT,
                        "retry.test", List.of(new DnsObservation.MxHost(10, "mx.retry.test")), false,
                        Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("UPDATE verification_job SET due_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
        JsonNode completed = awaitState(id, "COMPLETED");
        assertEquals(2, completed.get("attempts").asInt());
        assertEquals("UNVERIFIED", completed.get("result").get("status").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
    }

    @Test
    void malformedOversizedUnknownAndUnauthenticatedRequestsNeverWriteJobsOrExposeDetails() throws Exception {
        HttpResponse<String> unauthorized = post("/api/jobs", "{\"email\":\"secret@fixture.test\"}", null, null);
        assertEquals(401, unauthorized.statusCode());
        assertFalse(unauthorized.body().contains("secret@fixture.test"));
        assertEquals(400, post("/api/jobs", "{\"email\":", KEY, null).statusCode());
        assertEquals(400, post("/api/jobs", JSON.writeValueAsString(Map.of("email", "x".repeat(256))), KEY, null).statusCode());
        assertEquals(400, post("/api/jobs", "{}", KEY, null).statusCode());
        assertEquals(400, post("/api/jobs", "{\"padding\":\"" + "x".repeat(9000) + "\"}", KEY, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));

        HttpResponse<String> malformed = send("GET", "/api/jobs/private%40fixture.test", null, KEY, null);
        assertEquals(400, malformed.statusCode());
        assertEquals("/api/jobs", JSON.readTree(malformed.body()).get("path").asText());
        assertFalse(malformed.body().contains("private"));
        assertEquals(404, send("GET", "/api/jobs/" + UUID.randomUUID(), null, KEY, null).statusCode());
    }

    @Test
    void concurrentSameKeySubmissionsReplayAcrossRestartAndChangedPayloadConflicts() throws Exception {
        JsonNode first = submit("one@fixture.test", "key-1");
        JsonNode replay = submit("one@fixture.test", "key-1");
        assertEquals(first.get("id"), replay.get("id"));
        api.close();
        api = start(false);
        assertEquals(first.get("id"), submit("one@fixture.test", "key-1").get("id"));
        HttpResponse<String> conflict = post("/api/jobs", "{\"email\":\"other@fixture.test\"}", KEY, "key-1");
        assertEquals(409, conflict.statusCode());
        assertFalse(conflict.body().contains("other@fixture.test"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
    }

    @Test
    void fullBacklogReturns429UntilCancellationAndStillReplaysTheSameRequest() throws Exception {
        api.close();
        api = start(false, 1);
        JsonNode first = submit("admitted@fixture.test", "admission-key");
        HttpResponse<String> full = post("/api/jobs", "{\"email\":\"blocked@fixture.test\"}", KEY, null);
        assertEquals(429, full.statusCode());
        assertFalse(full.body().contains("blocked@fixture.test"));
        assertEquals(first.get("id"), submit("admitted@fixture.test", "admission-key").get("id"));
        UUID id = UUID.fromString(first.get("id").asText());
        assertEquals("CANCELLED", JSON.readTree(send("DELETE", "/api/jobs/" + id, null, KEY, null).body())
                .get("state").asText());
        assertFalse(submit("allowed@fixture.test", null).get("id").asText().equals(first.get("id").asText()));
    }

    @Test
    void cancellationDuringDnsCannotPublishHistoryEvenIfComputationFinishesLater() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID pending = UUID.fromString(submit("pending@fixture.test", null).get("id").asText());
        HttpResponse<String> pendingCancellation = send("DELETE", "/api/jobs/" + pending, null, KEY, null);
        assertEquals(200, pendingCancellation.statusCode());
        assertEquals("CANCELLED", JSON.readTree(pendingCancellation.body()).get("state").asText());
        worker = start(true);
        DnsLookupService dns = worker.getBean(DnsLookupService.class);
        when(dns.lookup("fixture.test")).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return observation();
        });
        UUID running = UUID.fromString(submit("running@fixture.test", null).get("id").asText());
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("RUNNING", get(running).get("state").asText());
            HttpResponse<String> runningCancellation = send("DELETE", "/api/jobs/" + running, null, KEY, null);
            assertEquals(200, runningCancellation.statusCode());
            assertEquals("CANCELLED", JSON.readTree(runningCancellation.body()).get("state").asText());
        } finally {
            release.countDown();
        }
        worker.close();
        worker = null;
        assertEquals("CANCELLED", get(running).get("state").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, running));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, pending));
    }

    @Test
    void cancellationHttpDistinguishesRetryIdempotenceTerminalJobsAndUnknownIds() throws Exception {
        UUID pending = UUID.fromString(submit("pending-audit", null).get("id").asText());
        HttpResponse<String> cancelled = send("DELETE", "/api/jobs/" + pending, null, KEY, null);
        assertEquals(200, cancelled.statusCode());
        JsonNode first = JSON.readTree(cancelled.body());
        assertEquals(pending.toString(), first.get("id").asText());
        assertEquals("CANCELLED", first.get("state").asText());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, pending));
        HttpResponse<String> again = send("DELETE", "/api/jobs/" + pending, null, KEY, null);
        assertEquals(200, again.statusCode());
        assertEquals(first, JSON.readTree(again.body()));

        JobStore store = api.getBean(JobStore.class);
        UUID retry = UUID.fromString(submit("retry-audit", null).get("id").asText());
        JobStore.Claim retryClaim = store.claim("http-retry-owner", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(store.retry(retryClaim, Duration.ofMinutes(1), "DNS_TEMPORARY"));
        HttpResponse<String> retriedCancellation = send("DELETE", "/api/jobs/" + retry, null, KEY, null);
        assertEquals(200, retriedCancellation.statusCode());
        assertEquals("CANCELLED", JSON.readTree(retriedCancellation.body()).get("state").asText());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, retry));

        UUID completed = UUID.fromString(submit("completed-audit", null).get("id").asText());
        worker = start(true);
        JsonNode completedBefore = awaitState(completed, "COMPLETED");
        worker.close();
        worker = null;
        HttpResponse<String> completedCancellation = send("DELETE", "/api/jobs/" + completed, null, KEY, null);
        assertEquals(409, completedCancellation.statusCode());
        JsonNode conflict = JSON.readTree(completedCancellation.body());
        assertEquals(409, conflict.get("status").asInt());
        assertEquals("Conflict", conflict.get("message").asText());
        assertEquals("/api/jobs", conflict.get("path").asText());
        assertFalse(completedCancellation.body().contains(completed.toString()));
        assertEquals(completedBefore, get(completed));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, completed));
        assertEquals("completed-audit", jdbc.queryForObject(
                "SELECT email FROM email_validation_history WHERE job_id = ?", String.class, completed));

        UUID failed = UUID.fromString(submit("failed-audit", null).get("id").asText());
        JobStore.Claim failedClaim = store.claim("http-failure-owner", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(store.fail(failedClaim, "VALIDATION_ERROR"));
        HttpResponse<String> failedCancellation = send("DELETE", "/api/jobs/" + failed, null, KEY, null);
        assertEquals(409, failedCancellation.statusCode());
        JsonNode failedConflict = JSON.readTree(failedCancellation.body());
        assertEquals(409, failedConflict.get("status").asInt());
        assertEquals("Conflict", failedConflict.get("message").asText());
        assertEquals("/api/jobs", failedConflict.get("path").asText());
        assertFalse(failedCancellation.body().contains(failed.toString()));
        assertEquals("FAILED", get(failed).get("state").asText());
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, failed));

        UUID unknown = UUID.randomUUID();
        HttpResponse<String> missing = send("DELETE", "/api/jobs/" + unknown, null, KEY, null);
        assertEquals(404, missing.statusCode());
        JsonNode missingBody = JSON.readTree(missing.body());
        assertEquals(404, missingBody.get("status").asInt());
        assertEquals("Not found", missingBody.get("message").asText());
        assertEquals("/api/jobs", missingBody.get("path").asText());
        assertFalse(missing.body().contains(unknown.toString()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job WHERE id = ?",
                Integer.class, unknown));
    }

    @Test
    void allJobMethodsRejectMissingWrongAndUnconfiguredKeysWithoutStateChanges() throws Exception {
        UUID id = UUID.fromString(submit("protected-audit", null).get("id").asText());
        for (String method : List.of("GET", "DELETE")) {
            for (String supplied : new String[] {null, "wrong-key"}) {
                HttpResponse<String> denied = send(method, "/api/jobs/" + id, null, supplied, null);
                assertEquals(401, denied.statusCode());
                JsonNode body = JSON.readTree(denied.body());
                assertEquals(401, body.get("status").asInt());
                assertEquals("Unauthorized", body.get("message").asText());
                assertEquals("/api/jobs", body.get("path").asText());
                assertFalse(denied.body().contains(id.toString()));
            }
        }
        assertEquals(401, post("/api/jobs", "{\"email\":\"protected-new\"}", "wrong-key", null).statusCode());
        assertEquals("PENDING", get(id).get("state").asText());
        api.close();
        api = start(false, 20000, 20, "");
        assertEquals(401, post("/api/jobs", "{\"email\":\"protected-new\"}", KEY, null).statusCode());
        assertEquals(401, send("GET", "/api/jobs/" + id, null, KEY, null).statusCode());
        assertEquals(401, send("DELETE", "/api/jobs/" + id, null, KEY, null).statusCode());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void jobAndIdempotencyIdentifiersStayOutOfApplicationLogs(CapturedOutput output) throws Exception {
        String email = "private-audit-" + UUID.randomUUID();
        String idempotencyKey = "private-key-" + UUID.randomUUID();
        UUID id = UUID.fromString(submit(email, idempotencyKey).get("id").asText());
        worker = start(true);
        assertEquals("INVALID", awaitState(id, "COMPLETED").get("result").get("status").asText());
        worker.close();
        worker = null;
        assertEquals(409, post("/api/jobs", "{\"email\":\"changed-audit\"}", KEY, idempotencyKey).statusCode());
        assertEquals(409, send("DELETE", "/api/jobs/" + id, null, KEY, null).statusCode());
        assertFalse(output.toString().contains(id.toString()));
        assertFalse(output.toString().contains(idempotencyKey));
        assertFalse(output.toString().contains(email));
    }

    @Test
    void workerAdmissionNeverRunsMoreThanConfiguredConcurrentDnsComputations() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        worker = start(true, 20000, 2);
        DnsLookupService dns = worker.getBean(DnsLookupService.class);
        when(dns.lookup("fixture.test")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return observation();
        });
        try {
            for (int i = 0; i < 3; i++) {
                submit("bounded" + i + "@fixture.test", null);
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM verification_job WHERE state = 'RUNNING'", Integer.class));
            UUID pending = jdbc.queryForObject(
                    "SELECT id FROM verification_job WHERE state = 'PENDING'", UUID.class);
            assertTrue(get(pending).get("result").isNull());
            assertEquals("CANCELLED", JSON.readTree(send("DELETE", "/api/jobs/" + pending, null, KEY, null).body())
                    .get("state").asText());
        } finally {
            release.countDown();
        }
        worker.close();
        worker = null;
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM email_validation_history WHERE job_id IS NOT NULL", Integer.class));
    }

    @Test
    @Timeout(30)
    void temporaryDatabaseOutageLeavesClaimRecoverableWithoutDuplicateHistory() throws Exception {
        UUID id = UUID.fromString(submit("outage-invalid", null).get("id").asText());
        JobStore.Claim abandoned = api.getBean(JobStore.class)
                .claim("outage-simulation", 1, Duration.ofSeconds(30)).getFirst();
        worker = start(true);
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            Thread.sleep(1000);
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }
        jdbc.update("UPDATE verification_job SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
        JsonNode completed = awaitState(id, "COMPLETED");
        assertEquals(abandoned.token() + 1, jdbc.queryForObject(
                "SELECT lease_token FROM verification_job WHERE id = ?", Long.class, id));
        assertEquals(2, completed.get("attempts").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
    }

    private ConfigurableApplicationContext start(boolean workerMode) {
        return start(workerMode, 20000);
    }

    private ConfigurableApplicationContext start(boolean workerMode, int maxOutstanding) {
        return start(workerMode, maxOutstanding, 20);
    }

    private ConfigurableApplicationContext start(boolean workerMode, int maxOutstanding, int concurrency) {
        return start(workerMode, maxOutstanding, concurrency, KEY);
    }

    private ConfigurableApplicationContext start(boolean workerMode, int maxOutstanding, int concurrency,
            String configuredKey) {
        ConfigurableApplicationContext app = new SpringApplicationBuilder(MailGuardApplication.class, DnsFixture.class)
                .web(workerMode ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--jobs.api-key=" + configuredKey, "--jobs.worker.enabled=" + workerMode,
                        "--jobs.max-outstanding=" + maxOutstanding,
                        "--jobs.worker.concurrency=" + concurrency,
                        "--jobs.worker.poll-millis=50", "--jobs.worker.shutdown-seconds=2");
        if (!workerMode) {
            port = app.getEnvironment().getProperty("local.server.port", Integer.class);
            jdbc = app.getBean(JdbcTemplate.class);
        }
        return app;
    }

    private JsonNode submit(String email, String key) throws Exception {
        HttpResponse<String> response = post("/api/jobs", JSON.writeValueAsString(Map.of("email", email)), KEY, key);
        assertEquals(202, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("/api/jobs/" + body.get("id").asText(), response.headers().firstValue("Location").orElseThrow());
        return body;
    }

    private JsonNode get(UUID id) throws Exception {
        HttpResponse<String> response = send("GET", "/api/jobs/" + id, null, KEY, null);
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }

    private JsonNode awaitState(UUID id, String expected) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < until) {
            JsonNode status = get(id);
            if (expected.equals(status.get("state").asText())) return status;
            Thread.sleep(50);
        }
        throw new AssertionError("Job did not reach " + expected + ": " + get(id).get("state"));
    }

    private HttpResponse<String> post(String path, String body, String key, String idempotencyKey) throws Exception {
        return send("POST", path, body, key, idempotencyKey);
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (key != null) request.header("X-Job-API-Key", key);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DnsObservation observation() {
        return new DnsObservation(DnsObservation.Outcome.MAIL_ROUTE, DnsObservation.Reason.MX_PRESENT,
                "fixture.test", List.of(new DnsObservation.MxHost(10, "mx.fixture.test")), false,
                Instant.parse("2026-01-01T00:00:00Z"));
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
