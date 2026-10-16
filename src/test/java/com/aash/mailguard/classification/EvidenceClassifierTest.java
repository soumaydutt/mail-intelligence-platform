package com.aash.mailguard.classification;

import com.aash.mailguard.dns.DnsObservation;
import com.aash.mailguard.smtp.SmtpObservation;
import com.aash.mailguard.smtp.SmtpReasonCode;
import com.aash.mailguard.validator.EmailFormatValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.aash.mailguard.classification.EvidenceClassification.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceClassifierTest {
    private static final Instant SYNTAX_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DNS_TIME = SYNTAX_TIME.plusSeconds(1);
    private static final Instant SMTP_TIME = SYNTAX_TIME.plusSeconds(2);
    private final EmailFormatValidator validator = new EmailFormatValidator();
    private final EvidenceClassifier classifier = new EvidenceClassifier();

    @Test
    void invalidSyntaxTakesPrecedenceOverContradictoryLaterEvidence() {
        ValidationEvidence evidence = evidence(validator.validate("bad"), dns(DnsObservation.Outcome.MAIL_ROUTE,
                DnsObservation.Reason.MX_PRESENT, false), smtp(SmtpObservation.Outcome.ACCEPTED,
                SmtpReasonCode.RECIPIENT_ACCEPTED, 250, "2.1.5", false));
        ClassificationResult result = classifier.classify(evidence);
        assertEquals(INVALID_SYNTAX, result.classification());
        assertEquals(EvidenceSource.SYNTAX, result.source());
        assertEquals(EmailFormatValidator.Reason.MISSING_AT, result.evidence().syntax().reason());
        assertFalse(result.retryable());
        assertEquals(SYNTAX_TIME, result.evidence().syntaxObservedAt());
    }

    @Test
    void dnsNegativeReasonsNeverInventRecipientInvalidity() {
        assertClassification(DOMAIN_NOT_FOUND, ClassificationReasonCode.DOMAIN_NXDOMAIN,
                dns(DnsObservation.Outcome.NO_MAIL_ROUTE, DnsObservation.Reason.DOMAIN_NOT_FOUND, false), null);
        assertClassification(MAIL_NOT_ACCEPTED, ClassificationReasonCode.NULL_MX,
                dns(DnsObservation.Outcome.MAIL_NOT_ACCEPTED, DnsObservation.Reason.NULL_MX, false), null);
        assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.NO_USABLE_ROUTE,
                dns(DnsObservation.Outcome.NO_MAIL_ROUTE, DnsObservation.Reason.NO_MAIL_ROUTE, false), null);
        assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.NO_USABLE_ROUTE,
                dns(DnsObservation.Outcome.NO_MAIL_ROUTE, DnsObservation.Reason.MX_TARGET_NO_ADDRESS, false), null);
    }

    @Test
    void positiveRoutingStillLeavesMailboxUnverified() {
        for (DnsObservation.Reason reason : List.of(DnsObservation.Reason.MX_PRESENT,
                DnsObservation.Reason.MULTIPLE_MX, DnsObservation.Reason.IMPLICIT_MX,
                DnsObservation.Reason.MX_TARGET_PARTIAL)) {
            boolean retryable = reason == DnsObservation.Reason.MX_TARGET_PARTIAL;
            ClassificationResult result = classifier.classify(evidence(validator.validate("a@example.test"),
                    dns(DnsObservation.Outcome.MAIL_ROUTE, reason, retryable), null));
            assertEquals(ROUTE_AVAILABLE, result.classification());
            assertEquals(EvidenceSource.DNS, result.source());
            assertEquals(retryable, result.retryable());
            assertEquals(DNS_TIME, result.evidence().dns().observedAt());
            assertTrue(result.diagnostic().contains("unconfirmed"));
        }
    }

    @Test
    void unavailableDnsAndSmtpDoNotBecomeNegative() {
        assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.DNS_NOT_OBSERVED, null, null);
        for (DnsObservation.Reason reason : List.of(DnsObservation.Reason.DNS_TEMPORARY_FAILURE,
                DnsObservation.Reason.DNS_QUERY_BUDGET_EXCEEDED,
                DnsObservation.Reason.MX_TARGET_TEMPORARY_FAILURE)) {
            ClassificationResult result = classifier.classify(evidence(validator.validate("a@example.test"),
                    dns(DnsObservation.Outcome.UNKNOWN, reason, true), null));
            assertEquals(ROUTE_UNCERTAIN, result.classification());
            assertTrue(result.retryable());
            assertEquals(reason, result.evidence().dns().reason());
        }
        assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.DNS_NOT_OBSERVED, null,
                smtp(SmtpObservation.Outcome.DISABLED, SmtpReasonCode.DISABLED_BY_POLICY, null, null, false));
        ClassificationResult temporary = classifier.classify(evidence(validator.validate("a@example.test"), null,
                smtp(SmtpObservation.Outcome.TEMPORARY_FAILURE,
                        SmtpReasonCode.MAILBOX_UNAVAILABLE_TEMPORARY, 450, "4.2.0", true)));
        assertEquals(ROUTE_UNCERTAIN, temporary.classification());
        assertEquals(ClassificationReasonCode.SMTP_UNCERTAIN, temporary.reason());
        assertTrue(temporary.retryable());
    }

    @Test
    void recipientAcceptanceIsOnlyRecipientStageEvidence() {
        for (int code : List.of(250, 251)) {
            SmtpReasonCode reason = code == 250 ? SmtpReasonCode.RECIPIENT_ACCEPTED
                    : SmtpReasonCode.RECIPIENT_ACCEPTED_FORWARD;
            ClassificationResult result = classifier.classify(evidence(validator.validate("a@example.test"),
                    dns(DnsObservation.Outcome.MAIL_ROUTE, DnsObservation.Reason.MX_PRESENT, false),
                    smtp(SmtpObservation.Outcome.ACCEPTED, reason, code, "2.1.5", false)));
            assertEquals(RECIPIENT_ACCEPTED_UNCONFIRMED, result.classification());
            assertEquals(ClassificationReasonCode.SMTP_RECIPIENT_ACCEPTED, result.reason());
            assertEquals(EvidenceSource.SMTP, result.source());
            assertTrue(result.diagnostic().contains("unconfirmed"));
        }
        assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.SMTP_UNCERTAIN, null,
                smtp(SmtpObservation.Outcome.AMBIGUOUS, SmtpReasonCode.RECIPIENT_ACCEPTED_UNVERIFIED,
                        252, null, false));
    }

    @Test
    void onlyContextualRecipientNotFoundMayRejectRecipient() {
        assertClassification(RECIPIENT_REJECTED, ClassificationReasonCode.SMTP_RECIPIENT_REJECTED, null,
                smtp(SmtpObservation.Outcome.REJECTED, SmtpReasonCode.RECIPIENT_NOT_FOUND,
                        550, "5.1.1", false));
        for (ValidationEvidence.SmtpEvidence observation : List.of(
                smtp(SmtpObservation.Outcome.AMBIGUOUS, SmtpReasonCode.RECIPIENT_REJECTED_UNSPECIFIED,
                        550, null, false),
                smtp(SmtpObservation.Outcome.POLICY_REJECTION, SmtpReasonCode.POLICY_REJECTION,
                        550, "5.7.1", false),
                smtp(SmtpObservation.Outcome.REJECTED, SmtpReasonCode.RECIPIENT_NOT_FOUND,
                        550, "5.7.1", false),
                smtp(SmtpObservation.Outcome.REJECTED, SmtpReasonCode.RECIPIENT_NOT_FOUND,
                        552, "5.1.1", false))) {
            assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.SMTP_UNCERTAIN, null, observation);
        }
    }

    @Test
    void conflictingDnsAndSmtpProduceUncertaintyRegardlessOfArrivalOrder() {
        for (DnsObservation.Reason reason : List.of(DnsObservation.Reason.DOMAIN_NOT_FOUND,
                DnsObservation.Reason.NULL_MX, DnsObservation.Reason.NO_MAIL_ROUTE)) {
            DnsObservation.Outcome outcome = reason == DnsObservation.Reason.NULL_MX
                    ? DnsObservation.Outcome.MAIL_NOT_ACCEPTED : DnsObservation.Outcome.NO_MAIL_ROUTE;
            for (SmtpObservation.Outcome smtpOutcome : List.of(SmtpObservation.Outcome.ACCEPTED,
                    SmtpObservation.Outcome.REJECTED)) {
                ClassificationResult result = classifier.classify(evidence(validator.validate("a@example.test"),
                        dns(outcome, reason, false), smtpOutcome == SmtpObservation.Outcome.ACCEPTED
                                ? smtp(smtpOutcome, SmtpReasonCode.RECIPIENT_ACCEPTED, 250, "2.1.5", false)
                                : smtp(smtpOutcome, SmtpReasonCode.RECIPIENT_NOT_FOUND, 550, "5.1.1", false)));
                assertEquals(ROUTE_UNCERTAIN, result.classification());
                assertEquals(ClassificationReasonCode.EVIDENCE_CONFLICT, result.reason());
            }
        }
    }

    @Test
    void evidenceSnapshotExcludesSmtpHostAndRawDiagnosticAndCopiesDnsHosts() {
        List<DnsObservation.MxHost> hosts = new ArrayList<>();
        hosts.add(new DnsObservation.MxHost(10, "mx.example.test."));
        DnsObservation dns = new DnsObservation(DnsObservation.Outcome.MAIL_ROUTE,
                DnsObservation.Reason.MX_PRESENT, "example.test", hosts, false, DNS_TIME);
        SmtpObservation raw = new SmtpObservation(SmtpObservation.Outcome.REJECTED,
                SmtpReasonCode.RECIPIENT_NOT_FOUND, "private-recipient@secret.test", 2525, 550,
                "5.1.1", "550 5.1.1 private-recipient@secret.test", false, SMTP_TIME);
        ValidationEvidence evidence = evidence(validator.validate("a@example.test"), dns,
                ValidationEvidence.SmtpEvidence.from(raw));
        hosts.clear();
        ClassificationResult result = classifier.classify(evidence);
        assertEquals(1, result.evidence().dns().mxHosts().size());
        assertEquals(SMTP_TIME, result.evidence().smtp().observedAt());
        assertEquals("5.1.1", result.evidence().smtp().enhancedStatusCode());
        assertFalse(result.toString().contains("private-recipient"));
        assertThrows(UnsupportedOperationException.class, () -> evidence.dns().mxHosts().clear());
        ValidationEvidence.SmtpEvidence forged = smtp(SmtpObservation.Outcome.REJECTED,
                SmtpReasonCode.RECIPIENT_NOT_FOUND, 550, "5.1.1\r\nprivate-recipient@secret.test", false);
        assertNull(forged.enhancedStatusCode());
        assertClassification(ROUTE_UNCERTAIN, ClassificationReasonCode.SMTP_UNCERTAIN, null, forged);
    }

    private void assertClassification(EvidenceClassification expected, ClassificationReasonCode reason,
                                      DnsObservation dns, ValidationEvidence.SmtpEvidence smtp) {
        ClassificationResult result = classifier.classify(evidence(validator.validate("a@example.test"), dns, smtp));
        assertEquals(expected, result.classification());
        assertEquals(reason, result.reason());
        assertNotNull(result.classifiedAt());
    }

    private static ValidationEvidence evidence(EmailFormatValidator.Result syntax, DnsObservation dns,
                                               ValidationEvidence.SmtpEvidence smtp) {
        return new ValidationEvidence(syntax, SYNTAX_TIME, dns, smtp);
    }

    private static DnsObservation dns(DnsObservation.Outcome outcome, DnsObservation.Reason reason,
                                      boolean retryable) {
        return new DnsObservation(outcome, reason, "example.test", List.of(), retryable, DNS_TIME);
    }

    private static ValidationEvidence.SmtpEvidence smtp(SmtpObservation.Outcome outcome,
                                                         SmtpReasonCode reason, Integer code,
                                                         String enhanced, boolean retryable) {
        return new ValidationEvidence.SmtpEvidence(outcome, reason, code, enhanced, retryable, SMTP_TIME);
    }
}
