package com.aash.mailguard.smtp;

import java.time.Instant;
import java.util.Objects;

public record SmtpObservation(
        Outcome outcome,
        SmtpReasonCode reason,
        String host,
        int port,
        Integer lastResponseCode,
        String enhancedStatusCode,
        String diagnostic,
        boolean retryable,
        Instant observedAt) {

    public SmtpObservation {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        TEMPORARY_FAILURE,
        POLICY_REJECTION,
        AMBIGUOUS,
        UNCERTAIN,
        DISABLED
    }

    public static SmtpObservation disabled(String host, SmtpReasonCode reason) {
        return new SmtpObservation(
                Outcome.DISABLED,
                reason,
                host,
                0,
                null,
                null,
                "SMTP verification disabled by policy",
                false,
                Instant.now()
        );
    }
}
