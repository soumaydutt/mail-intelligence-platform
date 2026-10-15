package com.aash.mailguard.dns;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DnsObservation(Outcome outcome, Reason reason, String domain, List<MxHost> mxHosts,
                             boolean retryable, Instant observedAt) {
    public DnsObservation {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(mxHosts);
        Objects.requireNonNull(observedAt);
        mxHosts = List.copyOf(mxHosts);
    }

    public Boolean legacyMxRecordFound() {
        return switch (reason) {
            case MX_PRESENT, MULTIPLE_MX -> true;
            case NULL_MX, IMPLICIT_MX, DOMAIN_NOT_FOUND, NO_MAIL_ROUTE -> false;
            default -> null;
        };
    }

    public enum Outcome { MAIL_ROUTE, MAIL_NOT_ACCEPTED, NO_MAIL_ROUTE, UNKNOWN }

    public enum Reason {
        MX_PRESENT, MULTIPLE_MX, NULL_MX, IMPLICIT_MX, DOMAIN_NOT_FOUND, NO_MAIL_ROUTE,
        DNS_TEMPORARY_FAILURE, DNS_REFUSED, DNS_RESOLVER_UNAVAILABLE, DNS_MALFORMED_RESPONSE,
        DNS_ALIAS_LOOP, DNS_ALIAS_DEPTH_EXCEEDED, DNS_ALIAS_FAILURE
    }

    public record MxHost(int preference, String host) {
        public MxHost {
            Objects.requireNonNull(host);
        }
    }
}
