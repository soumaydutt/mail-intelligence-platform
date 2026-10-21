package com.aash.mailguard.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class RateLimitSettings {
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private final boolean enabled;
    private final byte[] secret;
    private final String prefix;
    private final int windowSeconds;
    private final int publicFallbackCapacity;
    private final int[] capacities;

    public RateLimitSettings(
            @Value("${rate-limit.enabled:false}") boolean enabled,
            @Value("${rate-limit.identity-secret:}") String identitySecret,
            @Value("${valkey.namespace:local}") String namespace,
            @Value("${rate-limit.window-seconds:60}") int windowSeconds,
            @Value("${rate-limit.public-capacity:60}") int publicCapacity,
            @Value("${rate-limit.public-global-capacity:600}") int publicGlobalCapacity,
            @Value("${rate-limit.public-fallback-capacity:30}") int publicFallbackCapacity,
            @Value("${rate-limit.test-capacity:120}") int testCapacity,
            @Value("${rate-limit.job-write-capacity:30}") int jobWriteCapacity,
            @Value("${rate-limit.job-read-capacity:120}") int jobReadCapacity,
            @Value("${rate-limit.import-write-capacity:6}") int importWriteCapacity,
            @Value("${rate-limit.import-read-capacity:120}") int importReadCapacity,
            @Value("${rate-limit.export-capacity:12}") int exportCapacity,
            @Value("${rate-limit.event-write-capacity:60}") int eventWriteCapacity,
            @Value("${rate-limit.evidence-read-capacity:120}") int evidenceReadCapacity,
            @Value("${rate-limit.suppression-write-capacity:30}") int suppressionWriteCapacity,
            @Value("${rate-limit.suppression-read-capacity:60}") int suppressionReadCapacity) {
        this.enabled = enabled;
        this.secret = identitySecret.getBytes(StandardCharsets.UTF_8);
        this.prefix = namespace + ":http-limit:v1:";
        this.windowSeconds = windowSeconds;
        this.publicFallbackCapacity = publicFallbackCapacity;
        this.capacities = new int[] { publicCapacity, publicGlobalCapacity, testCapacity, jobWriteCapacity,
                jobReadCapacity, importWriteCapacity, importReadCapacity, exportCapacity, eventWriteCapacity,
                evidenceReadCapacity, suppressionWriteCapacity, suppressionReadCapacity };
        if (enabled) {
            if (!NAMESPACE.matcher(namespace).matches() || secret.length < 32 || secret.length > 256
                    || windowSeconds < 1 || windowSeconds > 3600) {
                throw new IllegalArgumentException("Invalid enabled HTTP rate limit namespace, secret or window");
            }
            check(publicFallbackCapacity);
            for (int capacity : capacities) {
                check(capacity);
            }
        }
    }

    private static void check(int capacity) {
        if (capacity < 1 || capacity > 100_000) {
            throw new IllegalArgumentException("HTTP rate limit capacity must be between 1 and 100000");
        }
    }

    public boolean enabled() { return enabled; }
    public byte[] secret() { return secret.clone(); }
    public String prefix() { return prefix; }
    public int windowSeconds() { return windowSeconds; }
    public int fallbackCapacity() { return publicFallbackCapacity; }
    public int capacity(Policy policy) { return capacities[policy.ordinal()]; }

    public enum Policy {
        PUBLIC, PUBLIC_GLOBAL, TEST, JOB_WRITE, JOB_READ, IMPORT_WRITE, IMPORT_READ,
        EXPORT, EVENT_WRITE, EVIDENCE_READ, SUPPRESSION_WRITE, SUPPRESSION_READ
    }
}
