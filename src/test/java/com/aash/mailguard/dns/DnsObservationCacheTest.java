package com.aash.mailguard.dns;

import com.aash.mailguard.dns.DnsObservation.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DnsObservationCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    DnsObservationCacheTest() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void disabledNeverTouchesRedisAndSettingsAreValidated() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        DnsLookupService disabled = service(query -> {
            queries.incrementAndGet();
            return answer(query, Rcode.NXDOMAIN, false);
        }, false, 128, 2, 100);
        disabled.lookup("fixture.test");
        disabled.lookup("fixture.test");
        assertEquals(2, queries.get());
        verify(redis, never()).opsForValue();
        new DnsLookupService(mock(Resolver.class), null, null, "local",
                false, 30, 5, 8192, 128, 100);
        assertThrows(IllegalArgumentException.class, () -> new DnsLookupService(mock(Resolver.class), redis, metrics,
                "bad:namespace", true, 30, 5, 8192, 128, 100));
        assertThrows(IllegalArgumentException.class, () -> new DnsLookupService(mock(Resolver.class), redis, metrics,
                "local", false, 0, 5, 8192, 128, 100));
        assertThrows(IllegalArgumentException.class, () -> new DnsLookupService(mock(Resolver.class), redis, metrics,
                "local", true, 30, 5, 64, 128, 100));
        assertThrows(IllegalArgumentException.class, () -> new DnsLookupService(mock(Resolver.class), redis, metrics,
                "local", true, 30, 5, 8192, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new DnsLookupService(mock(Resolver.class), redis, metrics,
                "local", true, 30, 5, 8192, 128, 0));
    }

    @Test
    void keyIsHashedAndCanonicalIdnCaseUnifiesLookup() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        List<Name> names = new ArrayList<>();
        DnsLookupService service = service(query -> {
            queries.incrementAndGet();
            names.add(query.getQuestion().getName());
            return answer(query, Rcode.NXDOMAIN, true);
        }, true, 8192, 2, 100);
        String domain = "xn--bcher-kva.test";
        String key = new DnsObservationCache(redis, metrics, "local", 30, 5, 8192, 2, 100).key(domain);
        assertFalse(key.contains(domain));
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("BÜCHER.TEST").reason());
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("XN--BCHER-KVA.TEST").reason());
        assertEquals(List.of(Name.fromString(domain + "."), Name.fromString(domain + ".")), names);
        verify(values, org.mockito.Mockito.times(2)).get(eq(key), eq(0L), eq(8192L));
        verify(values, org.mockito.Mockito.times(2)).set(eq(key), any(String.class),
                org.mockito.ArgumentMatchers.<Duration>argThat(ttl -> ttl.toMillis() > 0 && ttl.toMillis() <= 5000));
    }

    private DnsLookupService service(Answer answer, boolean enabled, int maxBytes, int inflight, int waitMillis)
            throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> answer.send(invocation.getArgument(0)));
        return new DnsLookupService(resolver, redis, metrics, "local", enabled, 30, 5, maxBytes, inflight, waitMillis);
    }

    private static Message answer(Message query, int code, boolean soa) throws Exception {
        Message reply = new Message(query.getHeader().getID());
        reply.getHeader().setFlag(Flags.QR);
        reply.addRecord(query.getQuestion(), Section.QUESTION);
        reply.getHeader().setRcode(code);
        if (soa) {
            reply.addRecord(new SOARecord(Name.fromString("test."), DClass.IN, 12,
                    Name.fromString("ns.test."), Name.fromString("hostmaster.test."),
                    1, 2, 3, 4, 9), Section.AUTHORITY);
        }
        return reply;
    }

    @FunctionalInterface
    private interface Answer {
        Message send(Message query) throws Exception;
    }
}
