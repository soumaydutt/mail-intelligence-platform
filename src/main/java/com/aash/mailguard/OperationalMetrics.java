package com.aash.mailguard;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class OperationalMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    OperationalMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "mailguard.jobs.queued", "SELECT count(*) FROM verification_job WHERE state IN ('PENDING','RETRY')");
        gauge(registry, "mailguard.worker.active_leases", "SELECT count(*) FROM verification_job WHERE state = 'RUNNING' AND lease_expires_at > clock_timestamp()");
        gauge(registry, "mailguard.imports.promotable", "SELECT count(*) FROM import_batch WHERE state IN ('READY','PROCESSING')");
        gauge(registry, "mailguard.imports.ingesting", "SELECT count(*) FROM import_batch WHERE state = 'INGESTING'");
    }

    private void gauge(MeterRegistry registry, String name, String query) {
        Gauge.builder(name, jdbc, template -> {
            try {
                Long count = template.queryForObject(query, Long.class);
                return count == null ? Double.NaN : count.doubleValue();
            } catch (RuntimeException unavailable) {
                return Double.NaN;
            }
        }).register(registry);
    }
}
