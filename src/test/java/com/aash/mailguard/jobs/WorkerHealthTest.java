package com.aash.mailguard.jobs;

import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.imports.BulkImportStore;
import com.aash.mailguard.imports.BulkPromoter;
import com.aash.mailguard.service.EmailValidationHistoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerHealthTest {
    private static final Path JOB = Path.of(System.getProperty("java.io.tmpdir"), "mailguard-job-worker.ready");
    private static final Path IMPORT = Path.of(System.getProperty("java.io.tmpdir"), "mailguard-import-promoter.ready");

    @Test
    void startupIdleActiveStallFailureRecoveryAndShutdown() throws Exception {
        JobStore store = mock(JobStore.class);
        EmailValidationHistoryService validation = mock(EmailValidationHistoryService.class);
        BulkImportStore imports = mock(BulkImportStore.class);
        JobStore.Claim claim = new JobStore.Claim(UUID.randomUUID(), "invalid-address", "test", 1, 1, 3);
        AtomicBoolean issueWork = new AtomicBoolean();
        AtomicBoolean claimFailed = new AtomicBoolean();
        AtomicBoolean blockClaim = new AtomicBoolean();
        CountDownLatch claimBlocked = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch inValidation = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenAnswer(call -> {
            if (blockClaim.compareAndSet(true, false)) {
                claimBlocked.countDown();
                releaseClaim.await(5, TimeUnit.SECONDS);
            }
            if (claimFailed.get()) {
                throw new IllegalStateException("simulated dependency failure");
            }
            return issueWork.compareAndSet(true, false) ? List.of(claim) : List.of();
        });
        when(store.heartbeat(any(JobStore.Claim.class), any(Duration.class))).thenReturn(true);
        when(validation.compute(any())).thenAnswer(call -> {
            inValidation.countDown();
            releaseValidation.await(5, TimeUnit.SECONDS);
            return new EmailValidationHistoryService.Computation(new EmailValidationResponse(), false);
        });
        JobWorker worker = new JobWorker(store, validation, 1, 30, 50, 1);
        BulkPromoter promoter = new BulkPromoter(imports, 1, 50);
        try {
            assertFalse(healthy(JOB));
            assertFalse(healthy(IMPORT));
            worker.start();
            await(() -> healthy(JOB));
            assertFalse(healthy(IMPORT));
            promoter.start();
            await(() -> healthy(IMPORT));
            assertTrue(healthy(JOB)); // Empty successful polls are real progress.
            assertEquals("21", Files.readString(JOB));
            assertEquals("21", Files.readString(IMPORT));
            blockClaim.set(true);
            assertTrue(claimBlocked.await(3, TimeUnit.SECONDS));
            Files.setLastModifiedTime(JOB, FileTime.from(Instant.now().minusSeconds(30)));
            assertFalse(healthy(JOB)); // A blocked claim cannot advance dispatcher progress.
            releaseClaim.countDown();
            await(() -> healthy(JOB));

            issueWork.set(true);
            assertTrue(inValidation.await(3, TimeUnit.SECONDS));
            Files.setLastModifiedTime(JOB, FileTime.from(Instant.now().minusSeconds(30)));
            await(() -> healthy(JOB)); // Dispatcher keeps polling at capacity during long validation.
            assertTrue(healthy(IMPORT));

            releaseValidation.countDown();
            claimFailed.set(true);
            await(() -> !Files.exists(JOB));
            assertFalse(healthy(JOB));
            assertTrue(healthy(IMPORT));
            claimFailed.set(false);
            await(() -> healthy(JOB));
        } finally {
            releaseClaim.countDown();
            releaseValidation.countDown();
            worker.stop();
            promoter.stop();
        }
        assertFalse(healthy(JOB));
        assertFalse(healthy(IMPORT));
    }

    @Test
    void blockedPromoterExpiresAndFailedPollOnlyRecoversAfterSuccess() throws Exception {
        BulkImportStore imports = mock(BulkImportStore.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(imports.promote(1)).thenAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                blocked.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            if (fail.get()) {
                throw new IllegalStateException("simulated dependency failure");
            }
            return 0;
        });
        BulkPromoter promoter = new BulkPromoter(imports, 1, 50);
        try {
            assertFalse(healthy(IMPORT));
            promoter.start();
            assertTrue(blocked.await(3, TimeUnit.SECONDS));
            Files.setLastModifiedTime(IMPORT, FileTime.from(Instant.now().minusSeconds(30)));
            assertFalse(healthy(IMPORT)); // No scheduler refreshes a blocked poll.
            fail.set(true);
            release.countDown();
            await(() -> !Files.exists(IMPORT));
            fail.set(false);
            await(() -> healthy(IMPORT));
        } finally {
            release.countDown();
            promoter.stop();
        }
        assertFalse(healthy(IMPORT));
    }

    @Test
    void configuredSixtySecondPollsRemainHealthyUntilTheirBoundedGraceExpires() throws Exception {
        JobStore store = mock(JobStore.class);
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenReturn(List.of());
        JobWorker worker = new JobWorker(store, mock(EmailValidationHistoryService.class), 1, 30, 60_000, 1);
        BulkPromoter promoter = new BulkPromoter(mock(BulkImportStore.class), 1, 60_000);
        try {
            worker.start();
            promoter.start();
            await(() -> healthy(JOB) && healthy(IMPORT));
            assertEquals("80", Files.readString(JOB));
            assertEquals("80", Files.readString(IMPORT));
            Files.setLastModifiedTime(JOB, FileTime.from(Instant.now().minusSeconds(65)));
            Files.setLastModifiedTime(IMPORT, FileTime.from(Instant.now().minusSeconds(65)));
            assertTrue(healthy(JOB));
            assertTrue(healthy(IMPORT));
            Files.setLastModifiedTime(JOB, FileTime.from(Instant.now().minusSeconds(81)));
            Files.setLastModifiedTime(IMPORT, FileTime.from(Instant.now().minusSeconds(81)));
            assertFalse(healthy(JOB));
            assertFalse(healthy(IMPORT));
        } finally {
            worker.stop();
            promoter.stop();
        }
    }

    private static boolean healthy(Path marker) throws Exception {
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        Instant now = Instant.now();
        Instant last = Files.getLastModifiedTime(marker).toInstant();
        return !last.isAfter(now) && Duration.between(last, now).getSeconds() <= Long.parseLong(Files.readString(marker));
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!check.done() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(check.done(), "Timed out waiting for worker health transition");
    }

    @FunctionalInterface
    private interface Check {
        boolean done() throws Exception;
    }
}
