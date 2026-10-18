CREATE TABLE import_batch (
    id UUID PRIMARY KEY,
    state VARCHAR(16) NOT NULL DEFAULT 'INGESTING'
        CHECK (state IN ('INGESTING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    CONSTRAINT import_batch_terminal CHECK (
        (state IN ('COMPLETED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
        OR (state IN ('INGESTING', 'READY', 'PROCESSING') AND finished_at IS NULL)
    ),
    CONSTRAINT import_batch_failure CHECK (failure_code IS NULL OR state = 'FAILED')
);

CREATE TABLE import_row (
    import_id UUID NOT NULL REFERENCES import_batch(id),
    ordinal BIGINT NOT NULL CHECK (ordinal > 0),
    email VARCHAR(255),
    row_state VARCHAR(16) NOT NULL
        CHECK (row_state IN ('ACCEPTED', 'DUPLICATE', 'REJECTED', 'CANCELLED')),
    duplicate_of BIGINT,
    reason VARCHAR(64),
    job_id UUID UNIQUE REFERENCES verification_job(id),
    PRIMARY KEY (import_id, ordinal),
    FOREIGN KEY (import_id, duplicate_of) REFERENCES import_row(import_id, ordinal),
    CONSTRAINT import_row_state_fields CHECK (
        (row_state = 'ACCEPTED' AND email IS NOT NULL AND duplicate_of IS NULL AND reason IS NULL)
        OR (row_state = 'DUPLICATE' AND email IS NULL AND duplicate_of IS NOT NULL
            AND reason = 'DUPLICATE' AND job_id IS NULL)
        OR (row_state = 'REJECTED' AND reason IS NOT NULL AND duplicate_of IS NULL AND job_id IS NULL)
        OR (row_state = 'CANCELLED' AND email IS NOT NULL AND duplicate_of IS NULL)
    )
);

CREATE UNIQUE INDEX idx_import_row_unique_address ON import_row(import_id, email)
    WHERE row_state = 'ACCEPTED';
CREATE INDEX idx_import_row_unpromoted ON import_row(import_id, ordinal)
    WHERE row_state = 'ACCEPTED' AND job_id IS NULL;
CREATE INDEX idx_import_batch_processing ON import_batch(created_at, id)
    WHERE state IN ('READY', 'PROCESSING');
CREATE INDEX idx_import_batch_ingesting_stale ON import_batch(updated_at, id)
    WHERE state = 'INGESTING';

CREATE INDEX idx_verification_job_bulk_created ON verification_job(created_at);
CREATE INDEX idx_verification_job_bulk_finished ON verification_job(finished_at)
    WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED');
CREATE INDEX idx_import_batch_created ON import_batch(created_at);
CREATE INDEX idx_import_batch_finished ON import_batch(finished_at)
    WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED');
