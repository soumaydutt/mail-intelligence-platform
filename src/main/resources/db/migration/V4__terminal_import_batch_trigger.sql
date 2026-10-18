ALTER TABLE import_row ADD CONSTRAINT chk_import_row_duplicate_prior
    CHECK (duplicate_of IS NULL OR duplicate_of < ordinal);

ALTER TABLE import_batch
    ADD COLUMN input_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN accepted_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN duplicate_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN rejected_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN pending_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN running_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN retry_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN completed_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN failed_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cancelled_rows BIGINT NOT NULL DEFAULT 0;

-- Backfill terminal batches written before V4 without changing their state or timestamps.
UPDATE import_batch b SET
    input_rows = counts.input_rows,
    accepted_rows = counts.accepted_rows,
    duplicate_rows = counts.duplicate_rows,
    rejected_rows = counts.rejected_rows,
    pending_rows = counts.pending_rows,
    running_rows = counts.running_rows,
    retry_rows = counts.retry_rows,
    completed_rows = counts.completed_rows,
    failed_rows = counts.failed_rows,
    cancelled_rows = counts.cancelled_rows
FROM (
    SELECT b.id,
        count(r.ordinal) AS input_rows,
        count(*) FILTER (WHERE r.row_state = 'ACCEPTED') AS accepted_rows,
        count(*) FILTER (WHERE r.row_state = 'DUPLICATE') AS duplicate_rows,
        count(*) FILTER (WHERE r.row_state = 'REJECTED') AS rejected_rows,
        count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'PENDING') AS pending_rows,
        count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'RUNNING') AS running_rows,
        count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'RETRY') AS retry_rows,
        count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'COMPLETED') AS completed_rows,
        count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'FAILED') AS failed_rows,
        count(*) FILTER (WHERE r.row_state = 'CANCELLED' OR
            r.row_state = 'ACCEPTED' AND j.state = 'CANCELLED') AS cancelled_rows
    FROM import_batch b
    LEFT JOIN import_row r ON r.import_id = b.id
    LEFT JOIN verification_job j ON j.id = r.job_id
    WHERE b.state IN ('COMPLETED', 'FAILED', 'CANCELLED')
    GROUP BY b.id
) counts
WHERE b.id = counts.id;

CREATE FUNCTION prevent_terminal_import_batch_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal import batch cannot be updated';
    END IF;
    IF NEW.state IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
        EXECUTE format($query$
            SELECT count(r.ordinal),
                count(*) FILTER (WHERE r.row_state = 'ACCEPTED'),
                count(*) FILTER (WHERE r.row_state = 'DUPLICATE'),
                count(*) FILTER (WHERE r.row_state = 'REJECTED'),
                count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'PENDING'),
                count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'RUNNING'),
                count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'RETRY'),
                count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'COMPLETED'),
                count(*) FILTER (WHERE r.row_state = 'ACCEPTED' AND j.state = 'FAILED'),
                count(*) FILTER (WHERE r.row_state = 'CANCELLED' OR
                    r.row_state = 'ACCEPTED' AND j.state = 'CANCELLED')
            FROM %I.import_row r
            LEFT JOIN %I.verification_job j ON j.id = r.job_id
            WHERE r.import_id = $1
        $query$, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA)
        INTO NEW.input_rows, NEW.accepted_rows, NEW.duplicate_rows, NEW.rejected_rows,
            NEW.pending_rows, NEW.running_rows, NEW.retry_rows, NEW.completed_rows,
            NEW.failed_rows, NEW.cancelled_rows
        USING NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER import_batch_terminal_immutable
BEFORE UPDATE ON import_batch
FOR EACH ROW EXECUTE FUNCTION prevent_terminal_import_batch_update();
