package com.aash.mailguard;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = false)
class PostgresStartupFailureTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void unavailableDatabasePreventsApplicationStartup() throws Exception {
        try (ServerSocket unavailable = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String url = "jdbc:postgresql://127.0.0.1:" + unavailable.getLocalPort()
                    + "/test?connectTimeout=1&socketTimeout=1";
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> start(url, postgres.getUsername(), postgres.getPassword()).close());
            assertTrue(hasSqlState(failure, "08"), "Expected a database connection failure");
        }
        assertEquals(0, jdbc().queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'", Integer.class));
    }

    @Test
    void invalidCredentialsPreventStartupWithoutCreatingFlywayHistory() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> start(postgres.getJdbcUrl(), postgres.getUsername(),
                        "incorrect-local-test-password").close());
        assertTrue(hasSqlState(failure, "28P01"), "Expected PostgreSQL invalid-password SQLSTATE");
        assertEquals(0, jdbc().queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'", Integer.class));
    }

    @Test
    void incompatibleFlywayChecksumPreventsStartupWithoutChangingHistory() {
        String schema = "phase6_incompatible";
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).createSchemas(false).load().migrate();
            jdbc.update("INSERT INTO " + schema + ".email_validation_history (email) VALUES (?)",
                    "preserved@fixture.test");
            jdbc.update("UPDATE " + schema + ".flyway_schema_history SET checksum = 999999 WHERE version = '1'");

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> start(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                            "--spring.flyway.schemas=" + schema,
                            "--spring.jpa.properties.hibernate.default_schema=" + schema).close());
            assertTrue(hasCause(failure, FlywayException.class), "Expected Flyway validation failure");
            assertEquals(999999, jdbc.queryForObject("SELECT checksum FROM " + schema
                    + ".flyway_schema_history WHERE version = '1'", Integer.class));
            assertEquals("preserved@fixture.test", jdbc.queryForObject("SELECT email FROM " + schema
                    + ".email_validation_history", String.class));
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static ConfigurableApplicationContext start(String url, String username, String password,
                                                        String... options) {
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + url,
                "--spring.datasource.username=" + username, "--spring.datasource.password=" + password,
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.datasource.hikari.initialization-fail-timeout=1000",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true"));
        args.addAll(List.of(options));
        return new SpringApplicationBuilder(MailGuardApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
    }

    private static boolean hasSqlState(Throwable error, String statePrefix) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith(statePrefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
