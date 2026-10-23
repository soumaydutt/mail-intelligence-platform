package com.aash.mailguard.jobs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

/** Loop-owned progress marker; no scheduled heartbeat can mask a blocked poller. */
public final class WorkerHealthProbe {
    private final byte[] freshnessSeconds;
    private final Path marker;
    private final Path staged;
    private boolean marked;
    private boolean closed;

    public WorkerHealthProbe(String name, long pollMillis) {
        marker = Path.of(System.getProperty("java.io.tmpdir"), "mailguard-" + name + ".ready");
        staged = marker.resolveSibling(marker.getFileName() + ".staged");
        freshnessSeconds = Long.toString(20 + (pollMillis + 999) / 1000).getBytes(StandardCharsets.US_ASCII);
        try {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize worker health marker", e);
        }
    }

    public synchronized void succeeded() {
        if (closed) {
            return;
        }
        try {
            if (marked) {
                Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis()));
            } else {
                Files.write(staged, freshnessSeconds, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(staged, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                marked = true;
            }
        } catch (IOException e) {
            failed();
        }
    }

    public synchronized void failed() {
        marked = false;
        try {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(staged);
        } catch (IOException ignored) {
            // An undeletable marker expires under the healthcheck's age limit.
        }
    }

    public synchronized void close() {
        closed = true;
        failed();
    }
}
