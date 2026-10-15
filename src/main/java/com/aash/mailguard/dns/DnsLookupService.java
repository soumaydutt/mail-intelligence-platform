package com.aash.mailguard.dns;

import com.aash.mailguard.dns.DnsObservation.MxHost;
import com.aash.mailguard.dns.DnsObservation.Outcome;
import com.aash.mailguard.dns.DnsObservation.Reason;
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
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Component
public class DnsLookupService {
    private static final int MAX_IN_FLIGHT = 16;
    private static final int MAX_QUERIES = 24;
    private static final int MAX_ALIAS_HOPS = 8;
    private static final int MAX_MX_RECORDS = 32;

    private final Resolver resolver;
    private final Semaphore admission = new Semaphore(MAX_IN_FLIGHT);

    public DnsLookupService() {
        this(defaultResolver());
    }

    DnsLookupService(Resolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    private static Resolver defaultResolver() {
        ExtendedResolver configured = new ExtendedResolver();
        configured.setRetries(0);
        configured.setTimeout(Duration.ofSeconds(2));
        for (Resolver server : configured.getResolvers()) {
            server.setTimeout(Duration.ofSeconds(2));
        }
        return configured;
    }

    public Boolean hasMxRecord(String domain) {
        if (domain == null) {
            return null;
        }
        return lookup(domain).legacyMxRecordFound();
    }

    public DnsObservation lookup(String canonicalAsciiDomain) {
        Name owner;
        try {
            if (!validDomain(canonicalAsciiDomain)) {
                return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
            }
            owner = Name.fromString(canonicalAsciiDomain, Name.root);
        } catch (TextParseException e) {
            return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
        }

        if (!admission.tryAcquire()) {
            return observation(canonicalAsciiDomain, Reason.DNS_RESOLVER_UNAVAILABLE, List.of());
        }
        try {
            int[] queries = {0};
            Answer mxAnswer = resolve(owner, Type.MX, queries);
            if (mxAnswer.failure() != null) {
                return observation(canonicalAsciiDomain, mxAnswer.failure(), List.of());
            }
            if (!mxAnswer.records().isEmpty()) {
                List<MxHost> hosts = new ArrayList<>();
                boolean nullMx = false;
                for (Record record : mxAnswer.records()) {
                    if (!(record instanceof MXRecord mx) || hosts.size() + (nullMx ? 1 : 0) >= MAX_MX_RECORDS
                            || !mx.getTarget().isAbsolute()) {
                        return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
                    }
                    if (mx.getTarget().equals(Name.root)) {
                        if (mx.getPriority() != 0) {
                            return observation(canonicalAsciiDomain, Reason.DNS_MALFORMED_RESPONSE, List.of());
                        }
                        nullMx = true;
                    } else {
                        hosts.add(new MxHost(mx.getPriority(), mx.getTarget().toString()));
                    }
                }
                if (nullMx) {
                    return observation(canonicalAsciiDomain,
                            hosts.isEmpty() && mxAnswer.records().size() == 1 ? Reason.NULL_MX : Reason.DNS_MALFORMED_RESPONSE,
                            List.of());
                }
                hosts.sort(Comparator.comparingInt(MxHost::preference));
                return observation(canonicalAsciiDomain, hosts.size() == 1 ? Reason.MX_PRESENT : Reason.MULTIPLE_MX, hosts);
            }

            Reason failure = null;
            for (int type : new int[]{Type.A, Type.AAAA}) {
                Answer address = resolve(mxAnswer.owner(), type, queries);
                if (address.failure() != null) {
                    if (failure == null) {
                        failure = address.failure() == Reason.DOMAIN_NOT_FOUND
                                ? Reason.DNS_MALFORMED_RESPONSE : address.failure();
                    }
                } else if (!address.records().isEmpty()) {
                    return observation(canonicalAsciiDomain, Reason.IMPLICIT_MX, List.of());
                }
            }
            return observation(canonicalAsciiDomain, failure == null ? Reason.NO_MAIL_ROUTE : failure, List.of());
        } finally {
            admission.release();
        }
    }

    private Answer resolve(Name initial, int type, int[] queries) {
        Set<Name> seen = new HashSet<>();
        Name owner = initial;
        while (true) {
            if (!seen.add(owner)) {
                return new Answer(owner, List.of(), Reason.DNS_ALIAS_LOOP);
            }
            if (seen.size() > MAX_ALIAS_HOPS + 1) {
                return new Answer(owner, List.of(), Reason.DNS_ALIAS_DEPTH_EXCEEDED);
            }
            if (queries[0] == MAX_QUERIES) {
                return new Answer(owner, List.of(), Reason.DNS_RESOLVER_UNAVAILABLE);
            }
            queries[0]++;
            Message query = Message.newQuery(Record.newRecord(owner, type, DClass.IN));
            Message reply;
            try {
                reply = resolver.send(query);
            } catch (InterruptedIOException e) {
                return new Answer(owner, List.of(), Reason.DNS_TEMPORARY_FAILURE);
            } catch (IOException e) {
                return new Answer(owner, List.of(), Reason.DNS_RESOLVER_UNAVAILABLE);
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
            List<Record> matching = new ArrayList<>();
            List<Record> other = new ArrayList<>();
            Name alias = null;
            for (Record record : reply.getSectionArray(Section.ANSWER)) {
                if (record.getDClass() != DClass.IN) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                }
                if (!record.getName().equals(owner)) {
                    other.add(record);
                } else if (record instanceof CNAMERecord cname) {
                    if (alias != null || !cname.getTarget().isAbsolute() || cname.getTarget().equals(Name.root)) {
                        return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                    }
                    alias = cname.getTarget();
                } else if (record.getType() != type || !validRecord(record, type)) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                } else {
                    matching.add(record);
                    if (type == Type.MX && matching.size() > MAX_MX_RECORDS) {
                        return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                    }
                }
            }
            final Name resolvedAlias = alias;
            if ((alias == null && !other.isEmpty()) || (alias != null && (!matching.isEmpty()
                    || other.stream().anyMatch(record -> !record.getName().equals(resolvedAlias))))) {
                return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
            }
            if (alias == null) {
                // An empty NOERROR answer with a referral is not evidence of a missing route.
                if (reply.getHeader().getCount(Section.AUTHORITY) > 0
                        && !hasNegativeAuthority(reply)) {
                    return new Answer(owner, List.of(), Reason.DNS_MALFORMED_RESPONSE);
                }
                return new Answer(owner, matching, null);
            }
            owner = alias;
        }
    }

    private static boolean validRecord(Record record, int type) {
        return switch (type) {
            case Type.MX -> record instanceof MXRecord;
            case Type.A -> record instanceof ARecord address && address.getAddress() != null
                    && !address.getAddress().isAnyLocalAddress() && !address.getAddress().isMulticastAddress();
            case Type.AAAA -> record instanceof AAAARecord address && address.getAddress() != null
                    && !address.getAddress().isAnyLocalAddress() && !address.getAddress().isMulticastAddress();
            default -> false;
        };
    }

    private static boolean hasNegativeAuthority(Message reply) {
        for (Record record : reply.getSectionArray(Section.AUTHORITY)) {
            if (record.getType() == Type.SOA) {
                return true;
            }
        }
        return false;
    }

    private static boolean validDomain(String domain) {
        if (domain == null || domain.isEmpty() || domain.length() > 254) {
            return false;
        }
        int last = domain.endsWith(".") ? domain.length() - 1 : domain.length();
        if (last == 0 || last > 253) {
            return false;
        }
        int labelLength = 0;
        for (int i = 0; i < last; i++) {
            char c = domain.charAt(i);
            if (c == '.') {
                if (labelLength == 0 || labelLength > 63 || domain.charAt(i - 1) == '-') {
                    return false;
                }
                labelLength = 0;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || (c == '-' && labelLength > 0)) {
                labelLength++;
            } else {
                return false;
            }
        }
        return labelLength > 0 && labelLength <= 63 && domain.charAt(last - 1) != '-';
    }

    private static DnsObservation observation(String domain, Reason reason, List<MxHost> hosts) {
        Outcome outcome = switch (reason) {
            case MX_PRESENT, MULTIPLE_MX, IMPLICIT_MX -> Outcome.MAIL_ROUTE;
            case NULL_MX -> Outcome.MAIL_NOT_ACCEPTED;
            case DOMAIN_NOT_FOUND, NO_MAIL_ROUTE -> Outcome.NO_MAIL_ROUTE;
            default -> Outcome.UNKNOWN;
        };
        boolean retryable = reason == Reason.DNS_TEMPORARY_FAILURE || reason == Reason.DNS_REFUSED
                || reason == Reason.DNS_RESOLVER_UNAVAILABLE;
        return new DnsObservation(outcome, reason, domain, hosts, retryable, Instant.now());
    }

    private record Answer(Name owner, List<Record> records, Reason failure) { }
}
