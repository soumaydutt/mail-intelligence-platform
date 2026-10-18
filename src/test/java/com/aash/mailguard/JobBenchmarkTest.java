package com.aash.mailguard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

@EnabledIfSystemProperty(named = "jobs.benchmark", matches = "true")
@Testcontainers(disabledWithoutDocker = false)
class JobBenchmarkTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void measureBoundedHttpSubmissionAndWorkerCompletionWithoutNetworkDns() throws Exception {
        int batch = 200;
        String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()};
        try (HttpClient http = HttpClient.newHttpClient();
                ConfigurableApplicationContext api = new SpringApplicationBuilder(MailGuardApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .run("--server.port=0", database[0], database[1], database[2],
                                "--jobs.api-key=benchmark-fixture-only", "--jobs.worker.enabled=false")) {
            JdbcTemplate jdbc = api.getBean(JdbcTemplate.class);
            int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
            for (int concurrency : List.of(1, 5, 10, 20, 50)) {
                try (ConfigurableApplicationContext worker = new SpringApplicationBuilder(MailGuardApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(database[0], database[1], database[2], "--jobs.worker.enabled=true",
                                "--jobs.worker.concurrency=" + concurrency)) {
                    long started = System.nanoTime();
                    for (int i = 0; i < batch; i++) {
                        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/jobs"))
                                .header("X-Job-API-Key", "benchmark-fixture-only")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"invalid-" + i + "\"}"))
                                .build();
                        assertEquals(202, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                    }
                    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
                    while (System.nanoTime() < deadline && jdbc.queryForObject(
                            "SELECT count(*) FROM verification_job WHERE state = 'COMPLETED'", Integer.class) < batch) {
                        Thread.sleep(100);
                    }
                    assertEquals(batch, jdbc.queryForObject(
                            "SELECT count(*) FROM verification_job WHERE state = 'COMPLETED'", Integer.class));
                    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                    List<Long> latencies = jdbc.query("SELECT created_at, finished_at FROM verification_job",
                            (rs, row) -> Duration.between(rs.getTimestamp("created_at").toInstant(),
                                    rs.getTimestamp("finished_at").toInstant()).toMillis());
                    Collections.sort(latencies);
                    System.out.printf(Locale.ROOT,
                            "jobs benchmark concurrency=%d completed=%d seconds=%.3f per_second=%.1f p50_ms=%d p95_ms=%d%n",
                            concurrency, batch, seconds, batch / seconds,
                            latencies.get(batch / 2), latencies.get((int) Math.ceil(batch * 0.95) - 1));
                }
                jdbc.update("DELETE FROM email_validation_history WHERE job_id IS NOT NULL");
                jdbc.update("DELETE FROM verification_job");
            }
        }
    }
}
