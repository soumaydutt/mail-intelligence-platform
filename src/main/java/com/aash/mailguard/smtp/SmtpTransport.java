package com.aash.mailguard.smtp;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;

public interface SmtpTransport extends AutoCloseable {
    void connect(InetAddress address, int port, Duration timeout) throws IOException;
    SmtpResponse readGreeting(Duration timeout) throws IOException;
    SmtpResponse sendCommand(String command, Duration timeout) throws IOException;
    boolean isConnected();
    @Override
    void close();
}
