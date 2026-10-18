package com.aash.mailguard;

import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.imports.BulkImportStore;
import com.aash.mailguard.imports.BulkImportStore.InputRow;
import com.aash.mailguard.imports.BulkImportStore.Progress;
import com.aash.mailguard.jobs.JobStore;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "jobs.worker.enabled=false")
class BulkImportStoreTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired BulkImportStore bulk;
    @Autowired JobStore jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM email_validation_history WHERE job_id IS NOT NULL");
        jdbc.update("DELETE FROM import_row");
        jdbc.update("DELETE FROM import_batch");
        jdbc.update("DELETE FROM verification_job");
    }

    @Test
    void failedBatchKeepsStagedRowsButNeverPromotesThem() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "first-invalid", null),
                new InputRow(2, "first-invalid", null), new InputRow(3, null, "EMPTY_EMAIL")));
        bulk.fail(id, "INVALID_CSV");
        assertEquals("FAILED", bulk.find(id).orElseThrow().state());
        assertEquals(3, bulk.find(id).orElseThrow().inputRows());
        assertEquals(1, bulk.find(id).orElseThrow().duplicateRows());
        assertEquals(1, bulk.find(id).orElseThrow().rejectedRows());
        assertEquals(0, bulk.promote(10));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
        assertEquals(3, bulk.results(id, 0, 10).size());
    }

    @Test
    void boundedAppendTransactionRollsBackAnInvalidBatchAndAllowsRetry() {
        UUID id = bulk.begin();
        assertThrows(DataAccessException.class, () -> bulk.append(id,
                List.of(new InputRow(1, "same-invalid", null), new InputRow(1, "other-invalid", null))));
        assertEquals(0, bulk.find(id).orElseThrow().inputRows());
        bulk.append(id, List.of(new InputRow(1, "same-invalid", null)));
        bulk.finish(id);
        assertEquals(1, bulk.promote(10));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
    }

    @Test
    void unfinalizedImportNeverRunsAndStaleInterruptedIngestionFailsWithoutLosingRows() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "interrupted-invalid", null)));
        assertEquals(0, bulk.promote(10));
        assertEquals("INGESTING", bulk.find(id).orElseThrow().state());
        UUID recent = bulk.begin();
        jdbc.update("UPDATE import_batch SET updated_at = clock_timestamp() - interval '59 minutes' WHERE id = ?", recent);
        jdbc.update("UPDATE import_batch SET updated_at = clock_timestamp() - interval '61 minutes' WHERE id = ?", id);
        bulk.promote(10);
        assertEquals("FAILED", bulk.find(id).orElseThrow().state());
        assertEquals("INGEST_INTERRUPTED", bulk.find(id).orElseThrow().failureCode());
        assertEquals("INGESTING", bulk.find(recent).orElseThrow().state());
        assertEquals(1, bulk.find(id).orElseThrow().inputRows());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
    }

    @Test
    void terminalBatchesCannotBeChangedByDirectSql() {
        UUID id = bulk.begin();
        bulk.finish(id);
        assertEquals("COMPLETED", bulk.find(id).orElseThrow().state());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE import_batch SET state = 'INGESTING', finished_at = NULL WHERE id = ?", id));
        assertEquals("COMPLETED", bulk.find(id).orElseThrow().state());
    }

    @Test
    void duplicateMustReferenceAnEarlierRow() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(2, "first-invalid", null)));
        for (long reference : List.of(1L, 2L)) {
            DataAccessException error = assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO import_row(import_id, ordinal, row_state, duplicate_of, reason)
                    VALUES (?, 1, 'DUPLICATE', ?, 'DUPLICATE')
                    """, id, reference));
            assertEquals("23514", ((SQLException) error.getMostSpecificCause()).getSQLState());
        }
    }

    @Test
    void terminalProgressMatchesRowOutcomesWithoutReadingLargeTables() throws Exception {
        UUID completed = bulk.begin();
        bulk.append(completed, List.of(new InputRow(1, "first-invalid", null),
                new InputRow(2, "first-invalid", null), new InputRow(3, null, "EMPTY_EMAIL")));
        bulk.finish(completed);
        bulk.promote(10);
        assertTrue(jobs.complete(jobs.claim("summary", 1, Duration.ofSeconds(30)).getFirst(),
                response("first-invalid")));
        bulk.promote(10);
        Progress done = bulk.find(completed).orElseThrow();
        assertEquals("COMPLETED", done.state());
        assertEquals(3, done.inputRows());
        assertEquals(1, done.acceptedRows());
        assertEquals(1, done.duplicateRows());
        assertEquals(1, done.rejectedRows());
        assertEquals(1, done.completedRows());

        UUID failed = bulk.begin();
        bulk.append(failed, List.of(new InputRow(1, "failed-invalid", null),
                new InputRow(2, "failed-invalid", null)));
        bulk.fail(failed, "INVALID_CSV");
        Progress failure = bulk.find(failed).orElseThrow();
        assertEquals("FAILED", failure.state());
        assertEquals(2, failure.inputRows());
        assertEquals(1, failure.acceptedRows());
        assertEquals(1, failure.duplicateRows());
        assertEquals(0, failure.pendingRows());

        UUID cancelled = bulk.begin();
        bulk.append(cancelled, List.of(new InputRow(1, "cancel-invalid", null),
                new InputRow(2, "cancel-invalid", null)));
        bulk.finish(cancelled);
        assertTrue(bulk.cancel(cancelled));
        Progress cancellation = bulk.find(cancelled).orElseThrow();
        assertEquals("CANCELLED", cancellation.state());
        assertEquals(2, cancellation.inputRows());
        assertEquals(0, cancellation.acceptedRows());
        assertEquals(1, cancellation.duplicateRows());
        assertEquals(1, cancellation.cancelledRows());

        try (var blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE import_row, verification_job IN ACCESS EXCLUSIVE MODE");
            }
            new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                assertEquals(done, bulk.find(completed).orElseThrow());
                assertEquals(failure, bulk.find(failed).orElseThrow());
                assertEquals(cancellation, bulk.find(cancelled).orElseThrow());
                return null;
            });
        }
    }

    @Test
    void cancelPromotedJobFencesActiveWorkerAndPreservesDuplicateRowIdentity() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "same-invalid", null), new InputRow(2, "same-invalid", null)));
        bulk.finish(id);
        assertEquals(1, bulk.promote(10));
        JobStore.Claim claim = jobs.claim("synthetic-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertEquals("RUNNING", jobs.find(claim.id()).orElseThrow().state());
        assertTrue(bulk.cancel(id));
        assertFalse(jobs.heartbeat(claim, Duration.ofSeconds(30)));
        assertFalse(jobs.complete(claim, response("same-invalid")));
        assertEquals("CANCELLED", bulk.find(id).orElseThrow().state());
        assertEquals("CANCELLED", jobs.find(claim.id()).orElseThrow().state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, claim.id()));
        assertEquals(1L, bulk.results(id, 0, 10).get(1).duplicateOf());
        assertEquals(1, bulk.recentMetrics().rowsCancelled());
        assertTrue(bulk.cancel(id));
    }

    @Test
    void uniqueJobCompletesOnceAndDuplicateResultsFollowFirstOrdinal() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "same-invalid", null), new InputRow(2, "same-invalid", null)));
        bulk.finish(id);
        assertEquals(1, bulk.promote(10));
        JobStore.Claim claim = jobs.claim("synthetic-worker", 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(jobs.complete(claim, response("same-invalid")));
        assertFalse(jobs.complete(claim, response("same-invalid")));
        bulk.promote(10);
        assertEquals("COMPLETED", bulk.find(id).orElseThrow().state());
        assertEquals(1, bulk.find(id).orElseThrow().completedRows());
        assertEquals("INVALID", bulk.results(id, 0, 2).get(1).resultStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM import_row "
                + "WHERE import_id = ? AND row_state = 'DUPLICATE' AND email IS NOT NULL", Integer.class, id));
        assertEquals(1, bulk.recentMetrics().rowsCompleted());
        assertTrue(bulk.recentMetrics().promotionLagMillis() >= 0);
        assertFalse(bulk.cancel(id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, claim.id()));
    }

    @Test
    void retryAndExhaustionRemainDistinctFromInvalidClassificationInProgress() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "retry@fixture.test", null)));
        bulk.finish(id);
        bulk.promote(10);
        for (int attempt = 1; attempt <= 3; attempt++) {
            JobStore.Claim claim = jobs.claim("synthetic-worker", 1, Duration.ofSeconds(30)).getFirst();
            assertTrue(jobs.retry(claim, Duration.ofSeconds(30), "DNS_TEMPORARY"));
            assertEquals(attempt < 3 ? 1 : 0, bulk.find(id).orElseThrow().retryRows());
            if (attempt < 3) {
                jdbc.update("UPDATE verification_job SET due_at = clock_timestamp() - interval '1 second' WHERE id = ?", claim.id());
            }
        }
        bulk.promote(10);
        assertEquals("COMPLETED", bulk.find(id).orElseThrow().state());
        assertEquals(1, bulk.find(id).orElseThrow().failedRows());
        assertEquals(1, bulk.recentMetrics().rowsFailed());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id IS NOT NULL", Integer.class));
    }

    @Test
    void promotionFailureRollsBackJobAndRowLinkSoAnotherPromoterCanRetry() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "recover-invalid", null)));
        bulk.finish(id);
        jdbc.execute("CREATE FUNCTION fail_import_job_link() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER import_link_failure BEFORE UPDATE OF job_id ON import_row "
                + "FOR EACH ROW WHEN (NEW.job_id IS NOT NULL) EXECUTE FUNCTION fail_import_job_link()");
        try {
            assertThrows(DataAccessException.class, () -> bulk.promote(1));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM import_row WHERE job_id IS NOT NULL", Integer.class));
            assertEquals("READY", bulk.find(id).orElseThrow().state());
        } finally {
            jdbc.execute("DROP TRIGGER import_link_failure ON import_row");
            jdbc.execute("DROP FUNCTION fail_import_job_link()");
        }
        assertEquals(1, bulk.promote(1));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM verification_job", Integer.class));
    }

    @Test
    void expiredImportedClaimCannotOverwriteFreshResultOrDuplicateHistory() {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "reclaimed-invalid", null)));
        bulk.finish(id);
        bulk.promote(1);
        JobStore.Claim old = jobs.claim("abandoned", 1, Duration.ofSeconds(30)).getFirst();
        jdbc.update("UPDATE verification_job SET lease_expires_at = clock_timestamp() - interval '1 second' "
                + "WHERE id = ?", old.id());
        JobStore.Claim replacement = jobs.claim("replacement", 1, Duration.ofSeconds(30)).getFirst();
        assertEquals(old.token() + 1, replacement.token());
        assertFalse(jobs.complete(old, response("reclaimed-invalid")));
        assertTrue(jobs.complete(replacement, response("reclaimed-invalid")));
        bulk.promote(1);
        assertEquals(1, bulk.find(id).orElseThrow().completedRows());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                Integer.class, replacement.id()));
    }

    @Test
    void cancellationAndCompletionRaceKeepsOnlyTheCommittedWinner() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 5; round++) {
                String email = "completion-race-" + round;
                UUID id = bulk.begin();
                bulk.append(id, List.of(new InputRow(1, email, null)));
                bulk.finish(id);
                bulk.promote(1);
                JobStore.Claim claim = jobs.claim("race-worker", 1, Duration.ofSeconds(30)).getFirst();
                CountDownLatch go = new CountDownLatch(1);
                var cancelled = pool.submit(() -> { go.await(); return bulk.cancel(id); });
                var completed = pool.submit(() -> { go.await(); return jobs.complete(claim, response(email)); });
                go.countDown();
                boolean cancelledFirst = cancelled.get(10, TimeUnit.SECONDS);
                boolean completedFirst = completed.get(10, TimeUnit.SECONDS);
                assertTrue(cancelledFirst != completedFirst);
                assertEquals(completedFirst ? 1 : 0, jdbc.queryForObject(
                        "SELECT count(*) FROM email_validation_history WHERE job_id = ?", Integer.class, claim.id()));
                assertEquals(completedFirst ? "COMPLETED" : "CANCELLED",
                        bulk.find(id).orElseThrow().state());
            }
        }
    }

    @Test
    void concurrentPromotionAndCancellationHaveOnlyOneTerminalJobOutcome() throws Exception {
        UUID id = bulk.begin();
        bulk.append(id, List.of(new InputRow(1, "race-invalid", null)));
        bulk.finish(id);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var promotion = pool.submit(() -> { go.await(); return bulk.promote(1); });
            var cancellation = pool.submit(() -> { go.await(); return bulk.cancel(id); });
            go.countDown();
            assertTrue(cancellation.get(10, TimeUnit.SECONDS));
            int promoted = promotion.get(10, TimeUnit.SECONDS);
            assertTrue(promoted == 0 || promoted == 1);
            assertEquals("CANCELLED", bulk.find(id).orElseThrow().state());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job WHERE state != 'CANCELLED'", Integer.class));
            assertEquals(0, bulk.promote(10));
        }
    }

    private static EmailValidationResponse response(String email) {
        EmailValidationResponse result = new EmailValidationResponse();
        result.setEmail(email);
        result.setFormatValid(false);
        result.setStatus("INVALID");
        result.setMessage("Invalid format");
        return result;
    }
}
