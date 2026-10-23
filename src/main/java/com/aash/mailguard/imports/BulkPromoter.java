package com.aash.mailguard.imports;

import com.aash.mailguard.jobs.WorkerHealthProbe;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "jobs.worker", name = "enabled", havingValue = "true")
public class BulkPromoter {
    private static final Logger log = LoggerFactory.getLogger(BulkPromoter.class);
    private final BulkImportStore store;
    private final int batchSize;
    private final long pollMillis;
    private final LongAdder promoted = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final AtomicLong lastPollSuccessEpochSeconds = new AtomicLong();
    private final WorkerHealthProbe health;
    private volatile boolean running;
    private Thread dispatcher;

    public BulkPromoter(BulkImportStore store,
            @Value("${imports.batch-size:250}") int batchSize,
            @Value("${imports.worker.poll-millis:500}") long pollMillis) {
        if (batchSize < 1 || batchSize > 250 || pollMillis < 50 || pollMillis > 60000) {
            throw new IllegalArgumentException("Invalid bulk promoter configuration");
        }
        this.store = store;
        this.batchSize = batchSize;
        this.pollMillis = pollMillis;
        this.health = new WorkerHealthProbe("import-promoter", pollMillis);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        dispatcher = Thread.ofPlatform().name("mailguard-import-promoter").start(this::poll);
    }

    private void poll() {
        long cycles = 0;
        while (running) {
            try {
                promoted.add(store.promote(batchSize));
                lastPollSuccessEpochSeconds.set(java.time.Instant.now().getEpochSecond());
                health.succeeded();
            } catch (RuntimeException e) {
                health.failed();
                errors.increment();
                log.warn("Import promotion unavailable; retrying without request data");
            }
            if (++cycles % Math.max(1L, TimeUnit.MINUTES.toMillis(1) / pollMillis) == 0) {
                report();
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void report() {
        try {
            BulkImportStore.RecentMetrics recent = store.recentMetrics();
            log.info("Import worker promoted={} errors={} last_poll_success_epoch_seconds={} running={} "
                    + "last_minute_created={} last_minute_completed={} last_minute_failed={} "
                    + "last_minute_rows_completed={} last_minute_rows_failed={} "
                    + "last_minute_rows_cancelled={} last_minute_promotion_lag_ms={}",
                    promoted.sum(), errors.sum(), lastPollSuccessEpochSeconds.get(), running,
                    recent.importsCreated(), recent.importsCompleted(), recent.importsFailed(),
                    recent.rowsCompleted(), recent.rowsFailed(), recent.rowsCancelled(), recent.promotionLagMillis());
        } catch (RuntimeException e) {
            log.warn("Import metrics unavailable; continuing without request data");
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        health.close();
        if (dispatcher != null) {
            dispatcher.interrupt();
            try {
                dispatcher.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Import promoter counters promoted={} errors={}", promoted.sum(), errors.sum());
    }
}
