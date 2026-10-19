package com.aash.mailguard.delivery;

import com.aash.mailguard.validator.EmailFormatValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class DeliveryEvidenceService {
    public enum EventType { HARD_BOUNCE, SOFT_BOUNCE, DELIVERED, COMPLAINT, UNSUBSCRIBED }
    public record EventInput(String email, EventType eventType, String externalEventId,
                             Instant occurredAt, String enhancedStatusCode) { }
    public record Ingested(UUID id, boolean reused) { }
    public record Snapshot(boolean available, UUID eventId, String eventType, String interpretation,
                           Instant occurredAt, String sourceType, String sourceIdentifier,
                           String freshness, Boolean suppressed, String suppressionReason,
                           Instant observedAt, String limitation) {
        public static Snapshot unavailable() {
            return new Snapshot(false, null, null, null, null, null, null,
                    "UNAVAILABLE", null, null, null, "Historical evidence unavailable.");
        }
        private static Snapshot none() {
            return new Snapshot(false, null, null, null, null, null, null,
                    "NONE", false, null, null, "No delivery event observed.");
        }
    }
    public static class InvalidInput extends RuntimeException {
        public InvalidInput() { super("Invalid delivery evidence input"); }
    }
    public static class Conflict extends RuntimeException {
        public Conflict() { super("Delivery event identity conflict"); }
    }
    private static final Instant FIRST_REASONABLE_EVENT = Instant.parse("2000-01-01T00:00:00Z");
    private static final String LIMITATION = "Historical observations do not prove current deliverability.";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern STATUS = Pattern.compile("[245]\\.[0-9]{1,3}\\.[0-9]{1,3}");
    private static final Pattern TRANSIENT_STATUS = Pattern.compile("4\\.[0-9]{1,3}\\.[0-9]{1,3}");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EmailFormatValidator validator;
    private final String sourceId;
    private final int freshDays;
    public DeliveryEvidenceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                   EmailFormatValidator validator,
                                   @Value("${delivery.source-id:}") String sourceId,
                                   @Value("${delivery.fresh-days:30}") int freshDays) {
        if (freshDays < 1 || freshDays > 3650) {
            throw new IllegalArgumentException("Invalid delivery freshness configuration");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.validator = validator;
        this.sourceId = sourceId;
        this.freshDays = freshDays;
    }
    public Ingested ingest(EventInput input) {
        if (input == null || input.eventType() == null || input.occurredAt() == null
                || !identifier(sourceId, 128) || !identifier(input.externalEventId(), 255)
                || input.occurredAt().isBefore(FIRST_REASONABLE_EVENT)
                || input.occurredAt().isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))
                || input.occurredAt().getNano() % 1000 != 0
                || (input.enhancedStatusCode() != null
                    && (input.enhancedStatusCode().isEmpty() || input.enhancedStatusCode().length() > 32
                        || !printable(input.enhancedStatusCode())))) {
            throw new InvalidInput();
        }
        String key = emailKey(input.email());
        String status = input.enhancedStatusCode() != null
                && STATUS.matcher(input.enhancedStatusCode()).matches() ? input.enhancedStatusCode() : null;
        byte[] fingerprint = fingerprint(input.enhancedStatusCode());
        String interpretation = interpret(input.eventType(), status);
        return Objects.requireNonNull(transactions.execute(tx -> {
            lockIdentity(key);
            UUID id = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                    INSERT INTO delivery_event
                        (id, email_key, email, event_type, source_identifier, external_event_id,
                         occurred_at, enhanced_status_code, status_fingerprint, interpretation)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source_type, source_identifier, external_event_id) DO NOTHING
                    RETURNING id
                    """, (rs, row) -> rs.getObject(1, UUID.class), id, key, input.email(),
                    input.eventType().name(), sourceId, input.externalEventId(),
                    Timestamp.from(input.occurredAt()), status, fingerprint, interpretation);
            if (inserted.isEmpty()) {
                return jdbc.queryForObject("""
                        SELECT id, email, event_type, occurred_at, status_fingerprint
                        FROM delivery_event
                        WHERE source_type = 'ADMIN_JSON' AND source_identifier = ? AND external_event_id = ?
                        """, (rs, row) -> {
                    if (!input.email().equals(rs.getString("email"))
                            || !input.eventType().name().equals(rs.getString("event_type"))
                            || !input.occurredAt().equals(rs.getTimestamp("occurred_at").toInstant())
                            || !Arrays.equals(fingerprint, rs.getBytes("status_fingerprint"))) {
                        throw new Conflict();
                    }
                    return new Ingested(rs.getObject("id", UUID.class), true);
                }, sourceId, input.externalEventId());
            }
            if (suppresses(input.eventType(), interpretation)) {
                jdbc.update("""
                        INSERT INTO delivery_suppression (id, email_key, reason, source_event_id)
                        VALUES (?, ?, ?, ?)
                        """, UUID.randomUUID(), key, input.eventType().name(), id);
            }
            return new Ingested(id, false);
        }));
    }

    public Snapshot lookup(String email) {
        String key = emailKey(email);
        // Both indexed lateral probes belong to one READ COMMITTED statement, not two racy reads.
        return jdbc.query("""
                SELECT e.id AS event_id, e.event_type, e.interpretation, e.occurred_at,
                       e.source_type, e.source_identifier, e.observed_at, s.reason AS suppression_reason
                FROM delivery_identity i
                LEFT JOIN LATERAL (
                    SELECT id, event_type, interpretation, occurred_at, source_type,
                           source_identifier, observed_at
                    FROM delivery_event
                    WHERE email_key = i.email_key AND revoked_at IS NULL
                    ORDER BY occurred_at DESC, observed_at DESC, id DESC LIMIT 1
                ) e ON true
                LEFT JOIN LATERAL (
                    SELECT reason FROM delivery_suppression
                    WHERE email_key = i.email_key AND lifted_at IS NULL
                      AND effective_at <= clock_timestamp()
                      AND (expires_at IS NULL OR expires_at > clock_timestamp())
                    ORDER BY effective_at DESC, id DESC LIMIT 1
                ) s ON true
                WHERE i.email_key = ?
                """, (rs, row) -> {
            UUID id = rs.getObject("event_id", UUID.class);
            String suppressionReason = rs.getString("suppression_reason");
            if (id == null && suppressionReason == null) return Snapshot.none();
            Instant occurred = time(rs, "occurred_at");
            return new Snapshot(true, id, rs.getString("event_type"), rs.getString("interpretation"),
                    occurred, rs.getString("source_type"), rs.getString("source_identifier"),
                    occurred == null ? "NONE" : occurred.isBefore(Instant.now().minus(freshDays, ChronoUnit.DAYS))
                            ? "STALE" : "FRESH",
                    suppressionReason != null, suppressionReason, time(rs, "observed_at"),
                    occurred == null ? "No delivery event observed; suppression remains active." : LIMITATION);
        }, key).stream().findFirst().orElseGet(Snapshot::none);
    }
    private void lockIdentity(String key) {
        jdbc.update("INSERT INTO delivery_identity (email_key) VALUES (?) ON CONFLICT DO NOTHING", key);
        jdbc.query("SELECT email_key FROM delivery_identity WHERE email_key = ? FOR UPDATE",
                rs -> { rs.next(); return null; }, key);
    }

    private String emailKey(String email) {
        if (email == null) throw new InvalidInput();
        EmailFormatValidator.Result result = validator.validate(email);
        if (!result.valid()) throw new InvalidInput();
        return email.substring(0, email.lastIndexOf('@') + 1) + result.domain();
    }

    private static byte[] fingerprint(String status) {
        if (status == null) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(status.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean printable(String status) {
        for (int i = 0; i < status.length(); i++) {
            if (status.charAt(i) < ' ' || status.charAt(i) > '~') return false;
        }
        return true;
    }
    private static boolean identifier(String id, int max) {
        return id != null && id.length() <= max && IDENTIFIER.matcher(id).matches();
    }

    private static String interpret(EventType type, String status) {
        return switch (type) {
            case HARD_BOUNCE -> "5.1.1".equals(status) ? "RECIPIENT_HARD_BOUNCE" : "UNCERTAIN_BOUNCE";
            case SOFT_BOUNCE -> status != null && TRANSIENT_STATUS.matcher(status).matches()
                    ? "TRANSIENT_BOUNCE" : "UNCERTAIN_BOUNCE";
            case DELIVERED -> "HISTORICAL_DELIVERY";
            case COMPLAINT -> "COMPLAINT";
            case UNSUBSCRIBED -> "UNSUBSCRIBED";
        };
    }

    private static boolean suppresses(EventType type, String interpretation) {
        return type == EventType.COMPLAINT || type == EventType.UNSUBSCRIBED
                || "RECIPIENT_HARD_BOUNCE".equals(interpretation);
    }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
