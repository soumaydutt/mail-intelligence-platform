package com.aash.mailguard;

import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.jobs.JobStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jobs.worker.enabled=false", "jobs.max-attempts=3"
})
class JobStoreTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JobStore jobs;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearJobs() {
        jdbc.update("DELETE FROM email_validation_history WHERE job_id IS NOT NULL");
        jdbc.update("DELETE FROM verification_job");
    }

    @Test
    void concurrentClaimsOwnDistinctRowsAndOnlyDueOrExpiredLeasesCanRun() throws Exception {
        UUID first = jobs.submit("first@fixture.test", null).id();
        UUID second = jobs.submit("second@fixture.test", null).id();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<JobStore.Claim>> a = pool.submit(() -> { gate.await(); return jobs.claim("worker-a", 1, Duration.ofSeconds(30)); });
            Future<List<JobStore.Claim>> b = pool.submit(() -> { gate.await(); return jobs.claim("worker-b", 1, Duration.ofSeconds(30)); });
            gate.countDown();
            var claims = List.of(a.get(10, TimeUnit.SECONDS).getFirst(), b.get(10, TimeUnit.SECONDS).getFirst());
            assertEquals(Set.of(first, second), Set.of(claims.get(0).id(), claims.get(1).id()));
            assertTrue(jobs.claim("third", 2, Duration.ofSeconds(30)).isEmpty());
            assertEquals(1, claims.get(0).token());
            assertEquals(1, claims.get(1).token());
        }
    }

    @Test
    void expiredLeaseIsReclaimedWithNewFenceAndOldWriterCannotCompleteOrRenew() {
        UUID id = jobs.submit("lease@fixture.test", null).id();
        JobStore.Claim old = jobs.claim("worker-a", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(jobs.heartbeat(old, Duration.ofSeconds(30)));
        jdbc.update("UPDATE verification_job SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
        assertFalse(jobs.heartbeat(old, Duration.ofSeconds(30)));
        JobStore.Claim fresh = jobs.claim("worker-b", 1, Duration.ofSeconds(30)).getFirst();
        assertEquals(old.token() + 1, fresh.token());
        assertEquals(old.attempts() + 1, fresh.attempts());
        assertFalse(jobs.complete(old, response("lease@fixture.test")));
        assertTrue(jobs.complete(fresh, response("lease@fixture.test")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));
        assertFalse(jobs.complete(fresh, response("lease@fixture.test")));
        assertEquals("COMPLETED", jobs.find(id).orElseThrow().state());
    }

    @Test
    void completionAndHistoryRollBackTogetherOnDatabaseConstraintFailure() {
        UUID id = jobs.submit("atomic@fixture.test", null).id();
        JobStore.Claim claim = jobs.claim("atomic-worker", 1, Duration.ofSeconds(30)).getFirst();
        EmailValidationResponse oversized = response("atomic@fixture.test");
        oversized.setMessage("x".repeat(256));
        assertThrows(DataAccessException.class, () -> jobs.complete(claim, oversized));
        assertEquals("RUNNING", jobs.find(id).orElseThrow().state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));
        assertTrue(jobs.complete(claim, response("atomic@fixture.test")));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE verification_job SET state = 'PENDING' WHERE id = ?", id));
    }

    @Test
    void retryDueTimeSurvivesASeparateStoreReadAndExhaustionNeverFabricatesInvalidHistory() {
        UUID id = jobs.submit("retry@fixture.test", null).id();
        for (int attempt = 1; attempt <= 3; attempt++) {
            JobStore.Claim claim = jobs.claim("retry-worker", 1, Duration.ofSeconds(30)).getFirst();
            assertEquals(attempt, claim.attempts());
            assertTrue(jobs.retry(claim, Duration.ofMinutes(1), "DNS_TEMPORARY"));
            if (attempt < 3) {
                assertEquals("RETRY", jobs.find(id).orElseThrow().state());
                assertNotNull(jobs.find(id).orElseThrow().dueAt());
                assertTrue(jobs.claim("early-worker", 1, Duration.ofSeconds(30)).isEmpty());
                jdbc.update("UPDATE verification_job SET due_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
            }
        }
        assertEquals("FAILED", jobs.find(id).orElseThrow().state());
        assertEquals(3, jobs.find(id).orElseThrow().attempts());
        assertTrue(jobs.claim("late-worker", 1, Duration.ofSeconds(30)).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));
    }

    @Test
    void identicalIdempotencyKeyReusesOneJobAcrossConcurrentSubmissionsAndRejectsChangedPayload() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JobStore.Submission> a = pool.submit(() -> { gate.await(); return jobs.submit("same@fixture.test", "request-key"); });
            Future<JobStore.Submission> b = pool.submit(() -> { gate.await(); return jobs.submit("same@fixture.test", "request-key"); });
            gate.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS).id(), b.get(10, TimeUnit.SECONDS).id());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM verification_job WHERE idempotency_key = ?", Integer.class, "request-key"));
        assertThrows(JobStore.IdempotencyConflict.class, () -> jobs.submit("changed@fixture.test", "request-key"));
        assertNotEquals(jobs.submit("same@fixture.test", null).id(), jobs.submit("same@fixture.test", null).id());
    }

    @Test
    void cancellingPendingAndRunningJobsPreventsLateCompletionAndIsTerminal() {
        UUID pending = jobs.submit("pending@fixture.test", null).id();
        UUID running = jobs.submit("running@fixture.test", null).id();
        assertTrue(jobs.cancel(pending));
        assertEquals("CANCELLED", jobs.find(pending).orElseThrow().state());
        JobStore.Claim claim = jobs.claim("cancel-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertEquals(running, claim.id());
        assertTrue(jobs.cancel(running));
        assertFalse(jobs.heartbeat(claim, Duration.ofSeconds(30)));
        assertFalse(jobs.complete(claim, response("running@fixture.test")));
        assertEquals("CANCELLED", jobs.find(running).orElseThrow().state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id IS NOT NULL", Integer.class));
        assertTrue(jobs.cancel(pending));
    }

    @Test
    void unexpiredHeartbeatCannotBeStolenEvenWithCompetingWorkers() {
        UUID id = jobs.submit("heartbeat@fixture.test", null).id();
        JobStore.Claim claim = jobs.claim("owner", 1, Duration.ofSeconds(6)).getFirst();
        assertTrue(jobs.heartbeat(claim, Duration.ofSeconds(30)));
        assertTrue(jobs.claim("competitor", 1, Duration.ofSeconds(30)).isEmpty());
        assertEquals(1, jobs.find(id).orElseThrow().attempts());
        assertFalse(jobs.fail(new JobStore.Claim(id, claim.email(), "impostor", claim.token(), claim.attempts(), claim.maxAttempts()),
                "VALIDATION_ERROR"));
        assertTrue(jobs.complete(claim, response("heartbeat@fixture.test")));
    }

    @Test
    void separateApplicationContextsCompeteForOneJobWithoutDuplicateClaim() throws Exception {
        UUID id = jobs.submit("shared@fixture.test", null).id();
        try (ConfigurableApplicationContext other = new SpringApplicationBuilder(MailGuardApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--jobs.worker.enabled=false")) {
            JobStore second = other.getBean(JobStore.class);
            CountDownLatch go = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<JobStore.Claim>> left = pool.submit(
                        () -> { go.await(); return jobs.claim("instance-one", 1, Duration.ofSeconds(30)); });
                Future<List<JobStore.Claim>> right = pool.submit(
                        () -> { go.await(); return second.claim("instance-two", 1, Duration.ofSeconds(30)); });
                go.countDown();
                List<JobStore.Claim> claims = new java.util.ArrayList<>(left.get(10, TimeUnit.SECONDS));
                claims.addAll(right.get(10, TimeUnit.SECONDS));
                assertEquals(1, claims.size());
                assertEquals(id, claims.getFirst().id());
                assertTrue(second.claim("third", 1, Duration.ofSeconds(30)).isEmpty());
            }
        }
    }

    @Test
    void persistedBacklogCapRejectsNewWorkButAllowsReplayAndFreesSpaceOnCancellation() {
        JobStore limited = new JobStore(jdbc, transactionManager, 3, 1);
        UUID id = limited.submit("admitted@fixture.test", "same-key").id();
        assertThrows(JobStore.QueueFull.class, () -> limited.submit("blocked@fixture.test", null));
        assertEquals(id, limited.submit("admitted@fixture.test", "same-key").id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
        assertTrue(limited.cancel(id));
        assertNotEquals(id, limited.submit("after@fixture.test", null).id());
    }

    @Test
    void claimCrashAndValidationWithoutCompletionRecoverWithoutPrematureHistory() {
        UUID id = jobs.submit("crash@fixture.test", null).id();
        JobStore.Claim first = jobs.claim("dead-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
        jdbc.update("UPDATE verification_job SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
        JobStore.Claim recovered = jobs.claim("replacement", 1, Duration.ofSeconds(30)).getFirst();
        assertEquals(first.token() + 1, recovered.token());
        assertFalse(jobs.complete(first, response("crash@fixture.test")));
        assertTrue(jobs.complete(recovered, response("crash@fixture.test")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
    }

    @Test
    void permanentFailureIsTerminalWithoutHistoryOrLaterClaims() {
        UUID id = jobs.submit("failure@fixture.test", null).id();
        JobStore.Claim claim = jobs.claim("failed-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(jobs.fail(claim, "VALIDATION_ERROR"));
        assertEquals("FAILED", jobs.find(id).orElseThrow().state());
        assertEquals("VALIDATION_ERROR", jobs.find(id).orElseThrow().failureCode());
        assertFalse(jobs.retry(claim, Duration.ofSeconds(1), "DNS_TEMPORARY"));
        assertTrue(jobs.claim("another-worker", 1, Duration.ofSeconds(30)).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
    }

    @Test
    void terminalJobsAndUnknownIdsCannotReportSuccessfulCancellation() {
        UUID completed = jobs.submit("completed@fixture.test", null).id();
        JobStore.Claim completedClaim = jobs.claim("completed-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(jobs.complete(completedClaim, response("completed@fixture.test")));
        UUID failed = jobs.submit("failed@fixture.test", null).id();
        JobStore.Claim failedClaim = jobs.claim("failed-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(jobs.fail(failedClaim, "VALIDATION_ERROR"));
        var completedBefore = jobs.find(completed).orElseThrow();
        var failedBefore = jobs.find(failed).orElseThrow();

        assertFalse(jobs.cancel(completed));
        assertFalse(jobs.cancel(failed));
        UUID unknown = UUID.randomUUID();
        assertFalse(jobs.cancel(unknown));
        assertTrue(jobs.find(unknown).isEmpty());
        assertEquals("COMPLETED", jobs.find(completed).orElseThrow().state());
        assertEquals(completedBefore.finishedAt(), jobs.find(completed).orElseThrow().finishedAt());
        assertEquals("UNVERIFIED", jobs.find(completed).orElseThrow().result().getStatus());
        assertEquals("FAILED", jobs.find(failed).orElseThrow().state());
        assertEquals(failedBefore.finishedAt(), jobs.find(failed).orElseThrow().finishedAt());
        assertEquals("VALIDATION_ERROR", jobs.find(failed).orElseThrow().failureCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, completed));
        assertEquals("completed@fixture.test", jdbc.queryForObject(
                "SELECT email FROM email_validation_history WHERE job_id = ?", String.class, completed));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, failed));
    }

    @Test
    void cancellingRetryClearsFailureAndRepeatedCancellationKeepsTheTerminalRow() {
        UUID id = jobs.submit("retry-cancel@fixture.test", null).id();
        JobStore.Claim claim = jobs.claim("retry-owner", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(jobs.retry(claim, Duration.ofHours(1), "DNS_TEMPORARY"));
        assertEquals("RETRY", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, id));
        assertTrue(jobs.cancel(id));
        var cancelled = jobs.find(id).orElseThrow();
        assertEquals("CANCELLED", cancelled.state());
        assertTrue(jobs.cancel(id));
        assertEquals(cancelled.finishedAt(), jobs.find(id).orElseThrow().finishedAt());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, id));
        assertNull(jdbc.queryForObject(
                "SELECT failure_code FROM verification_job WHERE id = ?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
    }

    @Test
    void concurrentCancellationRequestsBothSucceedWithoutRewritingTheJob() throws Exception {
        UUID id = jobs.submit("concurrent-cancel@fixture.test", null).id();
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = pool.submit(() -> { go.await(); return jobs.cancel(id); });
            Future<Boolean> second = pool.submit(() -> { go.await(); return jobs.cancel(id); });
            go.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        var cancelled = jobs.find(id).orElseThrow();
        assertEquals("CANCELLED", cancelled.state());
        assertTrue(jobs.cancel(id));
        assertEquals(cancelled.finishedAt(), jobs.find(id).orElseThrow().finishedAt());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM verification_job WHERE id = ?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, id));
    }

    @Test
    void cancellationAndCompletionRaceHasOnlyOneWinnerAndOnePossibleHistoryRow() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 12; round++) {
                String email = "race" + round + "@fixture.test";
                UUID id = jobs.submit(email, null).id();
                JobStore.Claim claim = jobs.claim("race-owner", 1, Duration.ofSeconds(30)).getFirst();
                CountDownLatch go = new CountDownLatch(1);
                Future<Boolean> cancelled = pool.submit(() -> { go.await(); return jobs.cancel(id); });
                Future<Boolean> completed = pool.submit(() -> { go.await(); return jobs.complete(claim, response(email)); });
                go.countDown();
                boolean cancelWon = cancelled.get(10, TimeUnit.SECONDS);
                boolean completeWon = completed.get(10, TimeUnit.SECONDS);
                assertTrue(cancelWon != completeWon);
                String terminal = cancelWon ? "CANCELLED" : "COMPLETED";
                assertEquals(terminal, jdbc.queryForObject(
                        "SELECT state FROM verification_job WHERE id = ?", String.class, id));
                assertEquals(completeWon ? 1 : 0, jdbc.queryForObject(
                        "SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, id));
                assertEquals(cancelWon, jobs.cancel(id));
                assertEquals(terminal, jobs.find(id).orElseThrow().state());
            }
        }
    }

    @Test
    void keyedIdentityIsByteExactForBothDomainAndLocalPartCase() {
        UUID id = jobs.submit("User@Example.test", "case-sensitive-request").id();
        assertEquals(id, jobs.submit("User@Example.test", "case-sensitive-request").id());
        assertThrows(JobStore.IdempotencyConflict.class,
                () -> jobs.submit("User@example.test", "case-sensitive-request"));
        assertThrows(JobStore.IdempotencyConflict.class,
                () -> jobs.submit("user@Example.test", "case-sensitive-request"));
        assertEquals("User@Example.test", jdbc.queryForObject(
                "SELECT email FROM verification_job WHERE id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM verification_job WHERE idempotency_key = ?",
                Integer.class, "case-sensitive-request"));
    }

    private static EmailValidationResponse response(String email) {
        EmailValidationResponse result = new EmailValidationResponse();
        result.setEmail(email);
        result.setDomain("fixture.test");
        result.setFormatValid(true);
        result.setMxRecordFound(true);
        result.setStatus("UNVERIFIED");
        result.setMessage("MX record observed; mailbox not verified.");
        return result;
    }
}
