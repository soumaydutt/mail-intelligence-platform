package com.aash.mailguard.smtp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class SocketSmtpTransport implements SmtpTransport {
    private static final int MAX_REPLY_LINES = 100;
    private static final int MAX_LINE_BYTES = 1024;
    private static final int MAX_REPLY_BYTES = 16 * 1024;
    private static final int MAX_COMMAND_BYTES = 512;

    private Socket socket;
    private InputStream reader;
    private OutputStream writer;

    @Override
    public void connect(InetAddress address, int port, Duration timeout) throws IOException {
        close();
        int timeoutMs = timeoutMillis(timeout);
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            reader = new BufferedInputStream(socket.getInputStream());
            writer = socket.getOutputStream();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public SmtpResponse readGreeting(Duration timeout) throws IOException {
        int timeoutMs = timeoutMillis(timeout);
        checkConnected();
        try {
            return readReply(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public SmtpResponse sendCommand(String command, Duration timeout) throws IOException {
        validateCommand(command);
        int timeoutMs = timeoutMillis(timeout);
        checkConnected();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            writer.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
            return readReply(deadline);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) { }
        }
        socket = null;
        reader = null;
        writer = null;
    }

    private void checkConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("SMTP socket is not connected");
        }
    }

    private static int timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("SMTP timeout must be positive");
        }
        if (timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) >= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(1, timeout.toMillis());
    }

    private void validateCommand(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("SMTP command must not be empty");
        }
        if (command.length() > MAX_COMMAND_BYTES - 2) {
            throw new IllegalArgumentException("SMTP command exceeds 512 bytes");
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("CRLF injection detected in SMTP command");
            }
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("Unsupported character in SMTP command: " + (int) c);
            }
        }
    }

    private SmtpResponse readReply(long deadline) throws IOException {
        int totalBytes = 0;
        int expectedCode = -1;
        List<String> lines = new ArrayList<>();
        byte[] lineBytes = new byte[MAX_LINE_BYTES];
        while (lines.size() < MAX_REPLY_LINES) {
            int length = 0;
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SocketTimeoutException("SMTP reply timed out");
                }
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining) + 1)));
                int value = reader.read();
                if (value == -1) {
                    throw new IOException("Premature EOF while reading SMTP response");
                }
                if (++totalBytes > MAX_REPLY_BYTES) {
                    throw new IOException("SMTP reply exceeds byte limit");
                }
                if (length >= MAX_LINE_BYTES) {
                    throw new IOException("SMTP reply line exceeds byte limit");
                }
                if (value > 126 || (value < 32 && value != '\r' && value != '\n' && value != '\t')) {
                    throw new IOException("Invalid ASCII in SMTP reply");
                }
                if (length > 0 && lineBytes[length - 1] == '\r' && value != '\n') {
                    throw new IOException("Invalid SMTP line ending");
                }
                lineBytes[length++] = (byte) value;
                if (value == '\n') {
                    if (length < 2 || lineBytes[length - 2] != '\r') {
                        throw new IOException("Invalid SMTP line ending");
                    }
                    break;
                }
            }
            String line = new String(lineBytes, 0, length - 2, StandardCharsets.US_ASCII);
            if (line.length() < 4 || line.charAt(0) < '2' || line.charAt(0) > '5'
                    || line.charAt(1) < '0' || line.charAt(1) > '9'
                    || line.charAt(2) < '0' || line.charAt(2) > '9'
                    || (line.charAt(3) != '-' && line.charAt(3) != ' ')) {
                throw new IOException("Malformed SMTP reply status");
            }
            int code = (line.charAt(0) - '0') * 100 + (line.charAt(1) - '0') * 10 + line.charAt(2) - '0';
            if (expectedCode != -1 && code != expectedCode) {
                throw new IOException("SMTP multiline status mismatch");
            }
            expectedCode = code;
            lines.add(line);
            // RFC 5321 §4.2: format is 3 digits, then space (last line) or hyphen (continuation)
            if (line.charAt(3) == ' ') {
                return SmtpResponse.parse(lines);
            }
        }
        throw new IOException("SMTP reply exceeds line limit");
    }
}
