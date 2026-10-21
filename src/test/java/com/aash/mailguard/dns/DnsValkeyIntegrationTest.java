package com.aash.mailguard.dns;

import com.aash.mailguard.dns.DnsObservation.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
class DnsValkeyIntegrationTest {
    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        if (connection != null) connection.destroy();
    }

    @Test
    void positiveHitKeepsOriginalTimestampAndExpiresAtAuthoritativeTtl() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        DnsLookupService service = service(query -> {
            queries.incrementAndGet();
            Message response = reply(query, Rcode.NOERROR, query.getQuestion().getType() == Type.MX);
            if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 1,
                        InetAddress.getByName("192.0.2.5")), Section.ANSWER);
            }
            return response;
        }, "positive", 30, 5, 8192);
        DnsObservation original = service.lookup("fixture.test");
        DnsObservation cached = service.lookup("FIXTURE.TEST");
        assertEquals(Reason.IMPLICIT_MX, cached.reason());
        assertEquals(original.observedAt(), cached.observedAt());
        assertEquals(2, queries.get());
        String key = key("positive", "fixture.test");
        long remainingMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(remainingMillis > 0 && remainingMillis <= 1000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (redis.hasKey(key) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        assertFalse(redis.hasKey(key));
        service.lookup("fixture.test");
        assertEquals(4, queries.get());
    }

    @Test
    void negativeProofCachesButTransientAndAmbiguousNegativesDoNot() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        DnsLookupService service = service(query -> {
            int count = queries.incrementAndGet();
            return reply(query, count == 1 ? Rcode.SERVFAIL : Rcode.NXDOMAIN, count > 2);
        }, "negative", 30, 5, 8192);
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, service.lookup("fixture.test").reason());
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        assertEquals(3, queries.get());
        long ttl = redis.getExpire(key("negative", "fixture.test"));
        assertTrue(ttl > 0 && ttl <= 5);
    }

    @Test
    void corruptAndOversizeEntriesMissWithoutFalseNegative() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        DnsLookupService service = service(query -> {
            queries.incrementAndGet();
            return reply(query, Rcode.SERVFAIL, false);
        }, "corrupt", 30, 5, 128);
        String key = key("corrupt", "fixture.test");
        redis.opsForValue().set(key, "garbage", Duration.ofSeconds(5));
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, service.lookup("fixture.test").reason());
        redis.opsForValue().set(key, "a".repeat(129), Duration.ofSeconds(5));
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, service.lookup("fixture.test").reason());
        assertEquals(2, queries.get());
        assertFalse(redis.opsForValue().get(key).contains("fixture.test"));
    }

    private DnsLookupService service(Answer answer, String namespace, int positive, int negative, int maxBytes)
            throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(call -> answer.send(call.getArgument(0)));
        return new DnsLookupService(resolver, redis, metrics, namespace, true, positive, negative, maxBytes, 8, 500);
    }

    private String key(String namespace, String domain) {
        return new DnsObservationCache(redis, metrics, namespace, 30, 5, 8192, 8, 500).key(domain);
    }

    private static Message reply(Message query, int code, boolean soa) throws Exception {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.getHeader().setRcode(code);
        response.addRecord(query.getQuestion(), Section.QUESTION);
        if (soa) response.addRecord(new SOARecord(Name.fromString("test."), DClass.IN, 60,
                Name.fromString("ns.test."), Name.fromString("hostmaster.test."), 1, 2, 3, 4, 10), Section.AUTHORITY);
        return response;
    }

    @FunctionalInterface
    private interface Answer {
        Message send(Message query) throws Exception;
    }
}
