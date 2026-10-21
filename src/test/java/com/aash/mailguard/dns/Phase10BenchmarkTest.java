package com.aash.mailguard.dns;

import com.aash.mailguard.MailGuardApplication;
import com.aash.mailguard.ratelimit.RedisTokenBucket;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "valkey.benchmark", matches = "true")
@Testcontainers(disabledWithoutDocker = false)
class Phase10BenchmarkTest {
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8-alpine")
            .withExposedPorts(6379);
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    private static final int RUNS = 1_000;
    private static final String DOMAIN = "fixture.test";
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(valkey.getHost(), valkey.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) connections.destroy();
    }

    @Test
    void cachedDnsLatency() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DnsLookupService dns = dns(true, queries, meters, 0);
        dns.lookup(DOMAIN);
        long[] samples = measure(RUNS, () -> assertEquals(DnsObservation.Reason.MX_PRESENT,
                dns.lookup(DOMAIN).reason()));
        assertEquals(2, queries.get(), "A hot cache must not invoke the resolver again");
        report("cached_dns", samples);
    }

    @Test
    void uncachedDnsLatency() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DnsLookupService dns = dns(false, queries, meters, 0);
        long[] samples = measure(RUNS, () -> assertEquals(DnsObservation.Reason.MX_PRESENT,
                dns.lookup(DOMAIN).reason()));
        assertEquals(RUNS * 2, queries.get(), "Uncached MX and A queries are both required");
        report("uncached_dns", samples);
    }

    @Test
    void concurrentCacheHitThroughput() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            DnsLookupService dns = dns(true, queries, meters, 0);
            dns.lookup(DOMAIN);
            CountDownLatch gate = new CountDownLatch(1);
            @SuppressWarnings("unchecked")
            Future<?>[] futures = new Future[16];
            long started = System.nanoTime();
            for (int worker = 0; worker < futures.length; worker++) {
                futures[worker] = pool.submit(() -> {
                    gate.await();
                    for (int i = 0; i < 250; i++) {
                        assertEquals(DnsObservation.Reason.MX_PRESENT, dns.lookup(DOMAIN).reason());
                    }
                    return null;
                });
            }
            gate.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            assertEquals(2, queries.get());
            System.out.printf(Locale.ROOT, "phase10 benchmark=concurrent_cache_hits requests=%d seconds=%.3f per_second=%.1f%n",
                    futures.length * 250, seconds, futures.length * 250 / seconds);
        }
    }

    @Test
    void rateLimiterCommandOverhead() throws Exception {
        RedisTokenBucket bucket = new RedisTokenBucket(redis);
        String key = "mailguard:benchmark:rate:v1:" + UUID.randomUUID();
        long[] samples = measure(RUNS, () -> assertTrue(bucket.take(key, RUNS + 1, 60).allowed()));
        report("rate_limiter_command", samples);
    }

    @Test
    void valkeyCommandLatency() throws Exception {
        String key = "mailguard:benchmark:command:v1:" + UUID.randomUUID();
        redis.opsForValue().set(key, "fixture", Duration.ofSeconds(60));
        long[] samples = measure(RUNS, () -> assertEquals("fixture", redis.opsForValue().get(key)));
        report("valkey_get_command", samples);
    }

    @Test
    void bulkValidationWithAndWithoutDnsCache() throws Exception {
        int rows = 300;
        for (boolean cacheEnabled : new boolean[]{false, true}) {
            FixtureDnsConfiguration.queries.set(0);
            String[] args = {
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.data.redis.host=" + valkey.getHost(),
                    "--spring.data.redis.port=" + valkey.getMappedPort(6379),
                    "--dns.cache.enabled=" + cacheEnabled,
                    "--valkey.namespace=" + namespace("bulk-benchmark-"),
                    "--jobs.api-key=private-benchmark-fixture",
                    "--jobs.worker.concurrency=20", "--jobs.worker.poll-millis=50",
                    "--imports.worker.poll-millis=50"
            };
            String[] apiArgs = Arrays.copyOf(args, args.length + 2);
            apiArgs[args.length] = "--server.port=0";
            apiArgs[args.length + 1] = "--jobs.worker.enabled=false";
            String[] workerArgs = Arrays.copyOf(args, args.length + 1);
            workerArgs[args.length] = "--jobs.worker.enabled=true";
            try (HttpClient client = HttpClient.newHttpClient();
                    ConfigurableApplicationContext api = new SpringApplicationBuilder(
                            MailGuardApplication.class, FixtureDnsConfiguration.class)
                            .web(WebApplicationType.SERVLET)
                            .run(apiArgs);
                    ConfigurableApplicationContext worker = new SpringApplicationBuilder(
                            MailGuardApplication.class, FixtureDnsConfiguration.class)
                            .web(WebApplicationType.NONE)
                            .run(workerArgs)) {
                String boundary = "benchmark-boundary";
                StringBuilder csv = new StringBuilder("email\n");
                for (int row = 0; row < rows; row++) {
                    csv.append("synthetic-").append(cacheEnabled).append('-').append(row)
                            .append('@').append(DOMAIN).append('\n');
                }
                byte[] payload = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                        + "filename=\"benchmark.csv\"\r\nContent-Type: text/csv\r\n\r\n"
                        + csv + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
                long started = System.nanoTime();
                HttpResponse<String> posted = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/imports"))
                                .header("X-Job-API-Key", "private-benchmark-fixture")
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, posted.statusCode(), posted.body());
                UUID id = UUID.fromString(json.readTree(posted.body()).get("id").asText());
                JdbcTemplate jdbc = api.getBean(JdbcTemplate.class);
                long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
                String state;
                do {
                    state = jdbc.queryForObject("SELECT state FROM import_batch WHERE id = ?", String.class, id);
                    if ("COMPLETED".equals(state)) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                } while (System.nanoTime() < deadline);
                assertEquals("COMPLETED", state);
                assertEquals(rows, jdbc.queryForObject("SELECT count(*) FROM import_row WHERE import_id = ?",
                        Integer.class, id));
                assertEquals(rows, jdbc.queryForObject("SELECT count(*) FROM verification_job WHERE id IN "
                        + "(SELECT job_id FROM import_row WHERE import_id = ?)", Integer.class, id));
                double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                System.out.printf(Locale.ROOT,
                        "phase10 benchmark=bulk_validation cache=%s rows=%d seconds=%.3f per_second=%.1f dns_queries=%d%n",
                        cacheEnabled, rows, seconds, rows / seconds, FixtureDnsConfiguration.queries.get());
            }
        }
    }

    private static DnsLookupService dns(boolean cached, AtomicInteger queries, MeterRegistry meters, int delayMillis)
            throws Exception {
        return new DnsLookupService(resolver(queries, delayMillis), redis, meters,
                namespace("dns-benchmark-"), cached, 30, 5, 8192, 128, 2500);
    }

    static String namespace(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 16);
    }

    private static Resolver resolver(AtomicInteger queries, int delayMillis) throws Exception {
        Resolver resolver = Mockito.mock(Resolver.class);
        Name mail = Name.fromString("mail.fixture.test.");
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> {
            Message query = invocation.getArgument(0);
            queries.incrementAndGet();
            if (delayMillis > 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMillis));
            Message answer = new Message(query.getHeader().getID());
            answer.getHeader().setFlag(Flags.QR);
            answer.addRecord(query.getQuestion(), Section.QUESTION);
            if (query.getQuestion().getType() == Type.MX) {
                answer.addRecord(new MXRecord(query.getQuestion().getName(), DClass.IN, 60, 10, mail), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                answer.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.1")), Section.ANSWER);
            }
            return answer;
        });
        return resolver;
    }

    private static long[] measure(int iterations, ThrowingAction action) throws Exception {
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            action.run();
            samples[i] = System.nanoTime() - started;
        }
        return samples;
    }

    private static void report(String name, long[] samples) {
        Arrays.sort(samples);
        long total = 0;
        for (long sample : samples) total += sample;
        System.out.printf(Locale.ROOT, "phase10 benchmark=%s requests=%d p50_us=%.1f p95_us=%.1f per_second=%.1f%n",
                name, samples.length, samples[samples.length / 2] / 1000.0,
                samples[(int) Math.ceil(samples.length * 0.95) - 1] / 1000.0,
                samples.length * 1_000_000_000.0 / total);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    @TestConfiguration
    static class FixtureDnsConfiguration {
        static final AtomicInteger queries = new AtomicInteger();

        @Bean
        @Primary
        DnsLookupService fixtureDns(StringRedisTemplate template, MeterRegistry registry,
                @Value("${dns.cache.enabled:false}") boolean cached,
                @Value("${valkey.namespace:local}") String namespace) throws Exception {
            return new DnsLookupService(resolver(queries, 2), template, registry,
                    namespace, cached, 30, 5, 8192, 128, 2500);
        }
    }
}
