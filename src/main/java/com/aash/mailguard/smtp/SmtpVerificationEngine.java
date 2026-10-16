package com.aash.mailguard.smtp;

import com.aash.mailguard.validator.EmailFormatValidator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;

@Component
public class SmtpVerificationEngine {
    private static final int DEFAULT_SMTP_PORT = 25;
    private static final String MAIL_FROM = "verify@mailguard.local";
    private static final String HELO_DOMAIN = "localhost";

    private final SmtpVerificationPolicy policy;
    private final SmtpTransportFactory transportFactory;
    private final SmtpResponseInterpreter interpreter;
    private final EmailFormatValidator validator = new EmailFormatValidator();
    private final Semaphore permits;

    public SmtpVerificationEngine() {
        this(SmtpVerificationPolicy.disabled(), SocketSmtpTransport::new);
    }

    public SmtpVerificationEngine(SmtpVerificationPolicy policy, SmtpTransportFactory transportFactory) {
        this(policy, transportFactory, new SmtpResponseInterpreter());
    }

    public SmtpVerificationEngine(SmtpVerificationPolicy policy, SmtpTransportFactory transportFactory,
                                  SmtpResponseInterpreter interpreter) {
        this.policy = java.util.Objects.requireNonNull(policy);
        this.transportFactory = java.util.Objects.requireNonNull(transportFactory);
        this.interpreter = java.util.Objects.requireNonNull(interpreter);
        this.permits = new Semaphore(policy.maxConcurrent());
    }

    public SmtpVerificationPolicy getPolicy() {
        return policy;
    }

    public SmtpObservation verify(String email, List<String> mxHosts) {
        if (!policy.enabled()) {
            return SmtpObservation.disabled("none", SmtpReasonCode.DISABLED_BY_POLICY);
        }
        if (mxHosts == null || mxHosts.isEmpty()) {
            return observation(SmtpObservation.Outcome.UNCERTAIN, SmtpReasonCode.CONNECTION_FAILED,
                    "none", DEFAULT_SMTP_PORT, null, false, "No MX host available");
        }
        return verify(email, mxHosts.getFirst(), DEFAULT_SMTP_PORT);
    }

    public SmtpObservation verify(String email, String host, int port) {
        if (!policy.enabled()) {
            return SmtpObservation.disabled("none", SmtpReasonCode.DISABLED_BY_POLICY);
        }
        if (!validEnvelope(email)) {
            return observation(SmtpObservation.Outcome.UNCERTAIN, SmtpReasonCode.INVALID_RECIPIENT_SYNTAX,
                    "none", port, null, false, "Invalid SMTP recipient syntax");
        }
        InetAddress destination = policy.destination(host, port);
        if (destination == null) {
            return SmtpObservation.disabled("none", SmtpReasonCode.HOST_NOT_PERMITTED);
        }
        if (Thread.currentThread().isInterrupted()) {
            return observation(SmtpObservation.Outcome.UNCERTAIN, SmtpReasonCode.PROTOCOL_ERROR,
                    host, port, null, true, "SMTP probe interrupted");
        }
        if (!permits.tryAcquire()) {
            return observation(SmtpObservation.Outcome.TEMPORARY_FAILURE, SmtpReasonCode.CAPACITY_EXCEEDED,
                    host, port, null, true, "SMTP fixture capacity exceeded");
        }

        long started = System.nanoTime();
        boolean connected = false;
        try (SmtpTransport transport = transportFactory.create()) {
            // 1. Connect
            transport.connect(destination, port, remaining(started, policy.connectTimeout()));
            connected = true;

            // 2. Greeting
            SmtpResponse greeting = transport.readGreeting(remaining(started, policy.readTimeout()));
            SmtpResponseInterpreter.Interpretation greeted = interpreter.interpretGreeting(greeting);
            if (greeted.outcome() != SmtpObservation.Outcome.ACCEPTED) {
                return interpreted(greeted, host, port, greeting);
            }

            // 3. EHLO / HELO
            SmtpResponse ehlo = transport.sendCommand("EHLO " + HELO_DOMAIN, remaining(started, policy.readTimeout()));
            if (ehlo != null && SmtpResponseInterpreter.consistent(ehlo)
                    && (ehlo.code() == 500 || ehlo.code() == 502 || ehlo.code() == 504)) {
                ehlo = transport.sendCommand("HELO " + HELO_DOMAIN, remaining(started, policy.readTimeout()));
            }
            if (ehlo == null || ehlo.code() != 250 || !SmtpResponseInterpreter.consistent(ehlo)) {
                return commandFailure(ehlo, host, port, SmtpReasonCode.EHLO_REJECTED);
            }

            // 4. MAIL FROM
            SmtpResponse mailFrom = transport.sendCommand("MAIL FROM:<" + MAIL_FROM + ">",
                    remaining(started, policy.readTimeout()));
            if (mailFrom == null || mailFrom.code() != 250 || !SmtpResponseInterpreter.consistent(mailFrom)) {
                return commandFailure(mailFrom, host, port, SmtpReasonCode.MAIL_FROM_REJECTED);
            }

            // 5. RCPT TO
            SmtpResponse rcpt = transport.sendCommand("RCPT TO:<" + email + ">",
                    remaining(started, policy.readTimeout()));
            SmtpResponseInterpreter.Interpretation result = interpreter.interpretRcptTo(rcpt);
            SmtpObservation observation = interpreted(result, host, port, rcpt);
            // 6. QUIT
            quietQuit(transport, started);
            return observation;
        } catch (InterruptedIOException e) {
            if (e instanceof SocketTimeoutException) {
                return observation(SmtpObservation.Outcome.TEMPORARY_FAILURE, SmtpReasonCode.CONNECTION_TIMED_OUT,
                        host, port, null, true, "SMTP timeout");
            }
            Thread.currentThread().interrupt();
            return observation(SmtpObservation.Outcome.UNCERTAIN, SmtpReasonCode.PROTOCOL_ERROR,
                    host, port, null, true, "SMTP probe interrupted");
        } catch (IOException e) {
            return observation(SmtpObservation.Outcome.UNCERTAIN,
                    connected ? SmtpReasonCode.PROTOCOL_ERROR : SmtpReasonCode.CONNECTION_FAILED,
                    host, port, null, true, "SMTP connection or protocol failure");
        } finally {
            permits.release();
        }
    }

    private boolean validEnvelope(String email) {
        if (!validator.validate(email).valid()) {
            return false;
        }
        for (int i = 0; i < email.length(); i++) {
            if (email.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private Duration remaining(long started, Duration stageLimit) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("SMTP probe interrupted");
        }
        long nanos = policy.totalTimeout().toNanos() - (System.nanoTime() - started);
        if (nanos < 1_000_000) {
            throw new SocketTimeoutException("SMTP conversation deadline reached");
        }
        return Duration.ofMillis(Math.min(stageLimit.toMillis(), nanos / 1_000_000));
    }

    private SmtpObservation commandFailure(SmtpResponse response, String host, int port, SmtpReasonCode reason) {
        if (response != null && !SmtpResponseInterpreter.consistent(response)) {
            return observation(SmtpObservation.Outcome.UNCERTAIN, SmtpReasonCode.PROTOCOL_ERROR,
                    host, port, response, false, "Conflicting SMTP and enhanced status codes");
        }
        int code = response == null ? 0 : response.code();
        SmtpObservation.Outcome outcome = code >= 400 && code < 500
                ? SmtpObservation.Outcome.TEMPORARY_FAILURE
                : code >= 500 && code < 600 ? SmtpObservation.Outcome.POLICY_REJECTION
                : SmtpObservation.Outcome.UNCERTAIN;
        return observation(outcome, outcome == SmtpObservation.Outcome.UNCERTAIN
                        ? SmtpReasonCode.PROTOCOL_ERROR : reason,
                host, port, response, outcome == SmtpObservation.Outcome.TEMPORARY_FAILURE,
                "SMTP command did not complete");
    }

    private SmtpObservation interpreted(SmtpResponseInterpreter.Interpretation interpretation,
                                        String host, int port, SmtpResponse response) {
        return observation(interpretation.outcome(), interpretation.reasonCode(), host, port, response,
                interpretation.retryable(), "SMTP recipient-stage evidence; delivery unconfirmed");
    }

    private static SmtpObservation observation(SmtpObservation.Outcome outcome, SmtpReasonCode reason,
                                               String host, int port, SmtpResponse response,
                                               boolean retryable, String diagnostic) {
        return new SmtpObservation(outcome, reason, host, port,
                response == null ? null : response.code(), response == null ? null : response.enhancedStatusCode(),
                diagnostic, retryable, Instant.now());
    }

    private void quietQuit(SmtpTransport transport, long started) {
        if (!transport.isConnected() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            transport.sendCommand("QUIT", remaining(started, Duration.ofMillis(250)));
        } catch (IOException ignored) {
            // The recipient-stage observation is already complete.
        }
    }
}
