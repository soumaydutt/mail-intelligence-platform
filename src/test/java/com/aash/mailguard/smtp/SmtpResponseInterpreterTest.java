package com.aash.mailguard.smtp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmtpResponseInterpreterTest {
    private final SmtpResponseInterpreter interpreter = new SmtpResponseInterpreter();

    @Test
    void interpretsGreetingResponses() {
        SmtpResponse ready = new SmtpResponse(220, null, "mail.example.com ESMTP ready", List.of());
        var res1 = interpreter.interpretGreeting(ready);
        assertEquals(SmtpObservation.Outcome.ACCEPTED, res1.outcome());
        assertEquals(SmtpReasonCode.SERVICE_READY, res1.reasonCode());

        SmtpResponse tempFail = new SmtpResponse(421, "4.3.2", "Service not available", List.of());
        var res2 = interpreter.interpretGreeting(tempFail);
        assertEquals(SmtpObservation.Outcome.TEMPORARY_FAILURE, res2.outcome());
        assertEquals(SmtpReasonCode.SERVICE_UNAVAILABLE_TEMPORARY, res2.reasonCode());
        assertTrue(res2.retryable());

        SmtpResponse permFail = new SmtpResponse(554, null, "Access denied - blacklisted", List.of());
        var res3 = interpreter.interpretGreeting(permFail);
        assertEquals(SmtpObservation.Outcome.POLICY_REJECTION, res3.outcome());
        assertEquals(SmtpReasonCode.GREETING_REJECTED, res3.reasonCode());
        assertFalse(res3.retryable());
    }

    @ParameterizedTest
    @CsvSource({
            "250, 2.1.5, ACCEPTED, RECIPIENT_ACCEPTED, false",
            "251, 2.1.5, ACCEPTED, RECIPIENT_ACCEPTED_FORWARD, false",
            "252, 2.1.5, AMBIGUOUS, RECIPIENT_ACCEPTED_UNVERIFIED, false",
            "421, 4.4.2, TEMPORARY_FAILURE, SERVICE_UNAVAILABLE_TEMPORARY, true",
            "450, 4.2.1, TEMPORARY_FAILURE, MAILBOX_UNAVAILABLE_TEMPORARY, true",
            "451, 4.3.0, TEMPORARY_FAILURE, MAILBOX_UNAVAILABLE_TEMPORARY, true",
            "452, 4.2.2, TEMPORARY_FAILURE, INSUFFICIENT_STORAGE_TEMPORARY, true",
            "550, 5.1.1, REJECTED, RECIPIENT_NOT_FOUND, false",
            "550, 5.7.1, POLICY_REJECTION, POLICY_REJECTION, false",
            "550, , AMBIGUOUS, RECIPIENT_REJECTED_UNSPECIFIED, false",
            "550, 5.2.1, AMBIGUOUS, RECIPIENT_REJECTED_UNSPECIFIED, false",
            "551, 5.1.6, AMBIGUOUS, RECIPIENT_ACCEPTED_UNVERIFIED, false",
            "552, 5.2.2, UNCERTAIN, INSUFFICIENT_STORAGE_PERMANENT, false",
            "553, 5.1.3, AMBIGUOUS, MAILBOX_NAME_INVALID, false",
            "554, 5.7.1, POLICY_REJECTION, POLICY_REJECTION, false",
            "500, , UNCERTAIN, COMMAND_SYNTAX_ERROR, false",
            "501, , UNCERTAIN, COMMAND_SYNTAX_ERROR, false",
            "250, 5.1.1, UNCERTAIN, PROTOCOL_ERROR, false",
            "550, 4.1.1, UNCERTAIN, PROTOCOL_ERROR, false"
    })
    void interpretsRcptToCodes(int code, String enhanced,
                               SmtpObservation.Outcome expectedOutcome,
                               SmtpReasonCode expectedReason,
                               boolean expectedRetryable) {
        var interpretation = interpreter.interpretRcptTo(
                new SmtpResponse(code, enhanced, "Synthetic fixture reply", List.of()));
        assertEquals(expectedOutcome, interpretation.outcome());
        assertEquals(expectedReason, interpretation.reasonCode());
        assertEquals(expectedRetryable, interpretation.retryable());
    }

    @Test
    void textAloneCannotProveRecipientNonexistence() {
        var result = interpreter.interpretRcptTo(
                new SmtpResponse(550, null, "No such user here", List.of()));
        assertEquals(SmtpObservation.Outcome.AMBIGUOUS, result.outcome());
    }

    @Test
    void parsesMultiLineResponses() {
        List<String> raw = List.of(
                "250-smtp.example.com Hello",
                "250-SIZE 52428800",
                "250-8BITMIME",
                "250 2.1.5 Recipient OK"
        );
        SmtpResponse resp = SmtpResponse.parse(raw);
        assertEquals(250, resp.code());
        assertEquals("2.1.5", resp.enhancedStatusCode());
        assertEquals("2.1.5 Recipient OK", resp.message());
        assertEquals(4, resp.lines().size());
        assertTrue(resp.isPositiveCompletion());
    }
}
