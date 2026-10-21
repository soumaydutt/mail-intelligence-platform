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

    @Test
    void soaProofControlsNegativeCaching() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsLookupService unproved = service(query -> {
            calls.incrementAndGet();
            return answer(query, Rcode.NXDOMAIN, false);
        }, true, 8192, 2, 100);
        unproved.lookup("fixture.test");
        unproved.lookup("fixture.test");
        assertEquals(2, calls.get());
        verify(values, never()).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void soaNegativeTtlIsMinimumOfRecordAndSoaMinimum() throws Exception {
        DnsLookupService service = service(query -> {
            Message response = answer(query, Rcode.NXDOMAIN, false);
            response.addRecord(new SOARecord(Name.fromString("test."), DClass.IN, 3,
                    Name.fromString("ns.test."), Name.fromString("hostmaster.test."),
                    1, 2, 3, 4, 2), Section.AUTHORITY);
            return response;
        }, true, 8192, 2, 100);
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        verify(values).set(any(String.class), any(String.class),
                org.mockito.ArgumentMatchers.<Duration>argThat(ttl -> ttl.toMillis() > 0 && ttl.toMillis() <= 2000));
    }

    @Test
    void mxAndAddressTtlMinimumWins() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsLookupService service = service(query -> {
            calls.incrementAndGet();
            Message reply = answer(query, Rcode.NOERROR, false);
            if (query.getQuestion().getType() == Type.MX) {
                reply.addRecord(new MXRecord(query.getQuestion().getName(), DClass.IN, 19, 10,
                        Name.fromString("mail.fixture.test.")), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                reply.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 7,
                        InetAddress.getByName("192.0.2.1")), Section.ANSWER);
            }
            return reply;
        }, true, 8192, 2, 100);
        assertEquals(Reason.MX_PRESENT, service.lookup("fixture.test").reason());
        verify(values).set(any(String.class), any(String.class),
                org.mockito.ArgumentMatchers.<Duration>argThat(ttl -> ttl.toMillis() > 0 && ttl.toMillis() <= 7000));
        assertEquals(2, calls.get());
    }

    @Test
    void implicitAddressRouteRequiresProvedMxAbsence() throws Exception {
        DnsLookupService unproved = service(query -> {
            Message response = answer(query, Rcode.NOERROR, false);
            if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.5")), Section.ANSWER);
            }
            return response;
        }, true, 8192, 2, 100);
        assertEquals(Reason.IMPLICIT_MX, unproved.lookup("fixture.test").reason());
        verify(values, never()).set(any(String.class), any(String.class), any(Duration.class));

        DnsLookupService proved = service(query -> {
            int type = query.getQuestion().getType();
            Message response = answer(query, Rcode.NOERROR, type == Type.MX);
            if (type == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.5")), Section.ANSWER);
            }
            return response;
        }, true, 8192, 2, 100);
        assertEquals(Reason.IMPLICIT_MX, proved.lookup("fixture.test").reason());
        verify(values).set(any(String.class), any(String.class),
                org.mockito.ArgumentMatchers.<Duration>argThat(ttl -> ttl.toMillis() > 0 && ttl.toMillis() <= 9000));
    }

    @Test
    void zeroTtlNeverCaches() throws Exception {
        DnsLookupService zero = service(query -> {
            Message response = answer(query, Rcode.NOERROR, false);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(new MXRecord(query.getQuestion().getName(), DClass.IN, 0, 10,
                        Name.fromString("mail.fixture.test.")), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.1")), Section.ANSWER);
            }
            return response;
        }, true, 8192, 2, 100);
        assertEquals(Reason.MX_PRESENT, zero.lookup("fixture.test").reason());
        verify(values, never()).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void oversizedObservationIsNotWritten() throws Exception {
        String domain = "a".repeat(50) + "." + "b".repeat(50) + ".test";
        DnsLookupService service = service(query -> {
            Message response = answer(query, Rcode.NOERROR, false);
            if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.5")), Section.ANSWER);
            }
            return response;
        }, true, 128, 2, 100);
        assertEquals(Reason.IMPLICIT_MX, service.lookup(domain).reason());
        verify(values, never()).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void transientFailuresAreNeverCachedAndRedisOutageFailsOpenThenRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsLookupService service = service(query -> {
            int index = calls.incrementAndGet();
            if (index == 1) throw new InterruptedIOException("timeout");
            return answer(query, index == 2 ? Rcode.SERVFAIL : Rcode.NXDOMAIN, true);
        }, true, 8192, 2, 100);
        when(values.get(any(String.class), anyLong(), anyLong())).thenThrow(new IllegalStateException("outage"))
                .thenReturn(null);
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, service.lookup("fixture.test").reason());
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, service.lookup("fixture.test").reason());
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        assertEquals(3, calls.get());
        verify(values).set(any(String.class), any(String.class),
                org.mockito.ArgumentMatchers.<Duration>argThat(ttl -> ttl.toMillis() > 0 && ttl.toMillis() <= 5000));
    }

    @Test
    void cacheReadTimeoutFallsBackToFreshDnsAndNextRequestRecovers() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        DnsLookupService service = service(query -> {
            queries.incrementAndGet();
            return answer(query, Rcode.NXDOMAIN, true);
        }, true, 8192, 2, 100);
        when(values.get(any(String.class), anyLong(), anyLong()))
                .thenThrow(new QueryTimeoutException("fixture read timeout")).thenReturn(null);
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        verify(values, never()).set(any(String.class), any(String.class), any(Duration.class));
        assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup("fixture.test").reason());
        assertEquals(2, queries.get());
        verify(values).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void cacheWriteTimeoutPreservesDnsEvidenceAndLaterWriteRecovers() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        DnsLookupService service = service(query -> {
            queries.incrementAndGet();
            Message response = answer(query, Rcode.NOERROR, false);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(new MXRecord(query.getQuestion().getName(), DClass.IN, 60, 10,
                        Name.fromString("mail.fixture.test.")), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.5")), Section.ANSWER);
            }
            return response;
        }, true, 8192, 2, 100);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("fixture write timeout")).doNothing()
                .when(values).set(any(String.class), any(String.class), any(Duration.class));
        assertEquals(Reason.MX_PRESENT, service.lookup("fixture.test").reason());
        assertEquals(Reason.MX_PRESENT, service.lookup("fixture.test").reason());
        assertEquals(4, queries.get());
        verify(values, org.mockito.Mockito.times(2)).set(any(String.class), any(String.class), any(Duration.class));
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
