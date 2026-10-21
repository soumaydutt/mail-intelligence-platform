package com.aash.mailguard;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValkeyConnectionLimitsTest {
    private static final Duration COMMAND = Duration.ofMillis(200);

    @Test
    void rejectsUnboundedOrInvalidConnectionSettingsBeforeStartup() {
        assertDoesNotThrow(() -> new ValkeyConnectionLimits("127.0.0.1", 6379,
                COMMAND, COMMAND, COMMAND, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ValkeyConnectionLimits("localhost", 0,
                COMMAND, COMMAND, COMMAND, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ValkeyConnectionLimits("localhost", 6379,
                Duration.ZERO, COMMAND, COMMAND, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ValkeyConnectionLimits("localhost", 6379,
                COMMAND, Duration.ofSeconds(3), COMMAND, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ValkeyConnectionLimits("localhost", 6379,
                COMMAND, COMMAND, Duration.ofMillis(-1), 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ValkeyConnectionLimits("localhost", 6379,
                COMMAND, COMMAND, COMMAND, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ValkeyConnectionLimits("localhost", 6379,
                COMMAND, COMMAND, COMMAND, 8, 9));
    }
}
