package com.aash.mailguard.smtp;

@FunctionalInterface
public interface SmtpTransportFactory {
    SmtpTransport create();
}
