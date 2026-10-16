package com.aash.mailguard.classification;

import com.aash.mailguard.dns.DnsObservation;
import com.aash.mailguard.smtp.SmtpObservation;
import com.aash.mailguard.smtp.SmtpReasonCode;
import com.aash.mailguard.validator.EmailFormatValidator;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ValidationEvidence(EmailFormatValidator.Result syntax, Instant syntaxObservedAt,
                                 DnsObservation dns, SmtpEvidence smtp) {
    public ValidationEvidence {
        Objects.requireNonNull(syntax, "syntax must not be null");
        Objects.requireNonNull(syntax.reason(), "syntax reason must not be null");
        Objects.requireNonNull(syntaxObservedAt, "syntaxObservedAt must not be null");
        // shortcut: observations have no expiry/TTL, add freshness only when a cache policy defines it.
    }

    public record SmtpEvidence(SmtpObservation.Outcome outcome, SmtpReasonCode reason,
                               Integer lastResponseCode, String enhancedStatusCode,
                               boolean retryable, Instant observedAt) {
        private static final Pattern ENHANCED_CODE = Pattern.compile("[245]\\.[0-9]{1,3}\\.[0-9]{1,3}");

        public SmtpEvidence {
            Objects.requireNonNull(outcome, "SMTP outcome must not be null");
            Objects.requireNonNull(reason, "SMTP reason must not be null");
            Objects.requireNonNull(observedAt, "SMTP observedAt must not be null");
            if (enhancedStatusCode != null && !ENHANCED_CODE.matcher(enhancedStatusCode).matches()) {
                enhancedStatusCode = null;
            }
        }

        public static SmtpEvidence from(SmtpObservation observation) {
            Objects.requireNonNull(observation, "SMTP observation must not be null");
            return new SmtpEvidence(observation.outcome(), observation.reason(), observation.lastResponseCode(),
                    observation.enhancedStatusCode(), observation.retryable(), observation.observedAt());
        }
    }
}
