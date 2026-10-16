package com.aash.mailguard.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SocketSmtpTransportTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final InetAddress LOOPBACK;

    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void readsGreetingAndMultilineEhloOverPinnedAddress() throws Exception {
        withServer(socket -> {
            socket.getOutputStream().write("220 mail ready\r\n".getBytes(StandardCharsets.US_ASCII));
            BufferedReader commands = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals("EHLO localhost", commands.readLine());
            socket.getOutputStream().write("250-mail hello\r\n250-SIZE 1234\r\n250 2.0.0 ready\r\n".getBytes(StandardCharsets.US_ASCII));
        }, transport -> {
            assertEquals(220, transport.readGreeting(TIMEOUT).code());
            SmtpResponse response = transport.sendCommand("EHLO localhost", TIMEOUT);
            assertEquals(250, response.code());
            assertEquals(3, response.lines().size());
            assertEquals("2.0.0", response.enhancedStatusCode());
            assertEquals("2.0.0 ready", response.message());
        });
    }

    @Test
    void rejectsOversizedLineBeforeDecoding() throws Exception {
        rejectsReply("220 " + "x".repeat(1024) + "\r\n", "SMTP reply line exceeds byte limit");
    }

    @Test
    void rejectsExcessiveReplyBytes() throws Exception {
        rejectsReply(("220-" + "x".repeat(200) + "\r\n").repeat(85), "SMTP reply exceeds byte limit");
    }

    @Test
    void rejectsExcessiveLineCountWithoutFinalLine() throws Exception {
        rejectsReply("220-more\r\n".repeat(100), "SMTP reply exceeds line limit");
    }

    @Test
    void rejectsMalformedAndMismatchedStatuses() throws Exception {
        for (String reply : new String[] {
                "abc ready\r\n", "220\r\n", "220!ready\r\n", "220 ready\n",
                "220 re\rady\r\n", "220-first\r\n221 final\r\n", "220-first\r\ninvalid\r\n"
        }) {
            rejectsReply(reply);
        }
    }

    @Test
    void rejectsMultilineEofAndPartialLine() throws Exception {
        rejectsReply("220-first\r\n");
        rejectsReply("220 ready\r");
        rejectsReply("");
    }

    @Test
    void rejectsInjectionNonAsciiAndInvalidTimeoutWithoutSending() throws Exception {
        withServer(socket -> {
            socket.getOutputStream().write("220 ready\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(1000);
            assertEquals("EHLO localhost", new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine());
            socket.getOutputStream().write("250 done\r\n".getBytes(StandardCharsets.US_ASCII));
        }, transport -> {
            assertEquals(220, transport.readGreeting(TIMEOUT).code());
            for (String command : new String[] {"", "EHLO localhost\r\nQUIT", "EHLO\nQUIT", "EHLO café",
                    "EHLO \u007f", "X".repeat(511)}) {
                assertThrows(IllegalArgumentException.class, () -> transport.sendCommand(command, TIMEOUT));
            }
            assertThrows(IllegalArgumentException.class, () -> transport.sendCommand("EHLO localhost", Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> transport.readGreeting(Duration.ZERO));
            assertEquals(250, transport.sendCommand("EHLO localhost", TIMEOUT).code());
        });
    }

    @Test
    void timesOutWhenServerLeavesMultilineReplyOpen() throws Exception {
        withServer(socket -> {
            socket.getOutputStream().write("220-still talking\r\n".getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(500);
        }, transport -> {
            assertThrows(SocketTimeoutException.class, () -> transport.readGreeting(Duration.ofMillis(75)));
            assertFalse(transport.isConnected());
        });
    }

    @Test
    void commandReplyTimeoutClosesSocket() throws Exception {
        withServer(socket -> {
            socket.getOutputStream().write("220 ready\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(1000);
            assertEquals("EHLO localhost", new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine());
            Thread.sleep(500);
        }, transport -> {
            assertEquals(220, transport.readGreeting(TIMEOUT).code());
            assertThrows(SocketTimeoutException.class,
                    () -> transport.sendCommand("EHLO localhost", Duration.ofMillis(75)));
            assertFalse(transport.isConnected());
        });
    }

    @Test
    void failsClosedOnConnectionReset() throws Exception {
        withServer(socket -> {
            socket.setSoLinger(true, 0);
        }, transport -> {
            assertThrows(IOException.class, () -> transport.readGreeting(TIMEOUT));
            assertFalse(transport.isConnected());
        });
    }

    @Test
    void rejectsInvalidConnectDurationWithoutOpeningSocket() throws Exception {
        try (SocketSmtpTransport transport = new SocketSmtpTransport()) {
            assertThrows(IllegalArgumentException.class, () -> transport.connect(LOOPBACK, 25, Duration.ZERO));
            assertFalse(transport.isConnected());
        }
    }

    private void rejectsReply(String reply) throws Exception {
        rejectsReply(reply, null);
    }

    private void rejectsReply(String reply, String expectedError) throws Exception {
        withServer(socket -> socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII)), transport -> {
            IOException error = assertThrows(IOException.class, () -> transport.readGreeting(TIMEOUT));
            if (expectedError != null) {
                assertEquals(expectedError, error.getMessage());
            }
            assertFalse(transport.isConnected());
        });
    }

    private void withServer(ServerAction serverAction, ClientAction clientAction) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(LOOPBACK, 0));
            Future<?> exchange = executor.submit(() -> {
                try (Socket peer = server.accept()) {
                    serverAction.run(peer);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try (SocketSmtpTransport transport = new SocketSmtpTransport()) {
                transport.connect(LOOPBACK, server.getLocalPort(), TIMEOUT);
                clientAction.run(transport);
            }
            exchange.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(Socket socket) throws Exception;
    }

    @FunctionalInterface
    private interface ClientAction {
        void run(SocketSmtpTransport transport) throws Exception;
    }
}
