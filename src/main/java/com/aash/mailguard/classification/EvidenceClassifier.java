package com.aash.mailguard.classification;

import com.aash.mailguard.dns.DnsObservation;
import com.aash.mailguard.smtp.SmtpObservation;
import com.aash.mailguard.smtp.SmtpReasonCode;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class EvidenceClassifier {
    public ClassificationResult classify(ValidationEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (!evidence.syntax().valid()) {
            return result(evidence, EvidenceClassification.INVALID_SYNTAX,
                    ClassificationReasonCode.SYNTAX_INVALID, EvidenceSource.SYNTAX, false,
                    "Invalid email syntax");
        }

        DnsObservation dns = evidence.dns();
        ValidationEvidence.SmtpEvidence smtp = evidence.smtp();
        boolean accepted = smtp != null && smtp.outcome() == SmtpObservation.Outcome.ACCEPTED
                && smtp.lastResponseCode() != null
                && (smtp.reason() == SmtpReasonCode.RECIPIENT_ACCEPTED && smtp.lastResponseCode() == 250
                    || smtp.reason() == SmtpReasonCode.RECIPIENT_ACCEPTED_FORWARD
                    && smtp.lastResponseCode() == 251);
        boolean rejected = smtp != null && smtp.outcome() == SmtpObservation.Outcome.REJECTED
                && smtp.reason() == SmtpReasonCode.RECIPIENT_NOT_FOUND
                && smtp.lastResponseCode() != null && smtp.lastResponseCode() == 550
                && "5.1.1".equals(smtp.enhancedStatusCode());
        boolean negativeDns = dns != null && (dns.reason() == DnsObservation.Reason.DOMAIN_NOT_FOUND
                && dns.outcome() == DnsObservation.Outcome.NO_MAIL_ROUTE
                || dns.reason() == DnsObservation.Reason.NULL_MX
                && dns.outcome() == DnsObservation.Outcome.MAIL_NOT_ACCEPTED
                || dns.reason() == DnsObservation.Reason.NO_MAIL_ROUTE
                && dns.outcome() == DnsObservation.Outcome.NO_MAIL_ROUTE
                || dns.reason() == DnsObservation.Reason.MX_TARGET_NO_ADDRESS
                && dns.outcome() == DnsObservation.Outcome.NO_MAIL_ROUTE);
        if (negativeDns && (accepted || rejected)) {
            return result(evidence, EvidenceClassification.ROUTE_UNCERTAIN,
                    ClassificationReasonCode.EVIDENCE_CONFLICT, EvidenceSource.DNS, false,
                    "DNS and SMTP observations conflict; current route and recipient are unconfirmed");
        }
        if (rejected) {
            return result(evidence, EvidenceClassification.RECIPIENT_REJECTED,
                    ClassificationReasonCode.SMTP_RECIPIENT_REJECTED, EvidenceSource.SMTP, false,
                    "SMTP returned 550 5.1.1 at RCPT; delivery not attempted");
        }
        if (accepted) {
            return result(evidence, EvidenceClassification.RECIPIENT_ACCEPTED_UNCONFIRMED,
                    ClassificationReasonCode.SMTP_RECIPIENT_ACCEPTED, EvidenceSource.SMTP,
                    dns != null && dns.retryable(), "SMTP accepted RCPT; mailbox existence and delivery unconfirmed");
        }
        if (dns == null) {
            boolean smtpObserved = smtp != null && smtp.outcome() != SmtpObservation.Outcome.DISABLED;
            return result(evidence, EvidenceClassification.ROUTE_UNCERTAIN,
                    smtpObserved ? ClassificationReasonCode.SMTP_UNCERTAIN : ClassificationReasonCode.DNS_NOT_OBSERVED,
                    smtpObserved ? EvidenceSource.SMTP : EvidenceSource.DNS,
                    smtp != null && smtp.retryable(), "DNS route not observed; mailbox unconfirmed");
        }
        if (dns.reason() == DnsObservation.Reason.DOMAIN_NOT_FOUND
                && dns.outcome() == DnsObservation.Outcome.NO_MAIL_ROUTE) {
            return result(evidence, EvidenceClassification.DOMAIN_NOT_FOUND,
                    ClassificationReasonCode.DOMAIN_NXDOMAIN, EvidenceSource.DNS, false,
                    "DNS reports domain not found; mailbox unconfirmed");
        }
        if (dns.reason() == DnsObservation.Reason.NULL_MX
                && dns.outcome() == DnsObservation.Outcome.MAIL_NOT_ACCEPTED) {
            return result(evidence, EvidenceClassification.MAIL_NOT_ACCEPTED,
                    ClassificationReasonCode.NULL_MX, EvidenceSource.DNS, false,
                    "Null MX declares domain does not accept mail; mailbox unconfirmed");
        }
        if (dns.outcome() == DnsObservation.Outcome.MAIL_ROUTE
                && (dns.reason() == DnsObservation.Reason.MX_PRESENT
                    || dns.reason() == DnsObservation.Reason.MULTIPLE_MX
                    || dns.reason() == DnsObservation.Reason.MX_TARGET_PARTIAL
                    || dns.reason() == DnsObservation.Reason.IMPLICIT_MX)) {
            return result(evidence, EvidenceClassification.ROUTE_AVAILABLE,
                    ClassificationReasonCode.DNS_ROUTE, EvidenceSource.DNS, dns.retryable(),
                    "DNS route observed; mailbox existence and delivery unconfirmed");
        }
        return result(evidence, EvidenceClassification.ROUTE_UNCERTAIN,
                negativeDns ? ClassificationReasonCode.NO_USABLE_ROUTE : ClassificationReasonCode.DNS_UNCERTAIN,
                EvidenceSource.DNS, dns.retryable() || smtp != null && smtp.retryable(),
                negativeDns ? "No usable DNS route observed; mailbox unconfirmed"
                        : "DNS route uncertain; mailbox unconfirmed");
    }
    private static ClassificationResult result(ValidationEvidence evidence, EvidenceClassification classification,
                                               ClassificationReasonCode reason, EvidenceSource source,
                                               boolean retryable, String diagnostic) {
        return new ClassificationResult(classification, reason, source, evidence, retryable, Instant.now(), diagnostic);
    }
}
