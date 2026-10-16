package com.aash.mailguard.smtp;

public class SmtpResponseInterpreter {

    public record Interpretation(
            SmtpObservation.Outcome outcome,
            SmtpReasonCode reasonCode,
            String diagnostic,
            boolean retryable) { }

    public Interpretation interpretGreeting(SmtpResponse response) {
        if (response == null) {
            return uncertain("No SMTP greeting");
        }
        if (!consistent(response)) {
            return uncertain("Conflicting SMTP and enhanced status codes");
        }
        return switch (response.code()) {
            case 220 -> new Interpretation(SmtpObservation.Outcome.ACCEPTED,
                    SmtpReasonCode.SERVICE_READY, "Service ready", false);
            case 421 -> temporary(SmtpReasonCode.SERVICE_UNAVAILABLE_TEMPORARY);
            case 554 -> new Interpretation(SmtpObservation.Outcome.POLICY_REJECTION,
                    SmtpReasonCode.GREETING_REJECTED, "Greeting rejected", false);
            default -> uncertain("Unexpected SMTP greeting");
        };
    }

    public Interpretation interpretRcptTo(SmtpResponse response) {
        if (response == null) {
            return uncertain("No RCPT response");
        }
        int code = response.code();
        String enhanced = response.enhancedStatusCode();
        if (!consistent(response)) {
            return uncertain("Conflicting SMTP and enhanced status codes");
        }
        return switch (code) {
            case 250 -> new Interpretation(SmtpObservation.Outcome.ACCEPTED,
                    SmtpReasonCode.RECIPIENT_ACCEPTED, "Recipient accepted at RCPT stage", false);
            case 251 -> new Interpretation(SmtpObservation.Outcome.ACCEPTED,
                    SmtpReasonCode.RECIPIENT_ACCEPTED_FORWARD, "Recipient forwarded at RCPT stage", false);
            case 252, 551 -> new Interpretation(SmtpObservation.Outcome.AMBIGUOUS,
                    SmtpReasonCode.RECIPIENT_ACCEPTED_UNVERIFIED, "Recipient not verified", false);
            case 421 -> temporary(SmtpReasonCode.SERVICE_UNAVAILABLE_TEMPORARY);
            case 450, 451 -> temporary(SmtpReasonCode.MAILBOX_UNAVAILABLE_TEMPORARY);
            case 452 -> temporary(SmtpReasonCode.INSUFFICIENT_STORAGE_TEMPORARY);
            case 550 -> {
                if ("5.1.1".equals(enhanced)) {
                    yield new Interpretation(SmtpObservation.Outcome.REJECTED,
                            SmtpReasonCode.RECIPIENT_NOT_FOUND, "Recipient not found at RCPT stage", false);
                }
                if (enhanced != null && enhanced.startsWith("5.7.")) {
                    yield new Interpretation(SmtpObservation.Outcome.POLICY_REJECTION,
                            SmtpReasonCode.POLICY_REJECTION, "SMTP policy rejection", false);
                }
                yield new Interpretation(SmtpObservation.Outcome.AMBIGUOUS,
                        SmtpReasonCode.RECIPIENT_REJECTED_UNSPECIFIED, "Recipient rejected without conclusive evidence", false);
            }
            case 552 -> new Interpretation(SmtpObservation.Outcome.UNCERTAIN,
                    SmtpReasonCode.INSUFFICIENT_STORAGE_PERMANENT, "Permanent storage refusal", false);
            case 553 -> new Interpretation(SmtpObservation.Outcome.AMBIGUOUS,
                    SmtpReasonCode.MAILBOX_NAME_INVALID, "Mailbox name rejected", false);
            case 554 -> new Interpretation(SmtpObservation.Outcome.POLICY_REJECTION,
                    SmtpReasonCode.POLICY_REJECTION, "SMTP policy rejection", false);
            case 500, 501, 502, 503, 504 -> new Interpretation(SmtpObservation.Outcome.UNCERTAIN,
                    SmtpReasonCode.COMMAND_SYNTAX_ERROR, "SMTP command rejected", false);
            default -> {
                if (code >= 400 && code < 500) {
                    yield temporary(SmtpReasonCode.MAILBOX_UNAVAILABLE_TEMPORARY);
                }
                if (code >= 500 && code < 600) {
                    yield new Interpretation(SmtpObservation.Outcome.AMBIGUOUS,
                            SmtpReasonCode.RECIPIENT_REJECTED_UNSPECIFIED, "Unspecified SMTP refusal", false);
                }
                yield uncertain("Unexpected RCPT response");
            }
        };
    }

    static boolean consistent(SmtpResponse response) {
        String enhanced = response.enhancedStatusCode();
        return enhanced == null || (!enhanced.isEmpty()
                && enhanced.charAt(0) == Character.forDigit(response.code() / 100, 10));
    }

    private static Interpretation temporary(SmtpReasonCode reason) {
        return new Interpretation(SmtpObservation.Outcome.TEMPORARY_FAILURE, reason, "Temporary SMTP failure", true);
    }

    private static Interpretation uncertain(String diagnostic) {
        return new Interpretation(SmtpObservation.Outcome.UNCERTAIN,
                SmtpReasonCode.PROTOCOL_ERROR, diagnostic, false);
    }
}
