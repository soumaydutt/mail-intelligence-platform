package com.aash.mailguard;

import com.aash.mailguard.imports.BulkImportStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "imports.benchmark", matches = "true")
@Testcontainers(disabledWithoutDocker = false)
class BulkBenchmarkTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fifteenThousandDistinctSyntheticRows() throws Exception {
        measure(15_000);
    }

    @Test
    void fortyThousandDistinctSyntheticRows() throws Exception {
        measure(40_000);
    }

    private void measure(int rows) throws Exception {
        String url = "--spring.datasource.url=" + postgres.getJdbcUrl();
        String username = "--spring.datasource.username=" + postgres.getUsername();
        String password = "--spring.datasource.password=" + postgres.getPassword();
        try (HttpClient http = HttpClient.newHttpClient();
                ConfigurableApplicationContext api = new SpringApplicationBuilder(MailGuardApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .run("--server.port=0", url, username, password, "--jobs.api-key=synthetic-benchmark-key",
                                "--jobs.worker.enabled=false", "--jobs.max-outstanding=50000");
                ConfigurableApplicationContext worker = new SpringApplicationBuilder(MailGuardApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(url, username, password, "--jobs.worker.enabled=true", "--jobs.max-outstanding=50000",
                                "--jobs.worker.concurrency=20", "--jobs.worker.poll-millis=50")) {
            JdbcTemplate jdbc = api.getBean(JdbcTemplate.class);
            long databaseBefore = jdbc.queryForObject("SELECT pg_total_relation_size('verification_job') + "
                    + "pg_total_relation_size('import_row') + pg_total_relation_size('import_batch')", Long.class);
            int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/imports");
            StringBuilder data = new StringBuilder(rows * 22 + 6).append("email\n");
            for (int row = 0; row < rows; row++) data.append("invalid-synthetic-").append(row).append('\n');
            String boundary = "benchmark-synthetic-boundary";
            byte[] payload = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                    + "filename=\"benchmark.csv\"\r\nContent-Type: text/csv\r\n\r\n"
                    + data + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            AtomicBoolean sampling = new AtomicBoolean(true);
            AtomicLong peak = new AtomicLong();
            AtomicLong poolPeak = new AtomicLong();
            HikariDataSource source = api.getBean(HikariDataSource.class);
            HikariDataSource workerSource = worker.getBean(HikariDataSource.class);
            Thread sampler = Thread.ofVirtual().start(() -> {
                while (sampling.get()) {
                    peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                    poolPeak.accumulateAndGet(source.getHikariPoolMXBean().getActiveConnections()
                            + workerSource.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                }
            });
            try {
                long begin = System.nanoTime();
                HttpResponse<String> posted = http.send(HttpRequest.newBuilder(endpoint)
                        .header("X-Job-API-Key", "synthetic-benchmark-key")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, posted.statusCode(), posted.body());
                UUID id = UUID.fromString(JSON.readTree(posted.body()).get("id").asText());
                double ingestSeconds = (System.nanoTime() - begin) / 1_000_000_000.0;
                long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
                long promotedAt = 0;
                while (System.nanoTime() < deadline) {
                    if (promotedAt == 0 && jdbc.queryForObject("SELECT count(*) FROM import_row "
                            + "WHERE import_id = ? AND row_state = 'ACCEPTED' AND job_id IS NULL", Integer.class, id) == 0) {
                        promotedAt = System.nanoTime();
                    }
                    if ("COMPLETED".equals(jdbc.queryForObject(
                            "SELECT state FROM import_batch WHERE id = ?", String.class, id))) break;
                    Thread.sleep(100);
                }
                assertTrue(promotedAt > 0, "Every accepted row must be promoted");
                assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM import_batch WHERE id = ?", String.class, id));
                assertEquals(rows, jdbc.queryForObject("SELECT count(*) FROM import_row WHERE import_id = ?", Integer.class, id));
                assertEquals(rows, jdbc.queryForObject("SELECT count(*) FROM verification_job WHERE id IN "
                        + "(SELECT job_id FROM import_row WHERE import_id = ?)", Integer.class, id));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verification_job "
                        + "WHERE id IN (SELECT job_id FROM import_row WHERE import_id = ?) "
                        + "AND state = 'FAILED'", Integer.class, id));
                double completionSeconds = (System.nanoTime() - begin) / 1_000_000_000.0;
                long exportStart = System.nanoTime();
                HttpResponse<InputStream> download = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/imports/" + id + "/export"))
                        .header("X-Job-API-Key", "synthetic-benchmark-key").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, download.statusCode());
                long bytes = 0;
                long exportedLines = 0;
                try (InputStream stream = download.body()) {
                    byte[] chunk = new byte[8192];
                    for (int size; (size = stream.read(chunk)) != -1;) {
                        bytes += size;
                        for (int index = 0; index < size; index++) {
                            if (chunk[index] == '\n') exportedLines++;
                        }
                    }
                }
                assertEquals(rows + 1, exportedLines);
                double exportSeconds = (System.nanoTime() - exportStart) / 1_000_000_000.0;
                long databaseBytes = jdbc.queryForObject("SELECT pg_total_relation_size('verification_job') + "
                        + "pg_total_relation_size('import_row') + pg_total_relation_size('import_batch')", Long.class)
                        - databaseBefore;
                double promotionSeconds = (promotedAt - begin) / 1_000_000_000.0;
                System.out.printf(Locale.ROOT, "bulk benchmark rows=%d ingest_s=%.3f ingest_rows_s=%.1f "
                                + "promotion_s=%.3f promotion_rows_s=%.1f complete_s=%.3f complete_rows_s=%.1f "
                                + "export_bytes=%d export_bytes_s=%.1f "
                                + "peak_heap_bytes=%d database_growth_bytes=%d pool_active_peak=%d errors=0%n",
                        rows, ingestSeconds, rows / ingestSeconds, promotionSeconds, rows / promotionSeconds,
                        completionSeconds, rows / completionSeconds, bytes, bytes / exportSeconds, peak.get(),
                        databaseBytes, poolPeak.get());
            } finally {
                sampling.set(false);
                sampler.join();
            }
        }
    }
}
