package com.aash.mailguard.dns;

import com.aash.mailguard.dns.DnsObservation.MxHost;
import com.aash.mailguard.dns.DnsObservation.Outcome;
import com.aash.mailguard.dns.DnsObservation.Reason;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DnsLookupServiceTest {
    private static final String DOMAIN = "fixture.test";

    @Test
    void singleMxRequiresTargetAddressBeforeReportingRoute() throws Exception {
        List<Integer> types = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            types.add(query.getQuestion().getType());
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                assertEquals(Name.fromString("mail.fixture.test."), query.getQuestion().getName());
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.1")), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Outcome.MAIL_ROUTE, result.outcome());
        assertEquals(Reason.MX_PRESENT, result.reason());
        assertEquals(List.of(new MxHost(10, "mail.fixture.test.")), result.mxHosts());
        assertEquals(List.of(Type.MX, Type.A), types);
        assertTrue(result.legacyMxRecordFound());
        assertFalse(result.retryable());
        assertEquals(DOMAIN, result.domain());
        assertNotNull(result.observedAt());
    }

    @Test
    void temporarilyUnresolvableMxTargetDoesNotProveAUsableRoute() throws Exception {
        List<Integer> queried = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            queried.add(query.getQuestion().getType());
            if (query.getQuestion().getType() == Type.A) {
                return reply(query, Rcode.SERVFAIL);
            }
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertTrue(result.legacyMxRecordFound());
        assertTrue(result.retryable());
        assertEquals(List.of(Type.MX, Type.A, Type.AAAA), queried);
    }

    @Test
    void mxTargetWithoutAddressIsNotAUsableRoute() throws Exception {
        Resolver resolver = fixture(query -> {
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Outcome.NO_MAIL_ROUTE, result.outcome());
        assertTrue(result.legacyMxRecordFound());
    }

    @Test
    void mxTargetCnameDoesNotClaimAStandardsCompliantRoute() throws Exception {
        Resolver resolver = fixture(query -> {
            Name owner = query.getQuestion().getName();
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            } else if (owner.equals(Name.fromString("mail.fixture.test."))) {
                response.addRecord(new CNAMERecord(owner, DClass.IN, 60,
                        Name.fromString("alias.fixture.test.")), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(owner, DClass.IN, 60,
                        InetAddress.getByName("192.0.2.5")), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.MX_TARGET_UNCERTAIN, result.reason());
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertTrue(result.legacyMxRecordFound());
    }

    @Test
    void conflictingAddressAnswersNeverTurnMxIntoAFalseNegative() throws Exception {
        for (int aRcode : new int[]{Rcode.NOERROR, Rcode.SERVFAIL}) {
            Resolver resolver = fixture(query -> {
                if (query.getQuestion().getType() == Type.AAAA) {
                    return reply(query, Rcode.NXDOMAIN);
                }
                Message response = reply(query, aRcode == Rcode.SERVFAIL
                        && query.getQuestion().getType() == Type.A ? Rcode.SERVFAIL : Rcode.NOERROR);
                if (query.getQuestion().getType() == Type.MX) {
                    response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
                }
                return response;
            });

            DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals(aRcode == Rcode.SERVFAIL ? Reason.MX_TARGET_TEMPORARY_FAILURE
                    : Reason.MX_TARGET_UNCERTAIN, result.reason());
            assertEquals(aRcode == Rcode.SERVFAIL, result.retryable());
            assertTrue(result.legacyMxRecordFound());
        }
    }

    @Test
    void temporaryPreferredMxAndUsableBackupAreDegradedNotInvalid() throws Exception {
        List<Name> queried = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            Name owner = query.getQuestion().getName();
            queried.add(owner);
            if (query.getQuestion().getType() == Type.A && owner.equals(Name.fromString("primary.fixture.test."))) {
                return reply(query, Rcode.SERVFAIL);
            }
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 30, "unused.fixture.test."), Section.ANSWER);
                response.addRecord(mx(query, 10, "backup.fixture.test."), Section.ANSWER);
                response.addRecord(mx(query, 5, "primary.fixture.test."), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A
                    && owner.equals(Name.fromString("backup.fixture.test."))) {
                response.addRecord(new ARecord(owner, DClass.IN, 60,
                        InetAddress.getByName("192.0.2.2")), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Outcome.MAIL_ROUTE, result.outcome());
        assertEquals(Reason.MX_TARGET_PARTIAL, result.reason());
        assertTrue(result.retryable());
        assertTrue(result.legacyMxRecordFound());
        assertEquals(List.of(new MxHost(5, "primary.fixture.test."),
                new MxHost(10, "backup.fixture.test."), new MxHost(30, "unused.fixture.test.")), result.mxHosts());
        assertEquals(List.of(Name.fromString("fixture.test."), Name.fromString("primary.fixture.test."),
                Name.fromString("primary.fixture.test."), Name.fromString("backup.fixture.test.")), queried);
    }

    @Test
    void mxTargetAaaaRouteSurvivesTemporaryAFailure() throws Exception {
        Resolver resolver = fixture(query -> {
            if (query.getQuestion().getType() == Type.A) {
                return reply(query, Rcode.SERVFAIL);
            }
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.AAAA) {
                response.addRecord(new AAAARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("2001:db8::1")), Section.ANSWER);
            }
            return response;
        });

        assertEquals(Reason.MX_PRESENT, new DnsLookupService(resolver).lookup(DOMAIN).reason());
    }

    @Test
    void repeatedMxTargetIsQueriedOnlyOnce() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Resolver resolver = fixture(query -> {
            queries.incrementAndGet();
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 20, "same.fixture.test."), Section.ANSWER);
                response.addRecord(mx(query, 10, "same.fixture.test."), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.MX_TARGET_NO_ADDRESS, result.reason());
        assertEquals(2, result.mxHosts().size());
        assertEquals(3, queries.get());
    }

    @Test
    void uninspectedMxTargetsStayUnknownAtQueryBudget() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Resolver resolver = fixture(query -> {
            queries.incrementAndGet();
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                for (int i = 0; i < 32; i++) {
                    response.addRecord(mx(query, i, "mail" + i + ".fixture.test."), Section.ANSWER);
                }
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.DNS_QUERY_BUDGET_EXCEEDED, result.reason());
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals(32, result.mxHosts().size());
        assertTrue(result.legacyMxRecordFound());
        assertTrue(result.retryable());
        assertEquals(24, queries.get());
    }

    @Test
    void exhaustedAaaaBudgetKeepsObservedMxAndRetryableUnknown() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Resolver resolver = fixture(query -> {
            queries.incrementAndGet();
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                for (int i = 0; i < 12; i++) {
                    response.addRecord(mx(query, i, "mail" + i + ".fixture.test."), Section.ANSWER);
                }
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.DNS_QUERY_BUDGET_EXCEEDED, result.reason());
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertTrue(result.retryable());
        assertTrue(result.legacyMxRecordFound());
        assertEquals(24, queries.get());
    }

    @Test
    void malformedTargetAnswerRemainsUncertainWithoutMailboxClaim() throws Exception {
        Resolver resolver = fixture(query -> {
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                response.addRecord(mx(query, 10, "other.fixture.test."), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.MX_TARGET_UNCERTAIN, result.reason());
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertTrue(result.legacyMxRecordFound());
        assertFalse(result.retryable());
    }

    @Test
    void multipleMxAreSortedByPreferenceWithStableTies() throws Exception {
        List<Name> queried = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            queried.add(query.getQuestion().getName());
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.MX) {
                response.addRecord(mx(query, 30, "last.fixture.test."), Section.ANSWER);
                response.addRecord(mx(query, 10, "first.fixture.test."), Section.ANSWER);
                response.addRecord(mx(query, 10, "equal.fixture.test."), Section.ANSWER);
            } else if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.3")), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.MULTIPLE_MX, result.reason());
        assertEquals(List.of(new MxHost(10, "first.fixture.test."),
                new MxHost(10, "equal.fixture.test."), new MxHost(30, "last.fixture.test.")), result.mxHosts());
        assertEquals(List.of(Name.fromString("fixture.test."), Name.fromString("first.fixture.test.")), queried);
    }

    @Test
    void exactNullMxDeclinesMailWithoutAddressFallback() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Resolver resolver = fixture(query -> {
            queries.incrementAndGet();
            Message response = reply(query, Rcode.NOERROR);
            response.addRecord(mx(query, 0, "."), Section.ANSWER);
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Outcome.MAIL_NOT_ACCEPTED, result.outcome());
        assertEquals(Reason.NULL_MX, result.reason());
        assertFalse(result.legacyMxRecordFound());
        assertEquals(1, queries.get());
    }

    @Test
    void mixedNullMxCannotBeTreatedAsDeliverableOrAbsent() throws Exception {
        Resolver resolver = fixture(query -> {
            Message response = reply(query, Rcode.NOERROR);
            response.addRecord(mx(query, 0, "."), Section.ANSWER);
            response.addRecord(mx(query, 10, "mail.fixture.test."), Section.ANSWER);
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.DNS_MALFORMED_RESPONSE, result.reason());
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertNull(result.legacyMxRecordFound());
    }

    @Test
    void addressFallbackFindsAWithoutClaimingExplicitMx() throws Exception {
        List<Integer> types = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            types.add(query.getQuestion().getType());
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("192.0.2.1")), Section.ANSWER);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.IMPLICIT_MX, result.reason());
        assertEquals(Outcome.MAIL_ROUTE, result.outcome());
        assertFalse(result.legacyMxRecordFound());
        assertEquals(List.of(Type.MX, Type.A), types);
    }

    @Test
    void addressFallbackFindsAaaaWhenAIsMissing() throws Exception {
        List<Integer> types = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            types.add(query.getQuestion().getType());
            Message response = reply(query, Rcode.NOERROR);
            if (query.getQuestion().getType() == Type.AAAA) {
                response.addRecord(new AAAARecord(query.getQuestion().getName(), DClass.IN, 60,
                        InetAddress.getByName("2001:db8::1")), Section.ANSWER);
            }
            return response;
        });

        assertEquals(Reason.IMPLICIT_MX, new DnsLookupService(resolver).lookup(DOMAIN).reason());
        assertEquals(List.of(Type.MX, Type.A, Type.AAAA), types);
    }

    @Test
    void noMxOrAddressIsNoObservedRoute() throws Exception {
        List<Integer> types = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            types.add(query.getQuestion().getType());
            return reply(query, Rcode.NOERROR);
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.NO_MAIL_ROUTE, result.reason());
        assertEquals(Outcome.NO_MAIL_ROUTE, result.outcome());
        assertFalse(result.legacyMxRecordFound());
        assertEquals(List.of(Type.MX, Type.A, Type.AAAA), types);
    }

    @Test
    void transientAddressFailureCannotBeReportedAsAbsent() throws Exception {
        Resolver resolver = fixture(query -> query.getQuestion().getType() == Type.A
                ? reply(query, Rcode.SERVFAIL) : reply(query, Rcode.NOERROR));

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, result.reason());
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertNull(result.legacyMxRecordFound());
        assertTrue(result.retryable());
    }

    @Test
    void nxdomainDoesNotTriggerAddressFallback() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Resolver resolver = fixture(query -> {
            queries.incrementAndGet();
            return reply(query, Rcode.NXDOMAIN);
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.DOMAIN_NOT_FOUND, result.reason());
        assertFalse(result.legacyMxRecordFound());
        assertEquals(1, queries.get());
    }

    @Test
    void servfailTimeoutRefusedAndIoRemainUnknown() throws Exception {
        for (int rcode : new int[]{Rcode.SERVFAIL, Rcode.REFUSED}) {
            DnsObservation result = new DnsLookupService(fixture(query -> reply(query, rcode))).lookup(DOMAIN);
            assertEquals(rcode == Rcode.SERVFAIL ? Reason.DNS_TEMPORARY_FAILURE : Reason.DNS_REFUSED, result.reason());
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertTrue(result.retryable());
            assertNull(result.legacyMxRecordFound());
        }
        Resolver timeout = fixture(query -> { throw new InterruptedIOException("timeout"); });
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, new DnsLookupService(timeout).lookup(DOMAIN).reason());
        Resolver io = fixture(query -> { throw new IOException("resolver error"); });
        assertEquals(Reason.DNS_TEMPORARY_FAILURE, new DnsLookupService(io).lookup(DOMAIN).reason());
    }

    @Test
    void malformedOrMismatchedResponsesAreUnknown() throws Exception {
        Resolver wrongType = fixture(query -> {
            Message response = reply(query, Rcode.NOERROR);
            response.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, 60,
                    InetAddress.getByName("192.0.2.1")), Section.ANSWER);
            return response;
        });
        assertEquals(Reason.DNS_MALFORMED_RESPONSE, new DnsLookupService(wrongType).lookup(DOMAIN).reason());
        Resolver mismatchedQuestion = fixture(query -> {
            Message response = reply(query, Rcode.NOERROR);
            response.removeAllRecords(Section.QUESTION);
            response.addRecord(org.xbill.DNS.Record.newRecord(Name.fromString("other.test."), Type.MX, DClass.IN),
                    Section.QUESTION);
            return response;
        });
        assertEquals(Reason.DNS_MALFORMED_RESPONSE,
                new DnsLookupService(mismatchedQuestion).lookup(DOMAIN).reason());
    }

    @Test
    void cnameChainAndSameAnswerTargetMxResolveWithoutDuplicateMxQuery() throws Exception {
        Name firstAlias = Name.fromString("alias.fixture.test.");
        Name finalAlias = Name.fromString("final.fixture.test.");
        List<Name> queried = new ArrayList<>();
        Resolver resolver = fixture(query -> {
            Name owner = query.getQuestion().getName();
            queried.add(owner);
            Message response = reply(query, Rcode.NOERROR);
            if (owner.equals(Name.fromString("fixture.test."))) {
                response.addRecord(new CNAMERecord(owner, DClass.IN, 60, firstAlias), Section.ANSWER);
            } else if (owner.equals(firstAlias)) {
                response.addRecord(new CNAMERecord(owner, DClass.IN, 60, finalAlias), Section.ANSWER);
                response.addRecord(new MXRecord(finalAlias, DClass.IN, 60, 5,
                        Name.fromString("mail.final.fixture.test.")), Section.ANSWER);
            } else if (owner.equals(Name.fromString("mail.final.fixture.test."))
                    && query.getQuestion().getType() == Type.A) {
                response.addRecord(new ARecord(owner, DClass.IN, 60,
                        InetAddress.getByName("192.0.2.4")), Section.ANSWER);
            } else {
                throw new AssertionError("Unnecessary alias lookup: " + owner);
            }
            return response;
        });

        DnsObservation result = new DnsLookupService(resolver).lookup(DOMAIN);
        assertEquals(Reason.MX_PRESENT, result.reason());
        assertEquals(List.of(new MxHost(5, "mail.final.fixture.test.")), result.mxHosts());
        assertEquals(List.of(Name.fromString("fixture.test."), firstAlias,
                Name.fromString("mail.final.fixture.test.")), queried);
    }

    @Test
    void cnameLoopDepthAndMissingAliasAreUncertain() throws Exception {
        Resolver loop = fixture(query -> {
            Message response = reply(query, Rcode.NOERROR);
            Name next = query.getQuestion().getName().equals(Name.fromString("fixture.test."))
                    ? Name.fromString("alias.fixture.test.") : Name.fromString("fixture.test.");
            response.addRecord(new CNAMERecord(query.getQuestion().getName(), DClass.IN, 60, next), Section.ANSWER);
            return response;
        });
        assertEquals(Reason.DNS_ALIAS_LOOP, new DnsLookupService(loop).lookup(DOMAIN).reason());
        AtomicInteger depthQueries = new AtomicInteger();
        Resolver depth = fixture(query -> {
            int count = depthQueries.incrementAndGet();
            Message response = reply(query, Rcode.NOERROR);
            response.addRecord(new CNAMERecord(query.getQuestion().getName(), DClass.IN, 60,
                    Name.fromString("hop" + count + ".fixture.test.")), Section.ANSWER);
            return response;
        });
        assertEquals(Reason.DNS_ALIAS_DEPTH_EXCEEDED, new DnsLookupService(depth).lookup(DOMAIN).reason());
        assertTrue(depthQueries.get() <= 9);
        Resolver missingAlias = fixture(query -> query.getQuestion().getName().equals(Name.fromString("fixture.test."))
                ? cnameReply(query, "alias.fixture.test.") : reply(query, Rcode.NXDOMAIN));
        assertEquals(Reason.DNS_ALIAS_FAILURE, new DnsLookupService(missingAlias).lookup(DOMAIN).reason());
    }

    @Test
    void invalidInputDoesNotIssueNetworkQueriesOrUseSearchSuffixes() throws Exception {
        Resolver resolver = mock(Resolver.class);
        assertEquals(Reason.DNS_MALFORMED_RESPONSE, new DnsLookupService(resolver).lookup(null).reason());
        assertEquals(Reason.DNS_MALFORMED_RESPONSE, new DnsLookupService(resolver).lookup("bad..fixture.test").reason());
        verifyNoInteractions(resolver);

        List<Name> queried = new ArrayList<>();
        Resolver absolute = fixture(query -> {
            queried.add(query.getQuestion().getName());
            return reply(query, Rcode.NXDOMAIN);
        });
        assertEquals(Reason.DOMAIN_NOT_FOUND, new DnsLookupService(absolute).lookup("fixture.test").reason());
        assertEquals(List.of(Name.fromString("fixture.test.")), queried);
    }

    @Test
    void concurrentCallsShareBoundedAdmissionWithoutCrossRequestCache() throws Exception {
        int permits = 16;
        CountDownLatch entered = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Resolver resolver = fixture(query -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture timed out");
            }
            return reply(query, Rcode.NXDOMAIN);
        });
        DnsLookupService service = new DnsLookupService(resolver);
        try (ExecutorService executor = Executors.newFixedThreadPool(permits)) {
            List<Future<DnsObservation>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < permits; i++) {
                    futures.add(executor.submit(() -> service.lookup(DOMAIN)));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                DnsObservation saturated = service.lookup(DOMAIN);
                assertEquals(Reason.DNS_RESOLVER_UNAVAILABLE, saturated.reason());
                assertTrue(saturated.retryable());
            } finally {
                release.countDown();
            }
            for (Future<DnsObservation> future : futures) {
                assertEquals(Reason.DOMAIN_NOT_FOUND, future.get(5, TimeUnit.SECONDS).reason());
            }
            assertEquals(permits, calls.get());
            assertEquals(Reason.DOMAIN_NOT_FOUND, service.lookup(DOMAIN).reason());
            assertEquals(permits + 1, calls.get());
        }
    }

    private static MXRecord mx(Message query, int preference, String host) throws Exception {
        return new MXRecord(query.getQuestion().getName(), DClass.IN, 60, preference, Name.fromString(host));
    }

    private static Message cnameReply(Message query, String alias) throws Exception {
        Message response = reply(query, Rcode.NOERROR);
        response.addRecord(new CNAMERecord(query.getQuestion().getName(), DClass.IN, 60,
                Name.fromString(alias)), Section.ANSWER);
        return response;
    }

    private static Message reply(Message query, int rcode) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.addRecord(query.getQuestion(), Section.QUESTION);
        response.getHeader().setRcode(rcode);
        return response;
    }

    private static Resolver fixture(Reply answer) throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> answer.send(invocation.getArgument(0)));
        return resolver;
    }

    @FunctionalInterface
    private interface Reply {
        Message send(Message query) throws Exception;
    }
}
