package com.aash.mailguard;

import com.aash.mailguard.entity.EmailValidationHistory;
import com.aash.mailguard.repository.EmailValidationHistoryRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PostgresPersistenceTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    Flyway flyway;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    EmailValidationHistoryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearHistory() {
        jdbc.update("DELETE FROM email_validation_history");
    }

    @Test
    void freshFlywaySchemaValidatesAndRepeatedMigrationsPreserveRows() {
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
        assertEquals("timestamp with time zone", jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'email_validation_history' "
                        + "AND column_name = 'created_at'", String.class));
        assertEquals("boolean", jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'email_validation_history' "
                        + "AND column_name = 'mx_record_found'", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'email_validation_history' "
                        + "AND indexname = 'idx_email_validation_history_recent'", String.class)
                .contains("(created_at DESC, id DESC)"));

        Long id = jdbc.queryForObject("INSERT INTO email_validation_history DEFAULT VALUES RETURNING id", Long.class);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertEquals(0, flyway.migrate().migrationsExecuted);
            entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager().validateMappedObjects();
            assertTrue(repository.existsById(id));
        }
    }

    @Test
    void rejectsUntrackedNonemptySchemaWithoutBaseliningIt() {
        String schema = "phase6_untracked";
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            jdbc.execute("CREATE TABLE " + schema + ".existing (id BIGINT)");
            Flyway untracked = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .createSchemas(false)
                    .baselineOnMigrate(false)
                    .load();
            assertThrows(FlywayException.class, untracked::migrate);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                            + "AND table_name = 'flyway_schema_history'", Integer.class, schema));
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void historyRoundTripsThroughJpaWithNullableColumnsAndDatabaseTimestamp() {
        EmailValidationHistory history = new EmailValidationHistory();
        history.setEmail("synthetic@example.test");
        history.setDomain("example.test");
        history.setStatus("UNVERIFIED");
        history.setMessage("Synthetic observation");
        history.setFormatValid(true);
        history.setMxRecordFound(false);
        history.setSmtpVerified(null);

        Long id = repository.save(history).getId();
        EmailValidationHistory persisted = repository.findById(id).orElseThrow();
        assertEquals("synthetic@example.test", persisted.getEmail());
        assertEquals("example.test", persisted.getDomain());
        assertEquals("UNVERIFIED", persisted.getStatus());
        assertEquals("Synthetic observation", persisted.getMessage());
        assertEquals(true, persisted.getFormatValid());
        assertEquals(false, persisted.getMxRecordFound());
        assertNull(persisted.getSmtpVerified());
        assertNotNull(persisted.getCreatedAt());
        assertEquals(jdbc.queryForObject("SELECT created_at FROM email_validation_history WHERE id = ?",
                Timestamp.class, id).toInstant(), persisted.getCreatedAt());

        Long nullableId = jdbc.queryForObject(
                "INSERT INTO email_validation_history DEFAULT VALUES RETURNING id", Long.class);
        EmailValidationHistory nullable = repository.findById(nullableId).orElseThrow();
        assertNull(nullable.getEmail());
        assertNull(nullable.getDomain());
        assertNull(nullable.getFormatValid());
        assertNull(nullable.getMxRecordFound());
        assertNull(nullable.getSmtpVerified());
        assertNull(nullable.getStatus());
        assertNull(nullable.getMessage());
        assertNotNull(nullable.getCreatedAt());
    }

    @Test
    void recentHistoryUsesIdToBreakTimestampTiesAcrossPages() {
        Timestamp older = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));
        Timestamp tied = Timestamp.from(Instant.parse("2024-01-02T00:00:00Z"));
        Long oldest = insertAt("oldest@example.test", older);
        Long first = insertAt("first@example.test", tied);
        Long second = insertAt("second@example.test", tied);

        var firstPage = repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 2));
        assertEquals(List.of(second, first), firstPage.getContent().stream()
                .map(EmailValidationHistory::getId).toList());
        assertTrue(firstPage.hasNext());
        var lastPage = repository.findAllByOrderByCreatedAtDescIdDesc(firstPage.nextPageable());
        assertEquals(List.of(oldest), lastPage.getContent().stream()
                .map(EmailValidationHistory::getId).toList());
        assertFalse(lastPage.hasNext());
    }

    @Test
    void postgresEnforcesVarcharLengthsAndNonNullTimestamp() {
        String oversized = "x".repeat(256);
        for (String column : List.of("email", "domain", "status", "message")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO email_validation_history (" + column + ") VALUES (?)", oversized),
                    column);
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO email_validation_history (created_at) VALUES (NULL)"));
        assertEquals(0, repository.count());
    }

    @Test
    void secondInsertFailureRollsBackFirstInsertAtReadCommittedIsolation() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(DataIntegrityViolationException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals("read committed", jdbc.queryForObject("SHOW transaction_isolation", String.class));
            jdbc.update("INSERT INTO email_validation_history (email) VALUES (?)", "first@example.test");
            jdbc.update("INSERT INTO email_validation_history (email) VALUES (?)", "x".repeat(256));
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_validation_history", Integer.class));
    }

    @Test
    void interruptedTransactionRollsBackEarlierInsert() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO email_validation_history (email) VALUES (?)", "interrupted@example.test");
            jdbc.execute("SET LOCAL statement_timeout = '200ms'");
            jdbc.queryForObject("SELECT pg_sleep(1)", Object.class);
        }));
        assertEquals(0, repository.count());
        assertEquals(1, jdbc.update("INSERT INTO email_validation_history (email) VALUES (?)",
                "after-interruption@example.test"));
    }

    @Test
    void unicodeAndSpecialCharactersSurvivePersistenceRoundTrip() {
        String unicodeEmail = "δοκιμή@bücher.test";
        String maxLenString = "é" + "x".repeat(254);

        EmailValidationHistory history = new EmailValidationHistory();
        history.setEmail(unicodeEmail);
        history.setDomain(maxLenString);
        history.setStatus("UNVERIFIED");
        history.setMessage(maxLenString);
        history.setFormatValid(true);
        history.setMxRecordFound(true);
        history.setSmtpVerified(null);

        Long id = repository.save(history).getId();
        EmailValidationHistory loaded = repository.findById(id).orElseThrow();
        assertEquals(unicodeEmail, loaded.getEmail());
        assertEquals(maxLenString, loaded.getDomain());
        assertEquals(maxLenString, loaded.getMessage());
        assertEquals(true, loaded.getFormatValid());
        assertEquals(true, loaded.getMxRecordFound());
        assertNull(loaded.getSmtpVerified());
    }

    @Test
    void concurrentHistoryInsertsDoNotDropRowsOrGenerateDuplicateIds() throws Exception {
        int threads = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    EmailValidationHistory row = new EmailValidationHistory();
                    row.setEmail("user" + idx + "@concurrent.test");
                    row.setDomain("concurrent.test");
                    row.setStatus("UNVERIFIED");
                    row.setFormatValid(true);
                    return repository.save(row).getId();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        try {
            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            List<Long> ids = new ArrayList<>();
            for (Future<Long> future : futures) {
                ids.add(future.get());
            }
            assertEquals(threads, ids.stream().distinct().count(), "All generated IDs must be unique");
            assertEquals(threads, repository.count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fullDatabaseBackupRestoresFlywayHistoryRowsAndIdentityIntoFreshContext() throws Exception {
        Timestamp firstTimestamp = Timestamp.from(Instant.parse("2024-01-02T03:04:05.123456Z"));
        Timestamp secondTimestamp = Timestamp.from(Instant.parse("2024-02-03T04:05:06.654321Z"));
        Long firstId = jdbc.queryForObject("INSERT INTO email_validation_history "
                        + "(email, domain, format_valid, mx_record_found, smtp_verified, status, message, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, "backup@example.test", "example.test", true, false, null,
                "UNVERIFIED", "Retain nullable SMTP result", firstTimestamp);
        Long secondId = jdbc.queryForObject(
                "INSERT INTO email_validation_history (created_at) VALUES (?) RETURNING id",
                Long.class, secondTimestamp);
        String rowsSql = "SELECT id, email, domain, format_valid, mx_record_found, smtp_verified, "
                + "status, message, created_at FROM email_validation_history ORDER BY id";
        String migrationsSql = "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank";
        List<Map<String, Object>> originalRows = jdbc.queryForList(rowsSql);
        List<Map<String, Object>> originalMigrations = jdbc.queryForList(migrationsSql);
        Map<String, Object> originalSequence = jdbc.queryForMap(
                "SELECT last_value, is_called FROM email_validation_history_id_seq");
        assertEquals(2, originalRows.size());
        assertEquals(List.of(firstId, secondId), originalRows.stream().map(row -> row.get("id")).toList());
        assertEquals(firstTimestamp, originalRows.get(0).get("created_at"));
        assertEquals(secondTimestamp, originalRows.get(1).get("created_at"));
        assertEquals(1, originalMigrations.size());
        assertEquals("1", originalMigrations.get(0).get("version"));
        assertNotNull(originalMigrations.get(0).get("checksum"));
        assertEquals(true, originalMigrations.get(0).get("success"));
        assertEquals(true, originalSequence.get("is_called"));

        String restoreDb = "mailguard_restore_" + UUID.randomUUID().toString().replace("-", "");
        String dumpFile = "/tmp/" + restoreDb + ".dump";
        boolean databaseCreated = false;
        try {
            var dump = postgres.execInContainer("pg_dump", "-Fc", "-U", postgres.getUsername(),
                    "-d", postgres.getDatabaseName(), "-f", dumpFile);
            assertEquals(0, dump.getExitCode(), dump.getStderr());

            var create = postgres.execInContainer("createdb", "-U", postgres.getUsername(), restoreDb);
            assertEquals(0, create.getExitCode(), create.getStderr());
            databaseCreated = true;

            var restore = postgres.execInContainer("pg_restore", "--exit-on-error", "--single-transaction",
                    "-U", postgres.getUsername(), "-d", restoreDb, dumpFile);
            assertEquals(0, restore.getExitCode(), restore.getStderr());

            String restoredUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/" + restoreDb;
            // A new context runs Flyway and Hibernate validation against the restored database.
            try (ConfigurableApplicationContext restored = new SpringApplicationBuilder(MailGuardApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.datasource.url=" + restoredUrl,
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--spring.flyway.enabled=true",
                            "--spring.jpa.hibernate.ddl-auto=validate")) {
                JdbcTemplate restoredJdbc = restored.getBean(JdbcTemplate.class);
                assertEquals(originalMigrations, restoredJdbc.queryForList(migrationsSql));
                assertEquals(1, restoredJdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history", Integer.class));
                assertEquals(originalRows, restoredJdbc.queryForList(rowsSql));
                assertEquals(originalSequence, restoredJdbc.queryForMap(
                        "SELECT last_value, is_called FROM email_validation_history_id_seq"));
                assertEquals("BASE TABLE", restoredJdbc.queryForObject(
                        "SELECT table_type FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name = 'email_validation_history'",
                        String.class));
                assertTrue(restoredJdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                                + "AND indexname = 'idx_email_validation_history_recent'", String.class)
                        .contains("(created_at DESC, id DESC)"));

                EmailValidationHistory newHistory = new EmailValidationHistory();
                newHistory.setEmail("after-restore@example.test");
                EmailValidationHistoryRepository restoredRepository =
                        restored.getBean(EmailValidationHistoryRepository.class);
                Long generatedId = restoredRepository.saveAndFlush(newHistory).getId();
                assertEquals(((Number) originalSequence.get("last_value")).longValue() + 1, generatedId);
                assertTrue(restoredRepository.existsById(firstId));
                assertTrue(restoredRepository.existsById(secondId));
                assertEquals(3, restoredRepository.count());
                assertEquals("1", restored.getBean(Flyway.class).info().current().getVersion().getVersion());
                assertEquals(originalMigrations, restoredJdbc.queryForList(migrationsSql));
            }
        } finally {
            try {
                if (databaseCreated) {
                    var drop = postgres.execInContainer("dropdb", "-U", postgres.getUsername(), restoreDb);
                    assertEquals(0, drop.getExitCode(), drop.getStderr());
                }
            } finally {
                var remove = postgres.execInContainer("rm", "-f", dumpFile);
                assertEquals(0, remove.getExitCode(), remove.getStderr());
            }
        }
    }

    @Test
    void migrationChecksumMismatchFailsFastWithoutDataCorruption() {
        String schema = "phase6_tamper";
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway flywayInstance = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .createSchemas(false)
                    .baselineOnMigrate(false)
                    .load();
            flywayInstance.migrate();

            jdbc.update("UPDATE " + schema + ".flyway_schema_history SET checksum = 999999 WHERE version = '1'");

            assertThrows(FlywayException.class, flywayInstance::validate);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void largeHistoryKeepsFirstAndLastPagesInIdOrder() {
        int total = 10_000;
        jdbc.batchUpdate(
                "INSERT INTO email_validation_history (email, domain, status, format_valid, mx_record_found, created_at) "
                        + "VALUES (?, 'scale.test', 'UNVERIFIED', true, true, TIMESTAMPTZ '2024-01-01 00:00:00+00')",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setString(1, "scale" + i + "@scale.test");
                    }

                    @Override
                    public int getBatchSize() {
                        return total;
                    }
                }
        );
        assertEquals(total, repository.count());

        var first = repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 10));
        assertEquals(IntStream.range(0, 10).mapToObj(i -> "scale" + (total - 1 - i) + "@scale.test").toList(),
                first.getContent().stream().map(EmailValidationHistory::getEmail).toList());
        assertTrue(first.hasNext());

        var last = repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(total / 10 - 1, 10));
        assertEquals(IntStream.range(0, 10).mapToObj(i -> "scale" + (9 - i) + "@scale.test").toList(),
                last.getContent().stream().map(EmailValidationHistory::getEmail).toList());
        assertFalse(last.hasNext());
    }

    private Long insertAt(String email, Timestamp createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO email_validation_history (email, created_at) VALUES (?, ?) RETURNING id",
                Long.class, email, createdAt);
    }
}
