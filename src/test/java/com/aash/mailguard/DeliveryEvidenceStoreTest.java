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

    private int countSuppression(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_suppression WHERE source_event_id = ?", Integer.class, eventId);
    }
}
