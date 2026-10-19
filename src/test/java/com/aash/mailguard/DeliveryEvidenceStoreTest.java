package com.aash.mailguard;

import com.aash.mailguard.delivery.DeliveryEvidenceService;
import com.aash.mailguard.delivery.DeliveryEvidenceService.EventInput;
import com.aash.mailguard.delivery.DeliveryEvidenceService.EventType;
import com.aash.mailguard.validator.EmailFormatValidator;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jobs.worker.enabled=false", "delivery.source-id=fixture-provider", "delivery.fresh-days=30"
})
class DeliveryEvidenceStoreTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired DeliveryEvidenceService evidence;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EmailFormatValidator validator;
    @Autowired HikariDataSource poolDataSource;

    @BeforeEach
    void clearEvidence() {
        jdbc.execute("TRUNCATE delivery_suppression, delivery_event, delivery_identity");
    }

    private EventInput event(String email, EventType type, String id, Instant when, String status) {
        return new EventInput(email, type, id, when, status);
    }

    @Test
    void fiveTypesAndStatusesHaveIndependentHistoricalAndSuppressionSemantics() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertEquals("NONE", evidence.lookup("none@example.test").freshness());
        assertFalse(evidence.lookup("none@example.test").suppressed());
        var uncertain = evidence.ingest(event("bounce@example.test", EventType.HARD_BOUNCE, "b1", now, "5.1.0"));
        assertEquals("UNCERTAIN_BOUNCE", evidence.lookup("bounce@example.test").interpretation());
        assertFalse(evidence.lookup("bounce@example.test").suppressed());
        assertEquals(0, countSuppression(uncertain.id()));
        var badStatus = evidence.ingest(event("bounce@example.test", EventType.HARD_BOUNCE, "b2", now.plus(1, ChronoUnit.SECONDS), "unknown"));
        assertEquals(0, countSuppression(badStatus.id()));
        assertNull(jdbc.queryForObject("SELECT enhanced_status_code FROM delivery_event WHERE id = ?",
                String.class, badStatus.id()));
        assertEquals(32, jdbc.queryForObject("SELECT octet_length(status_fingerprint) FROM delivery_event WHERE id = ?",
                Integer.class, badStatus.id()));
        var hard = evidence.ingest(event("bounce@example.test", EventType.HARD_BOUNCE, "b3", now.plus(2, ChronoUnit.SECONDS), "5.1.1"));
        assertEquals("RECIPIENT_HARD_BOUNCE", evidence.lookup("bounce@example.test").interpretation());
        assertTrue(evidence.lookup("bounce@example.test").suppressed());
        assertEquals(1, countSuppression(hard.id()));
        evidence.ingest(event("bounce@example.test", EventType.DELIVERED, "b4", now.plus(3, ChronoUnit.SECONDS), null));
        assertEquals("HISTORICAL_DELIVERY", evidence.lookup("bounce@example.test").interpretation());
        assertTrue(evidence.lookup("bounce@example.test").suppressed()); // Delivery never lifts a complaint or bounce.
        evidence.ingest(event("soft@example.test", EventType.SOFT_BOUNCE, "soft", now, "4.2.2"));
        assertEquals("TRANSIENT_BOUNCE", evidence.lookup("soft@example.test").interpretation());
        assertFalse(evidence.lookup("soft@example.test").suppressed());
        evidence.ingest(event("soft@example.test", EventType.SOFT_BOUNCE, "soft-other", now.plus(1, ChronoUnit.SECONDS), "5.1.1"));
        assertEquals("UNCERTAIN_BOUNCE", evidence.lookup("soft@example.test").interpretation());
        var complaint = evidence.ingest(event("complaint@example.test", EventType.COMPLAINT, "c1", now, null));
        var unsubscribed = evidence.ingest(event("unsub@example.test", EventType.UNSUBSCRIBED, "u1", now, null));
        assertTrue(evidence.lookup("complaint@example.test").suppressed());
        assertTrue(evidence.lookup("unsub@example.test").suppressed());
        assertEquals(1, countSuppression(complaint.id()));
        assertEquals(1, countSuppression(unsubscribed.id()));
        evidence.ingest(event("unsub@example.test", EventType.DELIVERED, "u2", now.plus(1, ChronoUnit.SECONDS), null));
        assertEquals("HISTORICAL_DELIVERY", evidence.lookup("unsub@example.test").interpretation());
        assertTrue(evidence.lookup("unsub@example.test").suppressed());
        assertEquals("ADMIN_JSON", evidence.lookup("complaint@example.test").sourceType());
        assertEquals("fixture-provider", evidence.lookup("complaint@example.test").sourceIdentifier());
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM delivery_event", Integer.class));
    }

    @Test
    void idempotencyRequiresExactImmutablePayloadAndRejectsInvalidInputs() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        EventInput original = event("Exact@EXAMPLE.test", EventType.HARD_BOUNCE, "same", at, "5.1.1");
        UUID id = evidence.ingest(original).id();
        assertTrue(evidence.ingest(original).reused());
        assertEquals(id, evidence.ingest(original).id());
        assertThrows(DeliveryEvidenceService.Conflict.class,
                () -> evidence.ingest(event("Exact@example.test", EventType.HARD_BOUNCE, "same", at, "5.1.1")));
        assertThrows(DeliveryEvidenceService.Conflict.class,
                () -> evidence.ingest(event("other@example.test", EventType.HARD_BOUNCE, "same", at, "5.1.1")));
        assertThrows(DeliveryEvidenceService.Conflict.class,
                () -> evidence.ingest(event("Exact@EXAMPLE.test", EventType.HARD_BOUNCE, "same", at, "5.1.0")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_event", Integer.class));
        assertEquals(1, countSuppression(id));
        EventInput malformed = event("Exact@EXAMPLE.test", EventType.HARD_BOUNCE, "malformed", at, "5.x oops");
        assertFalse(evidence.ingest(malformed).reused());
        assertTrue(evidence.ingest(malformed).reused());
        assertThrows(DeliveryEvidenceService.Conflict.class, () ->
                evidence.ingest(event("Exact@EXAMPLE.test", EventType.HARD_BOUNCE, "malformed", at, "5.y oops")));
        assertNull(jdbc.queryForObject("SELECT enhanced_status_code FROM delivery_event WHERE external_event_id = 'malformed'",
                String.class));
        assertThrows(DeliveryEvidenceService.InvalidInput.class,
                () -> evidence.ingest(event("ü@example.test", EventType.DELIVERED, "bad", at, null)));
        assertThrows(DeliveryEvidenceService.InvalidInput.class,
                () -> evidence.ingest(event("valid@example.test", EventType.DELIVERED, "future", at.plus(6, ChronoUnit.MINUTES), null)));
        assertThrows(DeliveryEvidenceService.InvalidInput.class,
                () -> evidence.ingest(event("valid@example.test", EventType.DELIVERED, "old", Instant.parse("1999-12-31T00:00:00Z"), null)));
    }

    @Test
    void databaseEnforcesImmutableProvenanceAndCompletedOnlyJobSnapshot() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = evidence.ingest(event("audit@example.test", EventType.COMPLAINT, "audit", at, null)).id();
        UUID delivered = evidence.ingest(event("audit@example.test", EventType.DELIVERED,
                "delete-without-fk", at, null)).id();
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM delivery_event WHERE id = ?", delivered));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE delivery_event SET source_identifier = 'forged' WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE delivery_event SET enhanced_status_code = '5.1.1' WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE delivery_suppression SET source_event_id = NULL WHERE source_event_id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM delivery_event WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM delivery_suppression WHERE source_event_id = ?", id));
        UUID pending = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO verification_job (id, email, request_fingerprint, result_historical_evidence)
                VALUES (?, 'audit@example.test', decode(repeat('00', 32), 'hex'), '{}'::jsonb)
                """, pending));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job WHERE id = ?", Integer.class, pending));
        DeliveryEvidenceService withoutSource = new DeliveryEvidenceService(jdbc, transactionManager, validator, "", 30);
        assertThrows(DeliveryEvidenceService.InvalidInput.class, () ->
                withoutSource.ingest(event("audit@example.test", EventType.DELIVERED, "no-source", at, null)));
    }

    @Test
    void idnDomainSharesAddressIdentityWithoutFoldingLocalPart() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = evidence.ingest(event("\"Case@Local\"@BÜCHER.Example", EventType.COMPLAINT, "unicode", at, null)).id();
        assertEquals(id, evidence.lookup("\"Case@Local\"@xn--bcher-kva.example").eventId());
        assertTrue(evidence.lookup("\"Case@Local\"@XN--BCHER-KVA.EXAMPLE").suppressed());
        assertFalse(evidence.lookup("\"case@Local\"@bücher.example").available());
        assertEquals("\"Case@Local\"@xn--bcher-kva.example", jdbc.queryForObject(
                "SELECT email_key FROM delivery_event WHERE id = ?", String.class, id));
    }

    @Test
    void correctionRevokesWithoutErasureAndOnlyLiftsItsOwnSuppression() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID bounce = evidence.ingest(event("correct@example.test", EventType.HARD_BOUNCE, "wrong", at, "5.1.1")).id();
        UUID manual = evidence.suppress("correct@example.test", "OPERATOR_BLOCK");
        assertEquals("MANUAL", jdbc.queryForObject(
                "SELECT reason FROM delivery_suppression WHERE id = ?", String.class, manual));
        assertEquals("OPERATOR_BLOCK", jdbc.queryForObject(
                "SELECT manual_reason FROM delivery_suppression WHERE id = ?", String.class, manual));
        evidence.revoke(bounce, "SOURCE_CORRECTION");
        assertEquals("SOURCE_CORRECTION", jdbc.queryForObject(
                "SELECT revocation_reason FROM delivery_event WHERE id = ?", String.class, bounce));
        assertEquals("SOURCE_CORRECTION", jdbc.queryForObject(
                "SELECT lifting_reason FROM delivery_suppression WHERE source_event_id = ?", String.class, bounce));
        assertTrue(evidence.lookup("correct@example.test").suppressed());
        assertNull(evidence.lookup("correct@example.test").eventId());
        assertTrue(evidence.ingest(event("correct@example.test", EventType.HARD_BOUNCE, "wrong", at, "5.1.1")).reused());
        assertThrows(DeliveryEvidenceService.Conflict.class, () -> evidence.revoke(bounce, "AGAIN"));
        UUID corrected = evidence.ingest(event("correct@example.test", EventType.DELIVERED, "corrected", at.plus(1, ChronoUnit.SECONDS), null)).id();
        assertEquals(corrected, evidence.lookup("correct@example.test").eventId());
        evidence.lift(manual, "OPERATOR_RELEASE");
        assertFalse(evidence.lookup("correct@example.test").suppressed());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM delivery_event", Integer.class));
        assertThrows(DeliveryEvidenceService.NotFound.class, () -> evidence.revoke(UUID.randomUUID(), "UNKNOWN"));
        assertThrows(DeliveryEvidenceService.NotFound.class, () -> evidence.lift(UUID.randomUUID(), "UNKNOWN"));
        assertThrows(DeliveryEvidenceService.Conflict.class, () -> evidence.lift(manual, "AGAIN"));
    }

    @Test
    void freshnessManualOnlySnapshotAndBoundedKeysetArePersistent() {
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        evidence.ingest(event("older@example.test", EventType.DELIVERED, "old", old, null));
        assertEquals("STALE", evidence.lookup("older@example.test").freshness());
        var extendedFreshness = new DeliveryEvidenceService(jdbc, transactionManager, validator,
                "fixture-provider", 90);
        assertEquals("FRESH", extendedFreshness.lookup("older@example.test").freshness());
        evidence.ingest(event("recent@example.test", EventType.DELIVERED, "recent",
                Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS), null));
        assertEquals("FRESH", evidence.lookup("recent@example.test").freshness());
        UUID first = evidence.suppress("manual@example.test", "OPERATOR_BLOCK");
        UUID second = evidence.suppress("manual@example.test", "REVIEW_PENDING");
        var manual = evidence.lookup("manual@example.test");
        assertTrue(manual.available());
        assertNull(manual.eventId());
        assertTrue(manual.suppressed());
        assertEquals("NONE", manual.freshness());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM delivery_suppression WHERE email_key = 'manual@example.test'", Integer.class));
        var ordered = evidence.suppressions(null, 100);
        assertTrue(ordered.size() >= 2);
        assertEquals(ordered.get(1).id(), evidence.suppressions(ordered.get(0).id(), 1).getFirst().id());
        assertTrue(ordered.stream().map(DeliveryEvidenceService.Suppression::id).toList().containsAll(Set.of(first, second)));
        assertThrows(DeliveryEvidenceService.InvalidInput.class, () -> evidence.suppressions(null, 101));
        assertThrows(DeliveryEvidenceService.InvalidInput.class, () -> evidence.suppressions(null, 0));
    }

    @Test
    void concurrentIngestAndLiftSerializeOnSameAddress() throws Exception {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID manual = evidence.suppress("race@example.test", "OPERATOR_BLOCK");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> { start.await(); return evidence.ingest(event("race@example.test", EventType.COMPLAINT, "race", at, null)); });
            var second = pool.submit(() -> { start.await(); evidence.lift(manual, "RELEASE"); return true; });
            start.countDown();
            var ingested = first.get(10, TimeUnit.SECONDS);
            assertTrue(second.get(10, TimeUnit.SECONDS));
            assertTrue(evidence.lookup("race@example.test").suppressed());
            assertEquals(1, countSuppression(ingested.id()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_suppression WHERE id = ? AND lifted_at IS NOT NULL", Integer.class, manual));
            var same = new CountDownLatch(1);
            var a = pool.submit(() -> { same.await(); return evidence.ingest(event("parallel@example.test", EventType.COMPLAINT, "repeat", at, null)); });
            var b = pool.submit(() -> { same.await(); return evidence.ingest(event("parallel@example.test", EventType.COMPLAINT, "repeat", at, null)); });
            same.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS).id(), b.get(10, TimeUnit.SECONDS).id());
            assertNotEquals(a.get().reused(), b.get().reused());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_event WHERE external_event_id = 'repeat'", Integer.class));
        }
    }

    @Test
    void indexedLatestLookupAndFreshInstallPersistAcrossSecondContext() {
        UUID id = evidence.ingest(event("restart@example.test", EventType.DELIVERED, "persist",
                Instant.now().truncatedTo(ChronoUnit.MICROS), null)).id();
        List<String> indexes = jdbc.query("""
                SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()
                  AND tablename IN ('delivery_event', 'delivery_suppression')
                """, (rs, row) -> rs.getString(1));
        assertTrue(indexes.contains("idx_delivery_event_latest"));
        assertTrue(indexes.contains("idx_delivery_suppression_active"));
        String plan = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (var rows = statement.executeQuery("""
                        EXPLAIN SELECT id FROM delivery_event
                        WHERE email_key = 'restart@example.test' AND revoked_at IS NULL
                        ORDER BY occurred_at DESC, observed_at DESC, id DESC LIMIT 1
                        """)) {
                    var lines = new StringBuilder();
                    while (rows.next()) lines.append(rows.getString(1));
                    return lines.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
            }
        });
        assertTrue(plan.contains("idx_delivery_event_latest"), plan);
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(MailGuardApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--jobs.worker.enabled=false", "--delivery.source-id=fixture-provider")) {
            var fromRestart = restarted.getBean(DeliveryEvidenceService.class);
            assertEquals(id, fromRestart.lookup("restart@example.test").eventId());
            assertTrue(fromRestart.ingest(event("restart@example.test", EventType.DELIVERED, "persist",
                    jdbc.queryForObject("SELECT occurred_at FROM delivery_event WHERE id = ?", java.sql.Timestamp.class, id).toInstant(), null)).reused());
        }
    }

    @Test
    void populatedV4MigratesToV5WithoutChangingExistingRows() {
        String schema = "delivery_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway v4 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).createSchemas(false).target("4").load();
            assertEquals(4, v4.migrate().migrationsExecuted);
            UUID job = UUID.randomUUID();
            UUID batch = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".verification_job(id, email, request_fingerprint) "
                    + "VALUES (?, 'legacy@example.test', decode(repeat('00', 32), 'hex'))", job);
            Long history = jdbc.queryForObject("INSERT INTO " + schema
                    + ".email_validation_history(email, job_id) VALUES ('legacy@example.test', ?) RETURNING id",
                    Long.class, job);
            jdbc.update("INSERT INTO " + schema + ".import_batch(id, state) VALUES (?, 'READY')", batch);
            jdbc.update("INSERT INTO " + schema
                    + ".import_row(import_id, ordinal, email, row_state, job_id) "
                    + "VALUES (?, 1, 'legacy@example.test', 'ACCEPTED', ?)", batch, job);
            Flyway v5 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).createSchemas(false).load();
            assertEquals(1, v5.migrate().migrationsExecuted);
            assertEquals("5", v5.info().current().getVersion().getVersion());
            assertEquals(0, v5.migrate().migrationsExecuted);
            assertEquals(job, jdbc.queryForObject("SELECT job_id FROM " + schema
                    + ".email_validation_history WHERE id = ?", UUID.class, history));
            assertEquals(job, jdbc.queryForObject("SELECT job_id FROM " + schema
                    + ".import_row WHERE import_id = ? AND ordinal = 1", UUID.class, batch));
            assertEquals("READY", jdbc.queryForObject(
                    "SELECT state FROM " + schema + ".import_batch WHERE id = ?", String.class, batch));
            assertNull(jdbc.queryForObject("SELECT result_historical_evidence::text FROM " + schema
                    + ".verification_job WHERE id = ?", String.class, job));
            assertEquals(3, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name IN
                      ('delivery_identity', 'delivery_event', 'delivery_suppression')
                    """, Integer.class, schema));
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void concurrentLookupsStayWithinPoolCapAndReleaseConnections() throws Exception {
        UUID id = evidence.ingest(event("pool@example.test", EventType.DELIVERED, "pool",
                Instant.now().truncatedTo(ChronoUnit.MICROS), null)).id();
        CountDownLatch acquired = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holders = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                holders.add(executor.submit(() -> {
                    try (var connection = poolDataSource.getConnection()) {
                        acquired.countDown();
                        return release.await(10, TimeUnit.SECONDS);
                    }
                }));
            }
            try {
                assertTrue(acquired.await(10, TimeUnit.SECONDS));
                assertEquals(4, poolDataSource.getHikariPoolMXBean().getActiveConnections());
                CountDownLatch start = new CountDownLatch(1);
                var readers = new ArrayList<Future<DeliveryEvidenceService.Snapshot>>();
                for (int i = 0; i < 20; i++) {
                    readers.add(executor.submit(() -> {
                        start.await();
                        return evidence.lookup("pool@example.test");
                    }));
                }
                start.countDown();
                for (var reader : readers) {
                    assertEquals(id, reader.get(10, TimeUnit.SECONDS).eventId());
                    assertTrue(poolDataSource.getHikariPoolMXBean().getActiveConnections()
                            <= poolDataSource.getMaximumPoolSize());
                }
            } finally {
                release.countDown();
            }
            for (var holder : holders) assertTrue(holder.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, poolDataSource.getHikariPoolMXBean().getActiveConnections());
    }

    private int countSuppression(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_suppression WHERE source_event_id = ?", Integer.class, eventId);
    }
}
