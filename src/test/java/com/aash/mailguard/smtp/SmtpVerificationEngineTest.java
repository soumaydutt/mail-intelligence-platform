package com.aash.mailguard.smtp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmtpVerificationEngineTest {
    private static final int PORT = 2525;

    @Test
    void disabledDefaultCannotCreateTransportEvenForLoopback() {
        SmtpTransport transport = mock(SmtpTransport.class);
        SmtpVerificationEngine engine = new SmtpVerificationEngine(SmtpVerificationPolicy.disabled(), () -> transport);
        assertEquals(SmtpObservation.Outcome.DISABLED,
                engine.verify("user@example.test", "127.0.0.1", PORT).outcome());
        verifyNoInteractions(transport);
    }

    @Test
    void onlyExactNumericLoopbackAndConfiguredPortMayConnect() throws Exception {
        SmtpTransport transport = mock(SmtpTransport.class);
        SmtpVerificationEngine engine = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport);
        for (String denied : List.of("localhost", "LOCALHOST", "127.0.0.2", "::ffff:127.0.0.1",
                "192.168.1.5", "10.0.0.1", "169.254.2.3", "224.0.0.1", "0.0.0.0",
                "255.255.255.255", "mail.example.test", "127.0.0.1\r\nQUIT")) {
            assertEquals(SmtpReasonCode.HOST_NOT_PERMITTED,
                    engine.verify("user@example.test", denied, PORT).reason(), denied);
        }
        assertEquals(SmtpReasonCode.HOST_NOT_PERMITTED,
                engine.verify("user@example.test", "127.0.0.1", 25).reason());
        verifyNoInteractions(transport);
        assertEquals("0:0:0:0:0:0:0:1", SmtpVerificationPolicy.localOnly(PORT)
                .destination("::1", PORT).getHostAddress());
    }

    @Test
    void policyRejectsInvalidTimeAndCapacity() {
        assertThrows(IllegalArgumentException.class, () -> SmtpVerificationPolicy.localOnly(0));
        assertThrows(IllegalArgumentException.class, () -> new SmtpVerificationPolicy(true, PORT,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2), 1));
        assertThrows(IllegalArgumentException.class, () -> new SmtpVerificationPolicy(true, PORT,
                Duration.ofMillis(1), Duration.ofNanos(1), Duration.ofSeconds(2), 1));
        assertThrows(IllegalArgumentException.class, () -> new SmtpVerificationPolicy(true, PORT,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 0));
    }

    @Test
    void invalidOrTransformedEnvelopeNeverReachesTransport() {
        SmtpTransport transport = mock(SmtpTransport.class);
        SmtpVerificationEngine engine = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport);
        for (String email : List.of("a\r\nQUIT@example.test", "invalid@example", "bad..name@example.test",
                "üser@example.test", "user@exämple.test", "user\u0000@example.test", "user@example.test\n")) {
            assertEquals(SmtpReasonCode.INVALID_RECIPIENT_SYNTAX,
                    engine.verify(email, "127.0.0.1", PORT).reason(), email);
        }
        assertEquals(SmtpReasonCode.INVALID_RECIPIENT_SYNTAX,
                engine.verify(null, "127.0.0.1", PORT).reason());
        verifyNoInteractions(transport);
    }

    @Test
    void rcptAcceptanceIsNotDeliveryAndNeverSendsCanary() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(250, "2.1.5", "Recipient accepted", List.of()));
        SmtpVerificationEngine engine = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport);
        SmtpObservation result = engine.verify("user@example.test", "127.0.0.1", PORT);
        assertEquals(SmtpObservation.Outcome.ACCEPTED, result.outcome());
        assertEquals("SMTP recipient-stage evidence; delivery unconfirmed", result.diagnostic());
        verify(transport).connect(eq(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})), eq(PORT), any());
        verify(transport).sendCommand(eq("RCPT TO:<user@example.test>"), any());
        verify(transport, times(1)).sendCommand(startsWith("RCPT TO:"), any());
        verify(transport, never()).sendCommand(startsWith("DATA"), any());
    }

    @Test
    void onlyUnsupportedEhloTriggersOneHeloFallback() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(251, null, "Forwarding", List.of()));
        when(transport.sendCommand(startsWith("EHLO"), any()))
                .thenReturn(new SmtpResponse(502, null, "Not implemented", List.of()));
        when(transport.sendCommand(startsWith("HELO"), any()))
                .thenReturn(new SmtpResponse(250, null, "OK", List.of()));
        SmtpObservation result = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport)
                .verify("user@example.test", "127.0.0.1", PORT);
        assertEquals(SmtpObservation.Outcome.ACCEPTED, result.outcome());
        verify(transport, times(1)).sendCommand(startsWith("HELO"), any());
    }

    @Test
    void ehlo421IsTemporaryWithoutHeloOrEnvelopeCommands() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.sendCommand(startsWith("EHLO"), any()))
                .thenReturn(new SmtpResponse(421, "4.3.2", "Unavailable", List.of()));
        SmtpObservation result = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport)
                .verify("user@example.test", "127.0.0.1", PORT);
        assertEquals(SmtpObservation.Outcome.TEMPORARY_FAILURE, result.outcome());
        assertTrue(result.retryable());
        verify(transport, never()).sendCommand(startsWith("HELO"), any());
        verify(transport, never()).sendCommand(startsWith("MAIL FROM"), any());
    }

    @Test
    void contradictoryEhloCannotTriggerHeloFallback() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.sendCommand(startsWith("EHLO"), any()))
                .thenReturn(new SmtpResponse(502, "4.7.0", "Conflicting status", List.of()));
        SmtpObservation result = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport)
                .verify("user@example.test", "127.0.0.1", PORT);
        assertEquals(SmtpObservation.Outcome.UNCERTAIN, result.outcome());
        assertEquals(SmtpReasonCode.PROTOCOL_ERROR, result.reason());
        verify(transport, never()).sendCommand(startsWith("HELO"), any());
        verify(transport, never()).sendCommand(startsWith("MAIL FROM"), any());
    }

    @Test
    void unexpectedSequenceDoesNotIssueRecipientCommand() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.sendCommand(startsWith("MAIL FROM"), any()))
                .thenReturn(new SmtpResponse(354, null, "Start data", List.of()));
        SmtpObservation result = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport)
                .verify("user@example.test", "127.0.0.1", PORT);
        assertEquals(SmtpObservation.Outcome.UNCERTAIN, result.outcome());
        verify(transport, never()).sendCommand(startsWith("RCPT TO"), any());
    }

    @Test
    void connectTimeoutAndPriorInterruptionNeverHideUncertainty() throws Exception {
        SmtpTransport transport = mock(SmtpTransport.class);
        doThrow(new SocketTimeoutException()).when(transport).connect(any(InetAddress.class), anyInt(), any());
        SmtpVerificationEngine engine = new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport);
        assertEquals(SmtpReasonCode.CONNECTION_TIMED_OUT,
                engine.verify("user@example.test", "127.0.0.1", PORT).reason());
        clearInvocations(transport);
        Thread.currentThread().interrupt();
        try {
            assertEquals(SmtpObservation.Outcome.UNCERTAIN,
                    engine.verify("user@example.test", "127.0.0.1", PORT).outcome());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(transport);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void totalDeadlineStopsConversationBetweenCommands() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.readGreeting(any())).thenAnswer(invocation -> {
            Thread.sleep(40);
            return new SmtpResponse(220, null, "Ready", List.of());
        });
        SmtpVerificationPolicy policy = new SmtpVerificationPolicy(true, PORT,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(10), 1);
        SmtpObservation result = new SmtpVerificationEngine(policy, () -> transport)
                .verify("user@example.test", "127.0.0.1", PORT);
        assertEquals(SmtpReasonCode.CONNECTION_TIMED_OUT, result.reason());
        verify(transport, never()).sendCommand(startsWith("EHLO"), any());
    }

    @Test
    void interruptionAfterGreetingPreservesInterruptAndClosesTransport() throws Exception {
        SmtpTransport transport = readyTransport(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.readGreeting(any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new SmtpResponse(220, null, "Ready", List.of());
        });
        AtomicReference<SmtpObservation> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            result.set(new SmtpVerificationEngine(SmtpVerificationPolicy.localOnly(PORT), () -> transport)
                    .verify("user@example.test", "127.0.0.1", PORT));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.join(3000);
        assertFalse(thread.isAlive());
        assertEquals(SmtpObservation.Outcome.UNCERTAIN, result.get().outcome());
        assertEquals(Boolean.TRUE, interrupted.get());
        verify(transport).close();
        verify(transport, never()).sendCommand(startsWith("EHLO"), any());
    }

    @Test
    void saturationRejectsSecondCallAndFailureReleasesPermit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SmtpTransport transport = mock(SmtpTransport.class);
        when(transport.readGreeting(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Fixture wait expired");
            }
            throw new IOException("Fixture closed");
        });
        SmtpVerificationPolicy policy = new SmtpVerificationPolicy(true, PORT,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(3), 1);
        SmtpVerificationEngine engine = new SmtpVerificationEngine(policy, () -> transport);
        AtomicReference<SmtpObservation> first = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> first.set(engine.verify("user@example.test", "127.0.0.1", PORT)));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            SmtpObservation busy = engine.verify("user@example.test", "127.0.0.1", PORT);
            assertEquals(SmtpReasonCode.CAPACITY_EXCEEDED, busy.reason());
            assertTrue(busy.retryable());
        } finally {
            release.countDown();
            thread.join(3000);
        }
        assertFalse(thread.isAlive());
        assertEquals(SmtpReasonCode.PROTOCOL_ERROR, first.get().reason());
        assertEquals(SmtpReasonCode.PROTOCOL_ERROR,
                engine.verify("user@example.test", "127.0.0.1", PORT).reason());
    }

    private static SmtpTransport readyTransport(SmtpResponse rcpt) throws IOException {
        SmtpTransport transport = mock(SmtpTransport.class);
        when(transport.readGreeting(any())).thenReturn(new SmtpResponse(220, null, "Ready", List.of()));
        when(transport.sendCommand(startsWith("EHLO"), any())).thenReturn(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.sendCommand(startsWith("MAIL FROM"), any())).thenReturn(new SmtpResponse(250, null, "OK", List.of()));
        when(transport.sendCommand(startsWith("RCPT TO"), any())).thenReturn(rcpt);
        return transport;
    }
}
