package com.aash.mailguard;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ValkeyConnectionLimits {
    ValkeyConnectionLimits(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.connect-timeout:200ms}") Duration connectTimeout,
            @Value("${spring.data.redis.timeout:200ms}") Duration commandTimeout,
            @Value("${spring.data.redis.lettuce.pool.max-wait:200ms}") Duration maxWait,
            @Value("${spring.data.redis.lettuce.pool.max-active:8}") int maxActive,
            @Value("${spring.data.redis.lettuce.pool.max-idle:8}") int maxIdle) {
        if (host == null || host.isBlank() || host.length() > 253 || port < 1 || port > 65535
                || !bounded(connectTimeout, 1, 2000) || !bounded(commandTimeout, 1, 2000)
                || !bounded(maxWait, 0, 2000) || maxActive < 1 || maxActive > 64
                || maxIdle < 0 || maxIdle > maxActive) {
            throw new IllegalArgumentException("Invalid Valkey endpoint, timeout or connection pool bounds");
        }
    }

    private static boolean bounded(Duration duration, long minimumMillis, long maximumMillis) {
        return duration != null && !duration.isNegative()
                && duration.compareTo(Duration.ofMillis(minimumMillis)) >= 0
                && duration.compareTo(Duration.ofMillis(maximumMillis)) <= 0;
    }
}
