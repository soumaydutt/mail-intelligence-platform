package com.aash.mailguard.imports;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class BulkImportStore {
    public record InputRow(long ordinal, String email, String reason) { }
    public record Progress(UUID id, String state, long inputRows, long acceptedRows, long duplicateRows,
                           long rejectedRows, long pendingRows, long runningRows, long retryRows,
                           long completedRows, long failedRows, long cancelledRows,
                           Instant createdAt, Instant finishedAt, String failureCode) { }
    public record ResultRow(long ordinal, String email, String rowState, Long duplicateOf, String reason,
                            String jobState, String resultStatus, String failureCode, Boolean formatValid,
                            Boolean mxRecordFound, Boolean smtpVerified, String domain, String message) { }
    public record RecentMetrics(long importsCreated, long importsCompleted, long importsFailed,
                                long rowsCompleted, long rowsFailed, long rowsCancelled,
                                long promotionLagMillis) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxRows;
    private final int batchSize;
    private final int pageSize;
    private final int maxOutstanding;
    private final int maxAttempts;
    private final int staleMinutes;

    public BulkImportStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${imports.max-rows:50000}") int maxRows,
            @Value("${imports.batch-size:250}") int batchSize,
            @Value("${imports.results-page-size:500}") int pageSize,
            @Value("${imports.stale-minutes:60}") int staleMinutes,
            @Value("${jobs.max-outstanding:20000}") int maxOutstanding,
            @Value("${jobs.max-attempts:3}") int maxAttempts) {
        if (maxRows < 1 || maxRows > 50000 || batchSize < 1 || batchSize > 250
                || pageSize < 1 || pageSize > 500 || staleMinutes < 60 || staleMinutes > 10080
                || maxOutstanding < 1 || maxOutstanding > 1000000 || maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("Invalid bulk import configuration");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxRows = maxRows;
        this.batchSize = batchSize;
        this.pageSize = pageSize;
        this.staleMinutes = staleMinutes;
        this.maxOutstanding = maxOutstanding;
        this.maxAttempts = maxAttempts;
    }

    public UUID begin() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batch(id) VALUES (?)", id);
        return id;
    }

    public void append(UUID id, List<InputRow> rows) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty() || rows.size() > batchSize) {
            throw new IllegalArgumentException("Invalid import batch size");
        }
        transactions.executeWithoutResult(status -> {
            requireIngesting(id);
            int existing = jdbc.queryForObject("SELECT count(*) FROM import_row WHERE import_id = ?", Integer.class, id);
            if (existing + rows.size() > maxRows) {
                throw new IllegalArgumentException("Import row limit exceeded");
            }
            for (InputRow row : rows) {
                if (row == null || row.ordinal() < 1 || row.ordinal() > maxRows
                        || row.email() != null && row.email().length() > 255) {
                    throw new IllegalArgumentException("Invalid import row");
                }
                if (row.reason() != null) {
                    String reason = safeCode(row.reason());
                    jdbc.update("""
                            INSERT INTO import_row(import_id, ordinal, email, row_state, reason)
                            VALUES (?, ?, ?, 'REJECTED', ?)
                            """, id, row.ordinal(), row.email(), reason);
                } else {
                    if (row.email() == null || row.email().isBlank()) {
                        throw new IllegalArgumentException("Invalid accepted address");
                    }
                    int inserted = jdbc.update("""
                            INSERT INTO import_row(import_id, ordinal, email, row_state)
                            VALUES (?, ?, ?, 'ACCEPTED')
                            ON CONFLICT (import_id, email) WHERE row_state = 'ACCEPTED' DO NOTHING
                            """, id, row.ordinal(), row.email());
                    if (inserted == 0) {
                        Long first = jdbc.queryForObject("""
                                SELECT ordinal FROM import_row
                                WHERE import_id = ? AND email = ? AND row_state = 'ACCEPTED'
                                """, Long.class, id, row.email());
                        jdbc.update("""
                                INSERT INTO import_row(import_id, ordinal, row_state, duplicate_of, reason)
                                VALUES (?, ?, 'DUPLICATE', ?, 'DUPLICATE')
                                """, id, row.ordinal(), first);
                    }
                }
            }
            jdbc.update("UPDATE import_batch SET updated_at = clock_timestamp() WHERE id = ?", id);
        });
    }

    public void finish(UUID id) {
        int changed = jdbc.update("""
                UPDATE import_batch b
                SET state = CASE WHEN EXISTS (SELECT 1 FROM import_row r
                            WHERE r.import_id = b.id AND r.row_state = 'ACCEPTED')
                        THEN 'READY' ELSE 'COMPLETED' END,
                    finished_at = CASE WHEN EXISTS (SELECT 1 FROM import_row r
                            WHERE r.import_id = b.id AND r.row_state = 'ACCEPTED')
                        THEN NULL ELSE clock_timestamp() END,
                    ready_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE b.id = ? AND b.state = 'INGESTING'
                """, id);
        if (changed != 1) {
            throw new IllegalStateException("Import is not ingesting");
        }
    }

    public void fail(UUID id, String code) {
        jdbc.update("""
                UPDATE import_batch
                SET state = 'FAILED', failure_code = ?, finished_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ? AND state = 'INGESTING'
                """, safeCode(code), id);
    }

    public Optional<Progress> find(UUID id) {
        List<Progress> terminal = jdbc.query("""
                SELECT id, state, created_at, finished_at, failure_code,
                    input_rows, accepted_rows, duplicate_rows, rejected_rows,
                    pending_rows, running_rows, retry_rows, completed_rows, failed_rows, cancelled_rows
                FROM import_batch WHERE id = ? AND state IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, BulkImportStore::progress, id);
        if (!terminal.isEmpty()) {
            return Optional.of(terminal.getFirst());
        }
        List<Progress> rows = jdbc.query("""
                SELECT b.id, b.state, b.created_at, b.finished_at, b.failure_code,
                    count(r.ordinal) AS input_rows,
                    count(*) FILTER (WHERE r.row_state = 'ACCEPTED') AS accepted_rows,
                    count(*) FILTER (WHERE r.row_state = 'DUPLICATE') AS duplicate_rows,
                    count(*) FILTER (WHERE r.row_state = 'REJECTED') AS rejected_rows,
                    count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND
                        (j.state = 'PENDING' OR (j.id IS NULL AND b.state IN ('INGESTING', 'READY', 'PROCESSING')))) AS pending_rows,
                    count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'RUNNING') AS running_rows,
                    count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'RETRY') AS retry_rows,
                    count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'COMPLETED') AS completed_rows,
                    count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'FAILED') AS failed_rows,
                    count(*) FILTER (WHERE r.row_state = 'CANCELLED' OR
                        r.row_state = 'ACCEPTED' AND j.state = 'CANCELLED') AS cancelled_rows
                FROM import_batch b
                LEFT JOIN import_row r ON r.import_id = b.id
                LEFT JOIN verification_job j ON j.id = r.job_id
                WHERE b.id = ?
                GROUP BY b.id
                """, BulkImportStore::progress, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean cancel(UUID id) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<String> states = jdbc.query("SELECT state FROM import_batch WHERE id = ? FOR UPDATE",
                    (rs, row) -> rs.getString(1), id);
            if (states.isEmpty()) {
                return false;
            }
            String state = states.getFirst();
            if ("CANCELLED".equals(state)) {
                return true;
            }
            if ("COMPLETED".equals(state) || "FAILED".equals(state)) {
                return false;
            }
            // Worker completions win if all work finished before this import lock was taken.
            if (!"INGESTING".equals(state) && allFinished(id)) {
                complete(id);
                return false;
            }
            int cancelledJobs = jdbc.update("""
                    UPDATE verification_job j
                    SET state = 'CANCELLED', finished_at = clock_timestamp(), updated_at = clock_timestamp(),
                        failure_code = NULL, lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                    FROM import_row r
                    WHERE r.import_id = ? AND r.job_id = j.id
                      AND j.state IN ('PENDING', 'RUNNING', 'RETRY')
                    """, id);
            // A completion that raced this cancellation may have won every job row lock.
            if (cancelledJobs == 0 && !"INGESTING".equals(state) && allFinished(id)) {
                complete(id);
                return false;
            }
            jdbc.update("""
                    UPDATE import_row r SET row_state = 'CANCELLED'
                    FROM verification_job j
                    WHERE r.import_id = ? AND r.job_id = j.id AND j.state = 'CANCELLED'
                      AND r.row_state = 'ACCEPTED'
                    """, id);
            jdbc.update("""
                    UPDATE import_row SET row_state = 'CANCELLED'
                    WHERE import_id = ? AND row_state = 'ACCEPTED' AND job_id IS NULL
                    """, id);
            jdbc.update("""
                    UPDATE import_batch SET state = 'CANCELLED', finished_at = clock_timestamp(),
                        updated_at = clock_timestamp() WHERE id = ?
                    """, id);
            return true;
        }));
    }

    public List<ResultRow> results(UUID id, long after, int limit) {
        if (after < 0 || limit < 1 || limit > pageSize) {
            throw new IllegalArgumentException("Invalid results page");
        }
        return jdbc.query("""
                SELECT r.ordinal, COALESCE(r.email, first_row.email) AS email,
                       r.row_state, r.duplicate_of, r.reason,
                       j.state AS job_state, j.result_status, j.failure_code,
                       j.result_format_valid, j.result_mx_record_found, j.result_smtp_verified,
                       j.result_domain, j.result_message
                FROM import_row r
                LEFT JOIN import_row first_row ON first_row.import_id = r.import_id
                     AND first_row.ordinal = r.duplicate_of
                LEFT JOIN verification_job j ON j.id = COALESCE(r.job_id, first_row.job_id)
                WHERE r.import_id = ? AND r.ordinal > ?
                ORDER BY r.ordinal LIMIT ?
                """, (rs, row) -> new ResultRow(rs.getLong("ordinal"), rs.getString("email"),
                rs.getString("row_state"), (Long) rs.getObject("duplicate_of"), rs.getString("reason"),
                rs.getString("job_state"), rs.getString("result_status"), rs.getString("failure_code"),
                (Boolean) rs.getObject("result_format_valid"), (Boolean) rs.getObject("result_mx_record_found"),
                (Boolean) rs.getObject("result_smtp_verified"), rs.getString("result_domain"),
                rs.getString("result_message")), id, after, limit);
    }

    public int promote(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid promotion size");
        }
        recoverAndComplete(Math.min(limit, batchSize));
        // The existing single-job admission path holds this same lock while counting outstanding jobs.
        int promoted = Objects.requireNonNull(transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(20261017, 7)", rs -> { rs.next(); return null; });
            int outstanding = jdbc.queryForObject("""
                    SELECT count(*) FROM verification_job WHERE state IN ('PENDING', 'RUNNING', 'RETRY')
                    """, Integer.class);
            int available = Math.min(batchSize, Math.min(limit, maxOutstanding - outstanding));
            if (available <= 0) {
                return 0;
            }
            List<UUID> imports = jdbc.query("""
                    SELECT b.id FROM import_batch b
                    WHERE b.state IN ('READY', 'PROCESSING')
                      AND EXISTS (SELECT 1 FROM import_row r
                          WHERE r.import_id = b.id AND r.row_state = 'ACCEPTED' AND r.job_id IS NULL)
                    ORDER BY b.created_at, b.id LIMIT 1 FOR UPDATE OF b SKIP LOCKED
                    """, (rs, row) -> rs.getObject(1, UUID.class));
            if (imports.isEmpty()) {
                return 0;
            }
            UUID id = imports.getFirst();
            List<InputRow> candidates = jdbc.query("""
                    SELECT ordinal, email FROM import_row
                    WHERE import_id = ? AND row_state = 'ACCEPTED' AND job_id IS NULL
                    ORDER BY ordinal LIMIT ?
                    """, (rs, row) -> new InputRow(rs.getLong(1), rs.getString(2), null), id, available);
            for (InputRow row : candidates) {
                UUID jobId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO verification_job(id, email, request_fingerprint, max_attempts)
                        VALUES (?, ?, ?, ?)
                        """, jobId, row.email(), fingerprint(row.email()), maxAttempts);
                jdbc.update("UPDATE import_row SET job_id = ? WHERE import_id = ? AND ordinal = ?",
                        jobId, id, row.ordinal());
            }
            jdbc.update("""
                    UPDATE import_batch SET state = 'PROCESSING', updated_at = clock_timestamp()
                    WHERE id = ? AND state = 'READY'
                    """, id);
            return candidates.size();
        }));
        return promoted;
    }

    private void recoverAndComplete(int limit) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    WITH stale AS (
                        SELECT id FROM import_batch WHERE state = 'INGESTING'
                            AND updated_at < clock_timestamp() - (? * interval '1 minute')
                        ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    UPDATE import_batch b SET state = 'FAILED', failure_code = 'INGEST_INTERRUPTED',
                        finished_at = clock_timestamp(), updated_at = clock_timestamp()
                    FROM stale WHERE b.id = stale.id
                    """, staleMinutes, limit);
            List<UUID> finished = jdbc.query("""
                    SELECT b.id FROM import_batch b WHERE b.state IN ('READY', 'PROCESSING')
                        AND NOT EXISTS (SELECT 1 FROM import_row r WHERE r.import_id = b.id
                            AND r.row_state = 'ACCEPTED' AND (r.job_id IS NULL OR EXISTS (
                                SELECT 1 FROM verification_job j WHERE j.id = r.job_id
                                    AND j.state IN ('PENDING', 'RUNNING', 'RETRY'))))
                    ORDER BY b.created_at, b.id LIMIT ? FOR UPDATE OF b SKIP LOCKED
                    """, (rs, row) -> rs.getObject(1, UUID.class), limit);
            for (UUID id : finished) {
                complete(id);
            }
        });
    }

    private boolean allFinished(UUID id) {
        return Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM import_row r
                    LEFT JOIN verification_job j ON j.id = r.job_id
                    WHERE r.import_id = ? AND r.row_state = 'ACCEPTED'
                        AND (r.job_id IS NULL OR j.state IN ('PENDING', 'RUNNING', 'RETRY')))
                """, Boolean.class, id));
    }

    private void complete(UUID id) {
        jdbc.update("""
                UPDATE import_batch SET state = 'COMPLETED', finished_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ? AND state IN ('READY', 'PROCESSING')
                """, id);
    }

    public RecentMetrics recentMetrics() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT count(*) FROM import_batch WHERE created_at > clock_timestamp() - interval '1 minute')
                        AS imports_created,
                    (SELECT count(*) FROM import_batch WHERE state = 'COMPLETED'
                        AND finished_at > clock_timestamp() - interval '1 minute') AS imports_completed,
                    (SELECT count(*) FROM import_batch WHERE state = 'FAILED'
                        AND finished_at > clock_timestamp() - interval '1 minute') AS imports_failed,
                    (SELECT count(*) FROM verification_job j WHERE state = 'COMPLETED'
                        AND finished_at > clock_timestamp() - interval '1 minute'
                        AND EXISTS (SELECT 1 FROM import_row r WHERE r.job_id = j.id)) AS rows_completed,
                    (SELECT count(*) FROM verification_job j WHERE state = 'FAILED'
                        AND finished_at > clock_timestamp() - interval '1 minute'
                        AND EXISTS (SELECT 1 FROM import_row r WHERE r.job_id = j.id)) AS rows_failed,
                    (SELECT count(*) FROM verification_job j WHERE state = 'CANCELLED'
                        AND finished_at > clock_timestamp() - interval '1 minute'
                        AND EXISTS (SELECT 1 FROM import_row r WHERE r.job_id = j.id))
                    + (SELECT count(*) FROM import_row r JOIN import_batch b ON b.id = r.import_id
                        WHERE r.row_state = 'CANCELLED' AND r.job_id IS NULL
                          AND b.finished_at > clock_timestamp() - interval '1 minute') AS rows_cancelled,
                    (SELECT COALESCE(round(avg(extract(epoch FROM (j.created_at - b.ready_at)) * 1000)), 0)
                        FROM verification_job j JOIN import_row r ON r.job_id = j.id
                        JOIN import_batch b ON b.id = r.import_id
                        WHERE j.created_at > clock_timestamp() - interval '1 minute')
                        AS promotion_lag_ms
                """, (rs, row) -> new RecentMetrics(rs.getLong("imports_created"),
                rs.getLong("imports_completed"), rs.getLong("imports_failed"), rs.getLong("rows_completed"),
                rs.getLong("rows_failed"), rs.getLong("rows_cancelled"), rs.getLong("promotion_lag_ms")));
    }

    private void requireIngesting(UUID id) {
        List<String> states = jdbc.query("SELECT state FROM import_batch WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), id);
        if (states.isEmpty() || !"INGESTING".equals(states.getFirst())) {
            throw new IllegalStateException("Import is not ingesting");
        }
    }

    private static Progress progress(ResultSet rs, int row) throws SQLException {
        return new Progress(rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getLong("input_rows"), rs.getLong("accepted_rows"), rs.getLong("duplicate_rows"),
                rs.getLong("rejected_rows"), rs.getLong("pending_rows"), rs.getLong("running_rows"),
                rs.getLong("retry_rows"), rs.getLong("completed_rows"), rs.getLong("failed_rows"),
                rs.getLong("cancelled_rows"), rs.getTimestamp("created_at").toInstant(),
                instant(rs, "finished_at"), rs.getString("failure_code"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String safeCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid import reason code");
        }
        return code;
    }

    private static byte[] fingerprint(String email) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
