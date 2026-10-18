package com.aash.mailguard.jobs;

import com.aash.mailguard.dto.EmailValidationRequest;
import com.aash.mailguard.service.EmailValidationHistoryService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

@Component
@ConditionalOnProperty(prefix = "jobs.worker", name = "enabled", havingValue = "true")
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final JobStore store;
    private final EmailValidationHistoryService validation;
    private final int concurrency;
    private final Duration lease;
    private final long pollMillis;
    private final long shutdownSeconds;
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("mailguard-job-heartbeat").daemon(true).unstarted(runnable));
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    private final String owner = UUID.randomUUID().toString();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private volatile boolean running;
    private Thread dispatcher;

    public JobWorker(JobStore store, EmailValidationHistoryService validation,
            @Value("${jobs.worker.concurrency:20}") int concurrency,
            @Value("${jobs.worker.lease-seconds:30}") int leaseSeconds,
            @Value("${jobs.worker.poll-millis:250}") long pollMillis,
            @Value("${jobs.worker.shutdown-seconds:10}") long shutdownSeconds) {
        if (concurrency < 1 || concurrency > 200 || leaseSeconds < 6 || leaseSeconds > 300
                || pollMillis < 50 || pollMillis > 60000 || shutdownSeconds < 1 || shutdownSeconds > 300) {
            throw new IllegalArgumentException("Invalid job worker configuration");
        }
        this.store = store;
        this.validation = validation;
        this.concurrency = concurrency;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.pollMillis = pollMillis;
        this.shutdownSeconds = shutdownSeconds;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        long period = Math.max(1, lease.toSeconds() / 3);
        heartbeat.scheduleWithFixedDelay(this::renew, period, period, TimeUnit.SECONDS);
        heartbeat.scheduleWithFixedDelay(this::report, 60, 60, TimeUnit.SECONDS);
        dispatcher = Thread.ofPlatform().name("mailguard-job-dispatcher").start(this::poll);
    }

    private void poll() {
        while (running) {
            try {
                int available = concurrency - active.size();
                if (available > 0) {
                    for (JobStore.Claim claim : store.claim(owner, available, lease)) {
                        Active work = new Active(claim);
                        active.put(claim.id(), work);
                        claimed.increment();
                        tasks.execute(() -> process(work));
                    }
                }
            } catch (RuntimeException e) {
                // Database outages leave claims to expire and be reclaimed by a healthy worker.
                log.warn("Job claim unavailable; retrying without exposing request data");
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void process(Active work) {
        JobStore.Claim claim = work.claim;
        work.thread = Thread.currentThread();
        try {
            if (!store.heartbeat(claim, lease)) {
                stale.increment();
                return;
            }
            EmailValidationRequest request = new EmailValidationRequest();
            request.setEmail(claim.email());
            EmailValidationHistoryService.Computation result;
            try {
                result = validation.compute(request);
            } catch (RuntimeException e) {
                if (store.fail(claim, "VALIDATION_ERROR")) {
                    failed.increment();
                } else {
                    stale.increment();
                }
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (result.retryable()) {
                long power = 1L << Math.min(claim.attempts() - 1, 6);
                long jitter = Math.floorMod(claim.id().getLeastSignificantBits() ^ claim.token(), 501);
                if (store.retry(claim, Duration.ofMillis(power * 1000 + jitter), "DNS_TEMPORARY")) {
                    if (claim.attempts() >= claim.maxAttempts()) {
                        failed.increment();
                    } else {
                        retried.increment();
                    }
                } else {
                    stale.increment();
                }
            } else if (store.complete(claim, result.response())) {
                completed.increment();
            } else {
                stale.increment();
            }
        } catch (RuntimeException e) {
            // A failed database write is never treated as completion. The lease recovers it.
            log.warn("Job persistence unavailable; lease will be reclaimed if needed");
        } finally {
            active.remove(claim.id(), work);
        }
    }

    private void renew() {
        for (Active work : active.values()) {
            try {
                if (!store.heartbeat(work.claim, lease) && work.thread != null) {
                    work.thread.interrupt();
                }
            } catch (RuntimeException e) {
                log.warn("Job heartbeat unavailable; leases remain authoritative");
                break;
            }
        }
    }

    private void report() {
        log.info("Job worker counters claimed={} completed={} retried={} failed={} stale={} active={}",
                claimed.sum(), completed.sum(), retried.sum(), failed.sum(), stale.sum(), active.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (dispatcher != null) {
            dispatcher.interrupt();
        }
        heartbeat.shutdownNow();
        tasks.shutdown();
        try {
            if (!tasks.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
                tasks.shutdownNow();
            }
        } catch (InterruptedException e) {
            tasks.shutdownNow();
            Thread.currentThread().interrupt();
        }
        report();
    }

    private static final class Active {
        private final JobStore.Claim claim;
        private volatile Thread thread;

        private Active(JobStore.Claim claim) {
            this.claim = claim;
        }
    }
}
