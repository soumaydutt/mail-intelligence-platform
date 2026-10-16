package com.aash.mailguard.smtp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

public record SmtpVerificationPolicy(
        boolean enabled,
        int allowedPort,
        Duration connectTimeout,
        Duration readTimeout,
        Duration totalTimeout,
        int maxConcurrent) {

    public SmtpVerificationPolicy {
        Objects.requireNonNull(connectTimeout);
        Objects.requireNonNull(readTimeout);
        Objects.requireNonNull(totalTimeout);
        if ((enabled && (allowedPort < 1 || allowedPort > 65535))
                || (!enabled && allowedPort != 0)
                || maxConcurrent < 1 || maxConcurrent > 32) {
            throw new IllegalArgumentException("Invalid SMTP fixture policy");
        }
        checkTimeout(connectTimeout);
        checkTimeout(readTimeout);
        checkTimeout(totalTimeout);
    }

    private static void checkTimeout(Duration timeout) {
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("SMTP timeouts must be between 1 ms and 60 s");
        }
    }

    public static SmtpVerificationPolicy disabled() {
        return new SmtpVerificationPolicy(false, 0, Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(10), 4);
    }

    public static SmtpVerificationPolicy localOnly(int port) {
        return new SmtpVerificationPolicy(true, port, Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(10), 4);
    }

    InetAddress destination(String host, int port) {
        if (!enabled || port != allowedPort || host == null) {
            return null;
        }
        byte[] bytes;
        if ("127.0.0.1".equals(host)) {
            bytes = new byte[]{127, 0, 0, 1};
        } else if ("::1".equals(host)) {
            bytes = new byte[16];
            bytes[15] = 1;
        } else {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
