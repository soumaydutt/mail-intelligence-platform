package com.aash.mailguard.dns;

import com.aash.mailguard.MailGuardApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
class Phase10HttpEndToEndTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8-alpine")
            .withExposedPorts(6379);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String KEY = "private-phase10-fixture-key";
    private static final String EMAIL = "Case@fixture.test";

    @Test
    void cachedDnsDoesNotFreezeCurrentEvidenceAndWorkerRetainsOldContracts() throws Exception {
        String namespace = "phase10-integration-" + UUID.randomUUID().toString().substring(0, 8);
        String[] common = {
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.data.redis.host=" + valkey.getHost(),
                "--spring.data.redis.port=" + valkey.getMappedPort(6379),
                "--valkey.namespace=" + namespace,
                "--dns.cache.enabled=true", "--rate-limit.enabled=true",
                "--rate-limit.identity-secret=synthetic-phase10-secret-has-32-characters",
                "--jobs.api-key=" + KEY, "--delivery.source-id=phase10-fixture-source",
                "--jobs.worker.poll-millis=50", "--imports.worker.poll-millis=50"
        };
        String[] apiArgs = java.util.Arrays.copyOf(common, common.length + 2);
        apiArgs[common.length] = "--server.port=0";
        apiArgs[common.length + 1] = "--jobs.worker.enabled=false";
        String[] workerArgs = java.util.Arrays.copyOf(common, common.length + 1);
        workerArgs[common.length] = "--jobs.worker.enabled=true";
        FixtureDnsConfiguration.queries.set(0);
        try (HttpClient http = HttpClient.newHttpClient();
                ConfigurableApplicationContext api = new SpringApplicationBuilder(
                        MailGuardApplication.class, FixtureDnsConfiguration.class)
                        .web(WebApplicationType.SERVLET).run(apiArgs)) {
            int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
            URI base = URI.create("http://127.0.0.1:" + port);
            JdbcTemplate jdbc = api.getBean(JdbcTemplate.class);
            HttpResponse<String> publicFirst = send(http, base, "POST", "/api/email/validate",
                    "{\"email\":\"" + EMAIL + "\"}", null);
            assertEquals(200, publicFirst.statusCode(), publicFirst.body());
            assertEquals("60", publicFirst.headers().firstValue("RateLimit-Limit").orElseThrow());
            JsonNode legacy = json.readTree(publicFirst.body());
            assertEquals(Set.of("email", "domain", "formatValid", "mxRecordFound", "smtpVerified", "status", "message"),
                    json.convertValue(legacy, Map.class).keySet());
            assertEquals("UNVERIFIED", legacy.get("status").asText());
            assertTrue(legacy.get("smtpVerified").isNull());
            assertEquals(2, FixtureDnsConfiguration.queries.get());

            String event = "{\"email\":\"" + EMAIL + "\",\"eventType\":\"COMPLAINT\","
                    + "\"externalEventId\":\"phase10-complaint\",\"occurredAt\":\""
                    + Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS) + "\"}";
            HttpResponse<String> ingested = send(http, base, "POST", "/api/delivery-events", event, KEY);
            assertEquals(201, ingested.statusCode(), ingested.body());
            HttpResponse<String> replay = send(http, base, "POST", "/api/delivery-events", event, KEY);
            assertEquals(200, replay.statusCode(), replay.body());
            assertTrue(json.readTree(replay.body()).get("reused").asBoolean());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_event", Integer.class));
            JsonNode suppressed = json.readTree(send(http, base, "POST", "/api/email/validate",
                    "{\"email\":\"" + EMAIL + "\"}", KEY).body());
            assertTrue(suppressed.get("historicalEvidence").get("suppressed").asBoolean());
            assertEquals(2, FixtureDnsConfiguration.queries.get(), "DNS cache hit must not cache historical evidence");

            JsonNode listed = json.readTree(send(http, base, "GET", "/api/suppressions", null, KEY).body());
            UUID suppression = UUID.fromString(listed.get(0).get("id").asText());
            assertEquals(204, send(http, base, "DELETE", "/api/suppressions/" + suppression,
                    "{\"reasonCode\":\"OPERATOR_RELEASE\"}", KEY).statusCode());
            JsonNode lifted = json.readTree(send(http, base, "POST", "/api/email/validate",
                    "{\"email\":\"" + EMAIL + "\"}", KEY).body());
            assertFalse(lifted.get("historicalEvidence").get("suppressed").asBoolean());
            assertEquals(2, FixtureDnsConfiguration.queries.get());

            try (ConfigurableApplicationContext worker = new SpringApplicationBuilder(
                    MailGuardApplication.class, FixtureDnsConfiguration.class)
                    .web(WebApplicationType.NONE).run(workerArgs)) {
                HttpResponse<String> accepted = send(http, base, "POST", "/api/jobs",
                        "{\"email\":\"" + EMAIL + "\"}", KEY);
                assertEquals(202, accepted.statusCode(), accepted.body());
                UUID jobId = UUID.fromString(json.readTree(accepted.body()).get("id").asText());
                JsonNode completed = await(http, base, "/api/jobs/" + jobId, "state", "COMPLETED");
                assertEquals("UNVERIFIED", completed.get("result").get("status").asText());
                assertFalse(completed.get("result").get("historicalEvidence").get("suppressed").asBoolean());
                HttpResponse<String> blocked = send(http, base, "POST", "/api/suppressions",
                        "{\"email\":\"" + EMAIL + "\",\"reasonCode\":\"OPERATOR_BLOCK\"}", KEY);
                assertEquals(201, blocked.statusCode(), blocked.body());
                JsonNode current = json.readTree(send(http, base, "POST", "/api/email/validate",
                        "{\"email\":\"" + EMAIL + "\"}", KEY).body());
                assertTrue(current.get("historicalEvidence").get("suppressed").asBoolean());
                JsonNode stored = json.readTree(send(http, base, "GET", "/api/jobs/" + jobId, null, KEY).body());
                assertFalse(stored.get("result").get("historicalEvidence").get("suppressed").asBoolean());

                String boundary = "phase10-fixture-boundary";
                String csv = "email\nimport@fixture.test\ninvalid-syntax\n";
                String form = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                        + "filename=\"phase10.csv\"\r\nContent-Type: text/csv\r\n\r\n"
                        + csv + "\r\n--" + boundary + "--\r\n";
                HttpResponse<String> imported = http.send(HttpRequest.newBuilder(base.resolve("/api/imports"))
                        .header("X-Job-API-Key", KEY)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(form.getBytes(StandardCharsets.UTF_8)))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, imported.statusCode(), imported.body());
                UUID importId = UUID.fromString(json.readTree(imported.body()).get("id").asText());
                JsonNode progress = await(http, base, "/api/imports/" + importId, "state", "COMPLETED");
                assertEquals(2, progress.get("inputRows").asInt());
                JsonNode rows = json.readTree(send(http, base, "GET",
                        "/api/imports/" + importId + "/results?after=0&limit=10", null, KEY).body());
                assertEquals(2, rows.size());
                assertEquals("UNVERIFIED", rows.get(0).get("resultStatus").asText());
                assertEquals("INVALID", rows.get(1).get("resultStatus").asText());
                HttpResponse<String> export = send(http, base, "GET", "/api/imports/" + importId + "/export",
                        null, KEY);
                assertEquals(200, export.statusCode());
                assertTrue(export.body().indexOf("import@fixture.test") < export.body().indexOf("invalid-syntax"));
                assertFalse(export.body().contains("historicalEvidence"));
                assertEquals(2, FixtureDnsConfiguration.queries.get(), "Worker must reuse shared DNS observation");
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_validation_history WHERE job_id = ?",
                        Integer.class, jobId));
            }
        }
    }

    @Test
    void startupWithoutValkeyKeepsBoundedPublicDnsButBlocksAuthorizedAdmin() throws Exception {
        FixtureDnsConfiguration.queries.set(0);
        try (HttpClient http = HttpClient.newHttpClient();
                ConfigurableApplicationContext api = new SpringApplicationBuilder(
                        MailGuardApplication.class, FixtureDnsConfiguration.class)
                        .web(WebApplicationType.SERVLET)
                        .run("--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword(),
                                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
                                "--valkey.namespace=unreachable-" + UUID.randomUUID().toString().substring(0, 8),
                                "--dns.cache.enabled=true", "--rate-limit.enabled=true",
                                "--rate-limit.identity-secret=synthetic-phase10-secret-has-32-characters",
                                "--jobs.api-key=" + KEY, "--jobs.worker.enabled=false")) {
            int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
            URI base = URI.create("http://127.0.0.1:" + port);
            for (int i = 0; i < 2; i++) {
                HttpResponse<String> publicResult = send(http, base, "POST", "/api/email/validate",
                        "{\"email\":\"offline@fixture.test\"}", null);
                assertEquals(200, publicResult.statusCode(), publicResult.body());
                assertEquals("30", publicResult.headers().firstValue("RateLimit-Limit").orElseThrow());
            }
            assertEquals(4, FixtureDnsConfiguration.queries.get(),
                    "An unavailable cache must not return stale or false-negative DNS evidence");
            String path = "/api/jobs/" + UUID.randomUUID();
            assertEquals(503, send(http, base, "GET", path, null, null).statusCode());
            HttpResponse<String> admin = send(http, base, "GET", path, null, KEY);
            assertEquals(503, admin.statusCode(), admin.body());
            assertEquals("no-store", admin.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    void twoApiInstancesShareTheSamePublicTokenBucket() throws Exception {
        String[] settings = {
                "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.data.redis.host=" + valkey.getHost(),
                "--spring.data.redis.port=" + valkey.getMappedPort(6379),
                "--valkey.namespace=shared-rate-" + UUID.randomUUID().toString().substring(0, 8),
                "--dns.cache.enabled=false", "--rate-limit.enabled=true",
                "--rate-limit.identity-secret=synthetic-phase10-secret-has-32-characters",
                "--rate-limit.test-capacity=1", "--rate-limit.public-capacity=2",
                "--rate-limit.public-global-capacity=2",
                "--jobs.api-key=" + KEY, "--jobs.worker.enabled=false"
        };
        try (HttpClient http = HttpClient.newHttpClient();
                ConfigurableApplicationContext first = new SpringApplicationBuilder(MailGuardApplication.class)
                        .web(WebApplicationType.SERVLET).run(settings);
                ConfigurableApplicationContext second = new SpringApplicationBuilder(MailGuardApplication.class)
                        .web(WebApplicationType.SERVLET).run(settings)) {
            int firstPort = first.getEnvironment().getProperty("local.server.port", Integer.class);
            int secondPort = second.getEnvironment().getProperty("local.server.port", Integer.class);
            URI firstBase = URI.create("http://127.0.0.1:" + firstPort);
            URI secondBase = URI.create("http://127.0.0.1:" + secondPort);
            HttpResponse<String> options = send(http, firstBase, "OPTIONS", "/api/email/test", null, null);
            assertEquals(200, options.statusCode(), options.body());
            assertEquals("POST,OPTIONS", options.headers().firstValue("Allow").orElseThrow());
            assertEquals(200, send(http, firstBase, "POST", "/api/email/test", null, null).statusCode());
            HttpResponse<String> denied = send(http, secondBase, "POST", "/api/email/test", null, null);
            assertEquals(429, denied.statusCode(), denied.body());
            long retry = Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow());
            assertTrue(retry > 0 && retry <= 60);
            assertEquals("/api/email/test", json.readTree(denied.body()).get("path").asText());
            HttpResponse<String> otherPolicy = send(http, secondBase, "POST", "/api/email/validate",
                    "{\"email\":\"invalid-syntax\"}", null);
            assertEquals(200, otherPolicy.statusCode(), otherPolicy.body());
            assertEquals("1", otherPolicy.headers().firstValue("RateLimit-Remaining").orElseThrow());
            HttpResponse<String> globalDenied = send(http, firstBase, "POST", "/api/email/validate",
                    "{\"email\":\"invalid-syntax\"}", null);
            assertEquals(429, globalDenied.statusCode(), globalDenied.body());
        }
    }

    @Test
    void optInBenchmarkContextsStartWithBoundedNamespacesWithoutRunningWorkloads() throws Exception {
        String dnsNamespace = Phase10BenchmarkTest.namespace("dns-benchmark-");
        String bulkNamespace = Phase10BenchmarkTest.namespace("bulk-benchmark-");
        assertTrue(dnsNamespace.length() <= 32);
        assertTrue(bulkNamespace.length() <= 32);
        assertNotEquals(dnsNamespace, Phase10BenchmarkTest.namespace("dns-benchmark-"));
        for (boolean cacheEnabled : new boolean[] {false, true}) {
            assertNotNull(new DnsLookupService(mock(Resolver.class), mock(StringRedisTemplate.class),
                    new SimpleMeterRegistry(), dnsNamespace, cacheEnabled, 30, 5, 8192, 128, 2500));
            assertThrows(IllegalArgumentException.class, () -> new DnsLookupService(
                    mock(Resolver.class), mock(StringRedisTemplate.class), new SimpleMeterRegistry(),
                    "dns-benchmark-" + UUID.randomUUID(), cacheEnabled, 30, 5, 8192, 128, 2500));

            String[] common = {
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.data.redis.host=" + valkey.getHost(),
                    "--spring.data.redis.port=" + valkey.getMappedPort(6379),
                    "--valkey.namespace=" + bulkNamespace,
                    "--dns.cache.enabled=" + cacheEnabled,
                    "--jobs.api-key=" + KEY
            };
            String[] apiArgs = java.util.Arrays.copyOf(common, common.length + 2);
            apiArgs[common.length] = "--server.port=0";
            apiArgs[common.length + 1] = "--jobs.worker.enabled=false";
            String[] workerArgs = java.util.Arrays.copyOf(common, common.length + 1);
            workerArgs[common.length] = "--jobs.worker.enabled=true";
            Phase10BenchmarkTest.FixtureDnsConfiguration.queries.set(0);
            try (HttpClient http = HttpClient.newHttpClient();
                    ConfigurableApplicationContext api = new SpringApplicationBuilder(
                            MailGuardApplication.class, Phase10BenchmarkTest.FixtureDnsConfiguration.class)
                            .web(WebApplicationType.SERVLET).run(apiArgs);
                    ConfigurableApplicationContext worker = new SpringApplicationBuilder(
                            MailGuardApplication.class, Phase10BenchmarkTest.FixtureDnsConfiguration.class)
                            .web(WebApplicationType.NONE).run(workerArgs)) {
                int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
                URI base = URI.create("http://127.0.0.1:" + port);
                for (int i = 0; i < 2; i++) {
                    HttpResponse<String> result = send(http, base, "POST", "/api/email/validate",
                            "{\"email\":\"benchmark@fixture.test\"}", null);
                    assertEquals(200, result.statusCode(), result.body());
                    assertEquals("UNVERIFIED", json.readTree(result.body()).get("status").asText());
                }
                assertEquals(cacheEnabled ? 2 : 4, Phase10BenchmarkTest.FixtureDnsConfiguration.queries.get());
                assertNotNull(worker.getBean(DnsLookupService.class));
            }
        }
    }

    private static JsonNode await(HttpClient http, URI base, String path, String field, String target)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(http, base, "GET", path, null, KEY);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = json.readTree(response.body());
            if (target.equals(body.get(field).asText())) return body;
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("Timed out awaiting " + target + " at " + path);
    }

    private static HttpResponse<String> send(HttpClient client, URI base, String method, String path,
            String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
        if (key != null) request.header("X-Job-API-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class FixtureDnsConfiguration {
        static final AtomicInteger queries = new AtomicInteger();

        @Bean
        @Primary
        DnsLookupService fixtureDns(StringRedisTemplate redis, MeterRegistry metrics,
                @Value("${valkey.namespace}") String namespace) throws Exception {
            Resolver resolver = mock(Resolver.class);
            Name mail = Name.fromString("mail.fixture.test.");
            when(resolver.send(any(Message.class))).thenAnswer(invocation -> {
                Message request = invocation.getArgument(0);
                queries.incrementAndGet();
                Message answer = new Message(request.getHeader().getID());
                answer.getHeader().setFlag(Flags.QR);
                answer.addRecord(request.getQuestion(), Section.QUESTION);
                if (request.getQuestion().getType() == Type.MX) {
                    answer.addRecord(new MXRecord(request.getQuestion().getName(), DClass.IN, 60,
                            10, mail), Section.ANSWER);
                } else if (request.getQuestion().getType() == Type.A) {
                    answer.addRecord(new ARecord(request.getQuestion().getName(), DClass.IN, 60,
                            InetAddress.getByName("192.0.2.1")), Section.ANSWER);
                }
                return answer;
            });
            return new DnsLookupService(resolver, redis, metrics, namespace, true, 30, 5, 8192, 128, 2500);
        }
    }
}
