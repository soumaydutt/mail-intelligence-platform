package com.aash.mailguard.dns;

import com.aash.mailguard.dns.DnsObservation.MxHost;
import com.aash.mailguard.dns.DnsObservation.Outcome;
import com.aash.mailguard.dns.DnsObservation.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Flags;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.IDN;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class DnsLookupService {
    private static final int MAX_IN_FLIGHT = 16;
    private static final int MAX_QUERIES = 24;
    private static final int MAX_ALIAS_HOPS = 8;
    private static final int MAX_MX_RECORDS = 32;
    private static final int MAX_ANSWER_RECORDS = 128;

    private final Resolver resolver;
    private final DnsObservationCache cache;
    private final MeterRegistry metrics;
    private final Semaphore admission = new Semaphore(MAX_IN_FLIGHT);

    public DnsLookupService() {
        this(defaultResolver());
    }

    DnsLookupService(Resolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
        this.cache = null;
        this.metrics = null;
    }

    @Autowired
    public DnsLookupService(StringRedisTemplate redis, MeterRegistry metrics,
                            @Value("${valkey.namespace:local}") String namespace,
                            @Value("${dns.cache.enabled:false}") boolean enabled,
                            @Value("${dns.cache.positive-ttl-seconds:30}") int positiveTtlSeconds,
                            @Value("${dns.cache.negative-ttl-seconds:5}") int negativeTtlSeconds,
                            @Value("${dns.cache.max-entry-bytes:8192}") int maxEntryBytes,
                            @Value("${dns.cache.max-inflight:128}") int maxInflight,
                            @Value("${dns.cache.wait-millis:2500}") int waitMillis) {
        this(defaultResolver(), redis, metrics, namespace, enabled, positiveTtlSeconds, negativeTtlSeconds,
                maxEntryBytes, maxInflight, waitMillis);
    }

    DnsLookupService(Resolver resolver, StringRedisTemplate redis, MeterRegistry metrics, String namespace,
                     boolean enabled, int positiveTtlSeconds, int negativeTtlSeconds, int maxEntryBytes,
                     int maxInflight, int waitMillis) {
        this.resolver = Objects.requireNonNull(resolver);
        this.metrics = metrics;
        DnsObservationCache.validateSettings(namespace, positiveTtlSeconds, negativeTtlSeconds,
                maxEntryBytes, maxInflight, waitMillis);
        this.cache = enabled ? new DnsObservationCache(redis, metrics, namespace, positiveTtlSeconds,
                negativeTtlSeconds, maxEntryBytes, maxInflight, waitMillis) : null;
    }

    private static Resolver defaultResolver() {
        ExtendedResolver configured = new ExtendedResolver();
        configured.setRetries(1);
        configured.setTimeout(Duration.ofSeconds(2));
        for (Resolver server : configured.getResolvers()) {
            server.setTimeout(Duration.ofSeconds(2));
        }
        return configured;
    }

    private static String canonical(String domain) {
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        try {
            String ascii = IDN.toASCII(domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain,
                    IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            Name name = Name.fromString(ascii, Name.root);
            return name.isAbsolute() && ascii.length() <= 253 ? ascii : null;
        } catch (IllegalArgumentException | TextParseException e) {
            return null;
        }
    }

    public DnsObservation lookup(String canonicalAsciiDomain) {
        if (cache != null) {
            String normalized = canonical(canonicalAsciiDomain);
            if (normalized != null) {
                return cache.lookup(normalized, () -> lookupMeasured(normalized));
            }
        }
        long started = System.nanoTime();
        try {
            return lookupDirect(canonicalAsciiDomain, null);
        } finally {
            recordDnsDuration(started);
        }
    }

    private DnsObservationCache.Result lookupMeasured(String domain) {
        long started = System.nanoTime();
        TtlEvidence ttl = new TtlEvidence();
        try {
            DnsObservation observation = lookupDirect(domain, ttl);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + 1;
            return new DnsObservationCache.Result(observation,
                    Math.max(0, ttl.positive() * 1000 - elapsedMillis),
                    Math.max(0, ttl.negative() * 1000 - elapsedMillis));
        } finally {
            recordDnsDuration(started);
        }
    }

    private void recordDnsDuration(long started) {
        if (metrics != null) {
            metrics.timer("dns.resolver.lookup").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private DnsObservation lookupDirect(String canonicalAsciiDomain, TtlEvidence ttl) {
        Name owner;
        try {
            if (canonicalAsciiDomain == null || canonicalAsciiDomain.isEmpty()) {
                return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
            }
            owner = Name.fromString(canonicalAsciiDomain, Name.root);
        } catch (TextParseException e) {
            return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
        }
        if (resolver instanceof ExtendedResolver configured && configured.getResolver(0) == null) {
            return observation(canonicalAsciiDomain, Reason.DNS_RESOLVER_UNAVAILABLE, List.of());
        }

        if (!admission.tryAcquire()) {
            return observation(canonicalAsciiDomain, Reason.DNS_RESOLVER_UNAVAILABLE, List.of());
        }
        try {
            int[] queries = {0};
            Answer mxAnswer = resolve(owner, Type.MX, queries, ttl);
            if (mxAnswer.failure() != null) {
                return observation(canonicalAsciiDomain, mxAnswer.failure(), List.of());
            }
            if (!mxAnswer.records().isEmpty()) {
                List<Record> mxRecords = mxAnswer.records();
                boolean nullMx = false;
                for (Record record : mxRecords) {
                    if (!(record instanceof MXRecord mx) || mxRecords.size() > MAX_MX_RECORDS
                            || !mx.getTarget().isAbsolute()) {
                        return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
                    }
                    if (mx.getTarget().equals(Name.root)) {
                        if (mx.getPriority() != 0) {
                            return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
                        }
                        nullMx = true;
                    }
                }
                if (nullMx) {
                    return observation(canonicalAsciiDomain,
                            mxRecords.size() == 1 ? Reason.NULL_MX : Reason.DNS_MALFORMED_RESPONSE, List.of());
                }
                mxRecords.sort(Comparator.comparingInt(record -> ((MXRecord) record).getPriority()));
                List<MxHost> hosts = new ArrayList<>(mxRecords.size());
                for (Record record : mxRecords) {
                    MXRecord mx = (MXRecord) record;
                    hosts.add(new MxHost(mx.getPriority(), mx.getTarget().toString()));
                }
                Set<Name> inspected = mxRecords.size() > 1 ? new HashSet<>() : null;
                boolean temporaryFailure = false;
                boolean uncertainFailure = false;
                for (Record record : mxRecords) {
                    Name target = ((MXRecord) record).getTarget();
                    if (inspected != null && !inspected.add(target)) {
                        continue;
                    }
                    if (queries[0] == MAX_QUERIES) {
                        return observation(canonicalAsciiDomain, Reason.DNS_QUERY_BUDGET_EXCEEDED, hosts);
                    }
                    Reason address = addressEvidence(target, queries, true, ttl);
                    switch (address) {
                        case IMPLICIT_MX -> {
                            return observation(canonicalAsciiDomain,
                                    temporaryFailure || uncertainFailure ? Reason.MX_TARGET_PARTIAL
                                            : mxRecords.size() == 1 ? Reason.MX_PRESENT : Reason.MULTIPLE_MX, hosts);
                        }
                        case NO_MAIL_ROUTE, DOMAIN_NOT_FOUND -> { }
                        case DNS_TEMPORARY_FAILURE, DNS_REFUSED, DNS_RESOLVER_UNAVAILABLE -> temporaryFailure = true;
                        case DNS_QUERY_BUDGET_EXCEEDED -> {
                            return observation(canonicalAsciiDomain, address, hosts);
                        }
                        default -> uncertainFailure = true;
                    }
                }
                return observation(canonicalAsciiDomain,
                        temporaryFailure ? Reason.MX_TARGET_TEMPORARY_FAILURE
                                : uncertainFailure ? Reason.MX_TARGET_UNCERTAIN : Reason.MX_TARGET_NO_ADDRESS,
                        hosts);
            }

            Reason address = addressEvidence(mxAnswer.owner(), queries, false, ttl);
            return observation(canonicalAsciiDomain,
                    address == Reason.DOMAIN_NOT_FOUND ? Reason.DNS_MALFORMED_RESPONSE : address, List.of());
        } finally {
            admission.release();
        }
    }

    private Reason addressEvidence(Name owner, int[] queries, boolean mxTarget, TtlEvidence ttl) {
        Reason failure = null;
        boolean noData = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            int type = attempt == 0 ? Type.A : Type.AAAA;
            Answer address = resolve(owner, type, queries, ttl);
            if (address.failure() == Reason.DNS_QUERY_BUDGET_EXCEEDED) {
                return Reason.DNS_QUERY_BUDGET_EXCEEDED;
            }
            if (address.failure() == Reason.DOMAIN_NOT_FOUND) {
                if (failure != null) {
                    return failure;
                }
                return noData ? Reason.DNS_MALFORMED_RESPONSE : Reason.DOMAIN_NOT_FOUND;
            }
            if (address.failure() != null) {
                if (failure == null) {
                    failure = address.failure();
                }
            } else if (mxTarget && !address.owner().equals(owner)) {
                return failure == null ? Reason.DNS_ALIAS_FAILURE : failure;
            } else if (!address.records().isEmpty()) {
                return Reason.IMPLICIT_MX;
            } else {
                noData = true;
            }
        }
        return failure == null ? Reason.NO_MAIL_ROUTE : failure;
    }

    private Answer resolve(Name initial, int type, int[] queries, TtlEvidence ttl) {
        Set<Name> seen = new HashSet<>();
        Name owner = initial;
        List<Record> inline = null;
        while (true) {
            if (!seen.add(owner)) {
                return new Answer(owner, List.of(), Reason.DNS_ALIAS_LOOP);
            }
            if (seen.size() > MAX_ALIAS_HOPS + 1) {
                return new Answer(owner, List.of(), Reason.DNS_ALIAS_DEPTH_EXCEEDED);
            }
            Message reply = null;
            List<Record> answer;
            if (inline != null) {
                answer = inline;
                inline = null;
            } else {
                if (queries[0] == MAX_QUERIES) {
                    return new Answer(owner, List.of(), Reason.DNS_QUERY_BUDGET_EXCEEDED);
                }
                queries[0]++;
                Message query = Message.newQuery(Record.newRecord(owner, type, DClass.IN));
                try {
                    reply = resolver.send(query);
                } catch (IOException e) {
                    return new Answer(owner, List.of(), Reason.DNS_TEMPORARY_FAILURE);
                }
                if (reply == null || reply.getHeader().getID() != query.getHeader().getID()
                        || !reply.getHeader().getFlag(Flags.QR) || reply.getHeader().getFlag(Flags.TC)
                        || reply.getHeader().getOpcode() != Opcode.QUERY
                        || reply.getHeader().getCount(Section.QUESTION) != 1
                        || reply.getQuestion() == null || !reply.getQuestion().getName().equals(owner)
                        || reply.getQuestion().getType() != type || reply.getQuestion().getDClass() != DClass.IN) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                }
                switch (reply.getRcode()) {
                    case Rcode.NXDOMAIN -> {
                        if (reply.getHeader().getCount(Section.ANSWER) != 0) {
                            return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                        }
                        if (ttl != null) {
                            ttl.negative(reply, owner);
                        }
                        return new Answer(owner, List.of(), seen.size() > 1 ? Reason.DNS_ALIAS_FAILURE : Reason.DOMAIN_NOT_FOUND);
                    }
                    case Rcode.SERVFAIL -> {
                        return new Answer(owner, List.of(), Reason.DNS_TEMPORARY_FAILURE);
                    }
                    case Rcode.REFUSED -> {
                        return new Answer(owner, List.of(), Reason.DNS_REFUSED);
                    }
                    case Rcode.NOERROR -> { }
                    default -> {
                        return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                    }
                }
                answer = reply.getSection(Section.ANSWER);
            }
            if (answer.size() > MAX_ANSWER_RECORDS) {
                return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
            }
            List<Record> matching = null;
            Name alias = null;
            boolean other = false;
            for (Record record : answer) {
                if (record == null || record.getDClass() != DClass.IN) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                }
                if (!record.getName().equals(owner)) {
                    if (!seen.contains(record.getName())) {
                        other = true;
                    }
                } else if (record instanceof CNAMERecord cname) {
                    if (alias != null || !cname.getTarget().isAbsolute() || cname.getTarget().equals(Name.root)) {
                        return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                    }
                    alias = cname.getTarget();
                } else if (record.getType() != type || !validRecord(record, type)) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                } else {
                    if (matching == null) {
                        matching = new ArrayList<>();
                    }
                    matching.add(record);
                    if (type == Type.MX && matching.size() > MAX_MX_RECORDS) {
                        return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                    }
                }
            }
            if (ttl != null) {
                if (alias != null) {
                    for (Record record : answer) {
                        if (record.getName().equals(owner) && record instanceof CNAMERecord) {
                            ttl.positive(record.getTTL());
                        }
                    }
                } else if (matching != null) {
                    for (Record record : matching) {
                        ttl.positive(record.getTTL());
                    }
                }
            }
            if (alias != null) {
                if (matching != null) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                }
                boolean targetPresent = false;
                for (Record record : answer) {
                    if (record.getName().equals(alias)) {
                        targetPresent = true;
                        break;
                    }
                }
                if (other && !targetPresent) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                }
                if (targetPresent) {
                    inline = answer;
                }
                owner = alias;
                continue;
            }
            if (other) {
                return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
            }
            // An empty NOERROR answer with a referral is not evidence of a missing route.
            if (reply != null && reply.getHeader().getCount(Section.AUTHORITY) > 0
                    && !hasNegativeAuthority(reply)) {
                return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
            }
            if (ttl != null && matching == null && reply != null) {
                ttl.negative(reply, owner);
            }
            return new Answer(owner, matching == null ? List.of() : matching, null);
        }
    }

    private static final class TtlEvidence {
        private long positive = Long.MAX_VALUE;
        private long negative = Long.MAX_VALUE;
        private boolean negativeProved = true;

        void positive(long seconds) {
            positive = Math.min(positive, seconds);
        }

        void negative(Message reply, Name owner) {
            SOARecord soa = null;
            for (Record record : reply.getSection(Section.AUTHORITY)) {
                if (record instanceof SOARecord found && record.getDClass() == DClass.IN
                        && owner.subdomain(found.getName()) && soa == null) {
                    soa = found;
                } else {
                    negativeProved = false;
                }
            }
            if (soa == null) {
                negativeProved = false;
            } else {
                negative = Math.min(negative, Math.min(soa.getTTL(), soa.getMinimum()));
            }
        }

        long positive() {
            return positive == Long.MAX_VALUE ? 0 : positive;
        }

        long negative() {
            return negativeProved && negative != Long.MAX_VALUE
                    ? Math.min(negative, positive) : 0;
        }
    }

    private static boolean validRecord(Record record, int type) {
        return switch (type) {
            case Type.MX -> record instanceof MXRecord;
            case Type.A -> record instanceof ARecord address && usable(address.getAddress());
            case Type.AAAA -> record instanceof AAAARecord address && usable(address.getAddress());
            default -> false;
        };
    }

    private static boolean usable(InetAddress address) {
        return address != null && !address.isAnyLocalAddress() && !address.isMulticastAddress();
    }

    private static boolean hasNegativeAuthority(Message reply) {
        for (Record record : reply.getSection(Section.AUTHORITY)) {
            if (record.getType() == Type.SOA) {
                return true;
            }
        }
        return false;
    }

    private static DnsObservation observation(String domain, Reason reason, List<MxHost> hosts) {
        Outcome outcome = switch (reason) {
            case MX_PRESENT, MULTIPLE_MX, MX_TARGET_PARTIAL, IMPLICIT_MX -> Outcome.MAIL_ROUTE;
            case NULL_MX -> Outcome.MAIL_NOT_ACCEPTED;
            case DOMAIN_NOT_FOUND, NO_MAIL_ROUTE, MX_TARGET_NO_ADDRESS -> Outcome.NO_MAIL_ROUTE;
            default -> Outcome.UNKNOWN;
        };
        boolean retryable = reason == Reason.DNS_TEMPORARY_FAILURE || reason == Reason.DNS_REFUSED
                || reason == Reason.DNS_RESOLVER_UNAVAILABLE || reason == Reason.MX_TARGET_TEMPORARY_FAILURE
                || reason == Reason.DNS_QUERY_BUDGET_EXCEEDED || reason == Reason.MX_TARGET_PARTIAL;
        return new DnsObservation(outcome, reason, domain, hosts, retryable, Instant.now());
    }

    private record Answer(Name owner, List<Record> records, Reason failure) { }
}
