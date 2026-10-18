CREATE TABLE verification_job (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) UNIQUE,
    request_fingerprint BYTEA NOT NULL CHECK (octet_length(request_fingerprint) = 32),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'RUNNING', 'RETRY', 'COMPLETED', 'FAILED', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0 AND attempts <= max_attempts),
    due_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(255),
    lease_token BIGINT NOT NULL DEFAULT 0 CHECK (lease_token >= 0),
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    result_email VARCHAR(255),
    result_domain VARCHAR(255),
    result_format_valid BOOLEAN,
    result_mx_record_found BOOLEAN,
    result_smtp_verified BOOLEAN,
    result_status VARCHAR(255),
    result_message VARCHAR(255),
    failure_code VARCHAR(64),
    CONSTRAINT verification_job_lease_state CHECK (
        (state = 'RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND heartbeat_at IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_owner IS NULL AND lease_expires_at IS NULL AND heartbeat_at IS NULL)
    ),
    CONSTRAINT verification_job_terminal_state CHECK (
        (state IN ('COMPLETED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
        OR (state IN ('PENDING', 'RUNNING', 'RETRY') AND finished_at IS NULL)
    ),
    CONSTRAINT verification_job_result_state CHECK (
        (state = 'COMPLETED' AND result_email IS NOT NULL AND result_email = email
            AND result_status IS NOT NULL AND failure_code IS NULL)
        OR (state <> 'COMPLETED' AND result_email IS NULL AND result_domain IS NULL
            AND result_format_valid IS NULL AND result_mx_record_found IS NULL
            AND result_smtp_verified IS NULL AND result_status IS NULL AND result_message IS NULL)
    ),
    CONSTRAINT verification_job_failure_state CHECK (failure_code IS NULL OR state IN ('RETRY', 'FAILED'))
);

CREATE INDEX idx_verification_job_due
    ON verification_job (due_at, created_at, id)
    WHERE state IN ('PENDING', 'RETRY');
CREATE INDEX idx_verification_job_lease
    ON verification_job (lease_expires_at, id)
    WHERE state = 'RUNNING';

CREATE FUNCTION prevent_terminal_verification_job_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal verification job cannot be updated';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER verification_job_terminal_immutable
BEFORE UPDATE ON verification_job
FOR EACH ROW EXECUTE FUNCTION prevent_terminal_verification_job_update();

ALTER TABLE email_validation_history
    ADD COLUMN job_id UUID UNIQUE REFERENCES verification_job(id);
