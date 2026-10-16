package com.aash.mailguard.smtp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SmtpLocalProtocolTest {
    @ParameterizedTest
    @CsvSource({
            "250 2.1.5 Accepted, ACCEPTED, RECIPIENT_ACCEPTED, false",
            "251 2.1.5 Forwarded, ACCEPTED, RECIPIENT_ACCEPTED_FORWARD, false",
            "252 2.1.5 Cannot verify, AMBIGUOUS, RECIPIENT_ACCEPTED_UNVERIFIED, false",
            "421 4.3.2 Unavailable, TEMPORARY_FAILURE, SERVICE_UNAVAILABLE_TEMPORARY, true",
            "450 4.2.1 Busy, TEMPORARY_FAILURE, MAILBOX_UNAVAILABLE_TEMPORARY, true",
            "451 4.3.0 Processing, TEMPORARY_FAILURE, MAILBOX_UNAVAILABLE_TEMPORARY, true",
            "452 4.2.2 Storage, TEMPORARY_FAILURE, INSUFFICIENT_STORAGE_TEMPORARY, true",
            "550 5.1.1 Unknown, REJECTED, RECIPIENT_NOT_FOUND, false",
            "550 5.7.1 Policy, POLICY_REJECTION, POLICY_REJECTION, false",
            "550 Rejected, AMBIGUOUS, RECIPIENT_REJECTED_UNSPECIFIED, false",
            "552 5.2.2 Storage, UNCERTAIN, INSUFFICIENT_STORAGE_PERMANENT, false",
            "554 5.7.1 Policy, POLICY_REJECTION, POLICY_REJECTION, false"
    })
    void rcptReplyOnlyYieldsRecipientStageEvidence(String reply, SmtpObservation.Outcome expectedOutcome,
                                                    SmtpReasonCode expectedReason, boolean retryable) throws Exception {
        List<String> commands = new ArrayList<>();
        try (LocalServer server = new LocalServer(session -> {
            session.reply("220 fixture ready");
            commands.add(session.command());
            session.reply("250-fixture hello", "250 HELP");
            commands.add(session.command());
            session.reply("250 OK");
            commands.add(session.command());
            session.reply(reply);
            commands.add(session.command());
            session.reply("221 bye");
        })) {
            SmtpObservation observation = new SmtpVerificationEngine(
                    SmtpVerificationPolicy.localOnly(server.port()), SocketSmtpTransport::new)
                    .verify("user@example.test", "127.0.0.1", server.port());
            assertEquals(expectedOutcome, observation.outcome());
            assertEquals(expectedReason, observation.reason());
            assertEquals(retryable, observation.retryable());
            assertEquals("SMTP recipient-stage evidence; delivery unconfirmed", observation.diagnostic());
            server.finish();
        }
        assertEquals(List.of("EHLO localhost", "MAIL FROM:<verify@mailguard.local>",
                "RCPT TO:<user@example.test>", "QUIT"), commands);
    }

    @Test
    void heloFallbackUsesSingleAttemptAndNeverSendsData() throws Exception {
        List<String> commands = new ArrayList<>();
        try (LocalServer server = new LocalServer(session -> {
            session.reply("220 fixture ready");
            commands.add(session.command());
            session.reply("502 Unsupported EHLO");
            commands.add(session.command());
            session.reply("250 HELO OK");
            commands.add(session.command());
            session.reply("250 Sender OK");
            commands.add(session.command());
            session.reply("250 Recipient OK");
            commands.add(session.command());
            session.reply("221 bye");
        })) {
            SmtpObservation observation = new SmtpVerificationEngine(
                    SmtpVerificationPolicy.localOnly(server.port()), SocketSmtpTransport::new)
                    .verify("user@example.test", "127.0.0.1", server.port());
            assertEquals(SmtpObservation.Outcome.ACCEPTED, observation.outcome());
            server.finish();
        }
        assertEquals(List.of("EHLO localhost", "HELO localhost", "MAIL FROM:<verify@mailguard.local>",
                "RCPT TO:<user@example.test>", "QUIT"), commands);
    }

    @Test
    void serviceUnavailableAfterEhloNeverSendsMailOrHelo() throws Exception {
        List<String> commands = new ArrayList<>();
        try (LocalServer server = new LocalServer(session -> {
            session.reply("220 fixture ready");
            commands.add(session.command());
            session.reply("421 4.3.2 Unavailable");
        })) {
            SmtpObservation observation = new SmtpVerificationEngine(
                    SmtpVerificationPolicy.localOnly(server.port()), SocketSmtpTransport::new)
                    .verify("user@example.test", "127.0.0.1", server.port());
            assertEquals(SmtpObservation.Outcome.TEMPORARY_FAILURE, observation.outcome());
            assertTrue(observation.retryable());
            server.finish();
        }
        assertEquals(List.of("EHLO localhost"), commands);
    }

    @FunctionalInterface
    private interface Script {
        void run(Session session) throws Exception;
    }

    private record Session(BufferedReader reader, BufferedWriter writer) {
        String command() throws IOException {
            String command = reader.readLine();
            if (command == null) {
                throw new IOException("Fixture received EOF before command");
            }
            return command;
        }

        void reply(String... lines) throws IOException {
            for (String line : lines) {
                writer.write(line);
                writer.write("\r\n");
            }
            writer.flush();
        }
    }

    private static final class LocalServer implements AutoCloseable {
        private final ServerSocket listener;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        LocalServer(Script script) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            thread = Thread.ofVirtual().start(() -> {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(2000);
                    script.run(new Session(new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)),
                            new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII))));
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        void finish() throws InterruptedException {
            thread.join(3000);
            assertFalse(thread.isAlive(), "Fixture server did not finish");
            assertNull(failure.get(), "Fixture failed");
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
