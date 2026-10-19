ALTER TABLE verification_job
    ADD COLUMN result_historical_evidence JSONB,
    ADD CONSTRAINT verification_job_historical_evidence_state
        CHECK (result_historical_evidence IS NULL OR state = 'COMPLETED');

-- This row is the per-address mutex for all mutations. Reads use one READ COMMITTED statement.
CREATE TABLE delivery_identity (
    email_key VARCHAR(255) PRIMARY KEY
);

CREATE TABLE delivery_event (
    id UUID PRIMARY KEY,
    email_key VARCHAR(255) NOT NULL REFERENCES delivery_identity(email_key),
    email VARCHAR(255) NOT NULL,
    event_type VARCHAR(16) NOT NULL
        CHECK (event_type IN ('HARD_BOUNCE', 'SOFT_BOUNCE', 'DELIVERED', 'COMPLAINT', 'UNSUBSCRIBED')),
    source_type VARCHAR(16) NOT NULL DEFAULT 'ADMIN_JSON' CHECK (source_type = 'ADMIN_JSON'),
    source_identifier VARCHAR(128) NOT NULL CHECK (source_identifier ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    external_event_id VARCHAR(255) NOT NULL CHECK (external_event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    enhanced_status_code VARCHAR(32)
        CHECK (enhanced_status_code ~ '^[245]\.[0-9]{1,3}\.[0-9]{1,3}$'),
    status_fingerprint BYTEA CHECK (status_fingerprint IS NULL OR octet_length(status_fingerprint) = 32),
    CONSTRAINT delivery_event_status_fingerprint CHECK (
        enhanced_status_code IS NULL OR status_fingerprint IS NOT NULL
    ),
    interpretation VARCHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(64),
    CONSTRAINT delivery_event_source_unique UNIQUE (source_type, source_identifier, external_event_id),
    CONSTRAINT delivery_event_id_identity_type_unique UNIQUE (id, email_key, event_type),
    CONSTRAINT delivery_event_revocation_audit CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CONSTRAINT delivery_event_occurrence_window CHECK (
        occurred_at >= TIMESTAMPTZ '2000-01-01 00:00:00+00'
        AND occurred_at <= observed_at + interval '5 minutes'
    ),
    CONSTRAINT delivery_event_revocation_time CHECK (revoked_at IS NULL OR revoked_at >= observed_at),
    CONSTRAINT delivery_event_reason CHECK (revocation_reason IS NULL OR revocation_reason ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE INDEX idx_delivery_event_latest ON delivery_event (email_key, occurred_at DESC, observed_at DESC, id DESC)
    WHERE revoked_at IS NULL;

CREATE TABLE delivery_suppression (
    id UUID PRIMARY KEY,
    email_key VARCHAR(255) NOT NULL REFERENCES delivery_identity(email_key),
    reason VARCHAR(64) NOT NULL CHECK (reason IN ('MANUAL', 'HARD_BOUNCE', 'COMPLAINT', 'UNSUBSCRIBED')),
    manual_reason VARCHAR(64) CHECK (manual_reason IS NULL OR manual_reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    effective_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ,
    lifted_at TIMESTAMPTZ,
    lifting_reason VARCHAR(64),
    source_event_id UUID UNIQUE,
    CONSTRAINT delivery_suppression_event_identity
        FOREIGN KEY (source_event_id, email_key, reason) REFERENCES delivery_event (id, email_key, event_type),
    CONSTRAINT delivery_suppression_provenance CHECK (
        (source_event_id IS NULL AND reason = 'MANUAL' AND manual_reason IS NOT NULL)
        OR (source_event_id IS NOT NULL AND reason <> 'MANUAL' AND manual_reason IS NULL)
    ),
    CONSTRAINT delivery_suppression_expiry CHECK (expires_at IS NULL OR expires_at > effective_at),
    CONSTRAINT delivery_suppression_lift_audit CHECK ((lifted_at IS NULL) = (lifting_reason IS NULL)),
    CONSTRAINT delivery_suppression_lift_time CHECK (lifted_at IS NULL OR lifted_at >= effective_at),
    CONSTRAINT delivery_suppression_lift_reason CHECK (lifting_reason IS NULL OR lifting_reason ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE INDEX idx_delivery_suppression_active ON delivery_suppression (email_key, effective_at DESC, id DESC)
    WHERE lifted_at IS NULL;

CREATE FUNCTION protect_delivery_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.email_key, NEW.email, NEW.event_type, NEW.source_type,
           NEW.source_identifier, NEW.external_event_id, NEW.occurred_at,
           NEW.enhanced_status_code, NEW.status_fingerprint, NEW.interpretation, NEW.observed_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.email_key, OLD.email, OLD.event_type, OLD.source_type,
           OLD.source_identifier, OLD.external_event_id, OLD.occurred_at,
           OLD.enhanced_status_code, OLD.status_fingerprint, OLD.interpretation, OLD.observed_at)
       OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL OR NEW.revocation_reason IS NULL THEN
        RAISE EXCEPTION 'delivery event is immutable except first audited revocation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER delivery_event_audit BEFORE UPDATE ON delivery_event
    FOR EACH ROW EXECUTE FUNCTION protect_delivery_event();

CREATE FUNCTION protect_delivery_suppression() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.email_key, NEW.reason, NEW.manual_reason, NEW.effective_at, NEW.expires_at, NEW.source_event_id)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.email_key, OLD.reason, OLD.manual_reason, OLD.effective_at, OLD.expires_at, OLD.source_event_id)
       OR OLD.lifted_at IS NOT NULL OR NEW.lifted_at IS NULL OR NEW.lifting_reason IS NULL THEN
        RAISE EXCEPTION 'delivery suppression is immutable except first audited lift';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER delivery_suppression_audit BEFORE UPDATE ON delivery_suppression
    FOR EACH ROW EXECUTE FUNCTION protect_delivery_suppression();

CREATE FUNCTION prevent_delivery_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'delivery audit history cannot be deleted';
END;
$$;
CREATE TRIGGER delivery_event_no_delete BEFORE DELETE ON delivery_event
    FOR EACH ROW EXECUTE FUNCTION prevent_delivery_delete();
CREATE TRIGGER delivery_suppression_no_delete BEFORE DELETE ON delivery_suppression
    FOR EACH ROW EXECUTE FUNCTION prevent_delivery_delete();