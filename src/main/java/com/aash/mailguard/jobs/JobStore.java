package com.aash.mailguard.jobs;

import com.aash.mailguard.dto.EmailValidationResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
public class JobStore {
    public record Submission(UUID id, boolean reused) { }
    public record Claim(UUID id, String email, String owner, long token, int attempts, int maxAttempts) { }
    public record JobStatus(UUID id, String state, int attempts, int maxAttempts, Instant dueAt,
                            Instant createdAt, Instant finishedAt, String failureCode,
                            EmailValidationResponse result) { }

    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict() {
            super("Idempotency key already belongs to a different request");
        }
    }
    public static class QueueFull extends RuntimeException {
        public QueueFull() {
            super("Job backlog is full");
        }
    }


    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final int maxOutstanding;

    public JobStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                    @Value("${jobs.max-attempts:3}") int maxAttempts,
                    @Value("${jobs.max-outstanding:20000}") int maxOutstanding) {
        if (maxAttempts < 1 || maxAttempts > 20 || maxOutstanding < 1 || maxOutstanding > 1000000) {
            throw new IllegalArgumentException("Invalid job admission configuration");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
        this.maxOutstanding = maxOutstanding;
    }

    public Submission submit(String email, String idempotencyKey) {
        if (email == null || email.isBlank() || email.length() > 255) {
            throw new IllegalArgumentException("Invalid job address");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        byte[] fingerprint = fingerprint(email);
        return Objects.requireNonNull(transactions.execute(status -> {
            // Serialize distributed admissions so the persisted backlog never exceeds its cap.
            jdbc.query("SELECT pg_advisory_xact_lock(20261017, 7)", rs -> { rs.next(); return null; });
            if (idempotencyKey != null) {
                Submission replay = existingSubmission(idempotencyKey, fingerprint);
                if (replay != null) {
                    return replay;
                }
            }
            int outstanding = jdbc.queryForObject("""
                    SELECT count(*) FROM verification_job WHERE state IN ('PENDING', 'RUNNING', 'RETRY')
                    """, Integer.class);
            if (outstanding >= maxOutstanding) {
                throw new QueueFull();
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO verification_job (id, email, idempotency_key, request_fingerprint, max_attempts)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, email, idempotencyKey, fingerprint, maxAttempts);
            return new Submission(id, false);
        }));
    }

    private Submission existingSubmission(String idempotencyKey, byte[] fingerprint) {
        List<Submission> rows = jdbc.query("""
                SELECT id, request_fingerprint FROM verification_job WHERE idempotency_key = ?
                """, (rs, row) -> {
                    if (!MessageDigest.isEqual(fingerprint, rs.getBytes("request_fingerprint"))) {
                        throw new IdempotencyConflict();
                    }
                    return new Submission(rs.getObject("id", UUID.class), true);
                }, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Optional<JobStatus> find(UUID id) {
        List<JobStatus> rows = jdbc.query("""
                SELECT id, state, attempts, max_attempts, due_at, created_at, finished_at,
                       failure_code, result_email, result_domain, result_format_valid,
                       result_mx_record_found, result_smtp_verified, result_status, result_message
                FROM verification_job WHERE id = ?
                """, JobStore::mapStatus, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean cancel(UUID id) {
        int changed = jdbc.update("""
                UPDATE verification_job
                SET state = 'CANCELLED', finished_at = clock_timestamp(), updated_at = clock_timestamp(),
                    failure_code = NULL, lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                WHERE id = ? AND state IN ('PENDING', 'RUNNING', 'RETRY')
                """, id);
        return changed == 1 || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM verification_job WHERE id = ? AND state = 'CANCELLED')
                """, Boolean.class, id));
    }

    public List<Claim> claim(String owner, int limit, Duration lease) {
        if (owner == null || owner.isBlank() || owner.length() > 255 || limit < 1) {
            throw new IllegalArgumentException("Invalid claim arguments");
        }
        long leaseMillis = positiveMillis(lease);
        return Objects.requireNonNull(transactions.execute(status -> {
            // A worker that died on its last attempt must not leave an unclaimable RUNNING job.
            jdbc.update("""
                    UPDATE verification_job
                    SET state = 'FAILED', failure_code = 'LEASE_EXPIRED',
                        finished_at = clock_timestamp(), updated_at = clock_timestamp(),
                        lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                    WHERE state = 'RUNNING' AND attempts >= max_attempts
                      AND lease_expires_at <= clock_timestamp()
                    """);
            return jdbc.query("""
                    WITH due AS (
                        SELECT id FROM verification_job
                        WHERE attempts < max_attempts AND (
                            (state IN ('PENDING', 'RETRY') AND due_at <= clock_timestamp())
                            OR (state = 'RUNNING' AND lease_expires_at <= clock_timestamp())
                        )
                        ORDER BY due_at, created_at, id
                        LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    UPDATE verification_job AS job
                    SET state = 'RUNNING', attempts = job.attempts + 1,
                        lease_token = job.lease_token + 1, lease_owner = ?,
                        lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                        heartbeat_at = clock_timestamp(), updated_at = clock_timestamp(),
                        failure_code = NULL
                    FROM due WHERE job.id = due.id
                    RETURNING job.id, job.email, job.lease_owner, job.lease_token,
                              job.attempts, job.max_attempts
                    """, (rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getString("email"),
                    rs.getString("lease_owner"), rs.getLong("lease_token"), rs.getInt("attempts"),
                    rs.getInt("max_attempts")), limit, owner, leaseMillis);
        }));
    }

    public boolean heartbeat(Claim claim, Duration lease) {
        return jdbc.update("""
                UPDATE verification_job
                SET heartbeat_at = clock_timestamp(),
                    lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE id = ? AND state = 'RUNNING' AND lease_owner = ? AND lease_token = ?
                  AND lease_expires_at > clock_timestamp()
                """, positiveMillis(lease), claim.id(), claim.owner(), claim.token()) == 1;
    }

    public boolean complete(Claim claim, EmailValidationResponse response) {
        Objects.requireNonNull(response, "response");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE verification_job
                    SET state = 'COMPLETED', result_email = ?, result_domain = ?,
                        result_format_valid = ?, result_mx_record_found = ?, result_smtp_verified = ?,
                        result_status = ?, result_message = ?, failure_code = NULL,
                        finished_at = clock_timestamp(), updated_at = clock_timestamp(),
                        lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                    WHERE id = ? AND email = ? AND state = 'RUNNING' AND lease_owner = ?
                      AND lease_token = ? AND lease_expires_at > clock_timestamp()
                    """, response.getEmail(), response.getDomain(), response.getFormatValid(),
                    response.getMxRecordFound(), response.getSmtpVerified(), response.getStatus(),
                    response.getMessage(), claim.id(), response.getEmail(), claim.owner(), claim.token());
            if (updated == 0) {
                return false;
            }
            jdbc.update("""
                    INSERT INTO email_validation_history
                        (job_id, email, domain, format_valid, mx_record_found, smtp_verified, status, message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, claim.id(), response.getEmail(), response.getDomain(), response.getFormatValid(),
                    response.getMxRecordFound(), response.getSmtpVerified(), response.getStatus(),
                    response.getMessage());
            return true;
        }));
    }

    public boolean retry(Claim claim, Duration backoff, String safeFailureCode) {
        long backoffMillis = positiveMillis(backoff);
        String code = failureCode(safeFailureCode);
        return jdbc.update("""
                UPDATE verification_job
                SET state = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'RETRY' END,
                    due_at = clock_timestamp() + (? * interval '1 millisecond'),
                    failure_code = ?,
                    finished_at = CASE WHEN attempts >= max_attempts THEN clock_timestamp() ELSE NULL END,
                    updated_at = clock_timestamp(),
                    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                WHERE id = ? AND state = 'RUNNING' AND lease_owner = ? AND lease_token = ?
                  AND lease_expires_at > clock_timestamp()
                """, backoffMillis, code, claim.id(), claim.owner(), claim.token()) == 1;
    }

    public boolean fail(Claim claim, String safeFailureCode) {
        String code = failureCode(safeFailureCode);
        return jdbc.update("""
                UPDATE verification_job
                SET state = 'FAILED', failure_code = ?, finished_at = clock_timestamp(),
                    updated_at = clock_timestamp(), lease_owner = NULL,
                    lease_expires_at = NULL, heartbeat_at = NULL
                WHERE id = ? AND state = 'RUNNING' AND lease_owner = ? AND lease_token = ?
                  AND lease_expires_at > clock_timestamp()
                """, code, claim.id(), claim.owner(), claim.token()) == 1;
    }

    private static JobStatus mapStatus(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        EmailValidationResponse response = null;
        if ("COMPLETED".equals(state)) {
            response = new EmailValidationResponse();
            response.setEmail(rs.getString("result_email"));
            response.setDomain(rs.getString("result_domain"));
            response.setFormatValid((Boolean) rs.getObject("result_format_valid"));
            response.setMxRecordFound((Boolean) rs.getObject("result_mx_record_found"));
            response.setSmtpVerified((Boolean) rs.getObject("result_smtp_verified"));
            response.setStatus(rs.getString("result_status"));
            response.setMessage(rs.getString("result_message"));
        }
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new JobStatus(rs.getObject("id", UUID.class), state, rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(), rs.getString("failure_code"), response);
    }

    private static byte[] fingerprint(String email) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long positiveMillis(Duration duration) {
        long millis = Objects.requireNonNull(duration, "duration").toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("Duration must be at least one millisecond");
        }
        return millis;
    }

    private static String failureCode(String code) {
        if (code == null || code.isEmpty() || code.length() > 64 || code.charAt(0) < 'A'
                || code.charAt(0) > 'Z') {
            throw new IllegalArgumentException("Invalid failure code");
        }
        for (int i = 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if ((c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') {
                throw new IllegalArgumentException("Invalid failure code");
            }
        }
        return code;
    }
}
