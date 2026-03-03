-- ============================================================================
-- V78: Rental Agreement Infrastructure
-- ============================================================================
-- Creates the rental_agreements table for compliance with Serbian marketplace
-- intermediary model. Each booking gets exactly one agreement that both parties
-- (owner + renter) must accept before the trip can start.
--
-- Immutability: Core evidence fields are protected by database triggers.
-- Delete is blocked entirely (audit trail preservation).
-- ============================================================================

-- 1. Create rental_agreements table
CREATE TABLE IF NOT EXISTS rental_agreements (
    id                   BIGSERIAL PRIMARY KEY,
    booking_id           BIGINT         NOT NULL UNIQUE,
    agreement_version    VARCHAR(20)    NOT NULL,
    agreement_type       VARCHAR(30)    NOT NULL DEFAULT 'STANDARD_RENTAL',
    content_hash         VARCHAR(128)   NOT NULL,
    generated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- Owner acceptance evidence
    owner_accepted_at    TIMESTAMPTZ    NULL,
    owner_ip             VARCHAR(45)    NULL,
    owner_user_agent     VARCHAR(500)   NULL,

    -- Renter acceptance evidence
    renter_accepted_at   TIMESTAMPTZ    NULL,
    renter_ip            VARCHAR(45)    NULL,
    renter_user_agent    VARCHAR(500)   NULL,

    -- Party references
    owner_user_id        BIGINT         NOT NULL,
    renter_user_id       BIGINT         NOT NULL,

    -- Immutable snapshots (JSONB for structured querying)
    vehicle_snapshot_json JSONB         NOT NULL,
    terms_snapshot_json   JSONB         NOT NULL,

    -- Status tracking
    status               VARCHAR(20)    NOT NULL DEFAULT 'PENDING',

    -- Timestamps
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- Foreign keys
    CONSTRAINT fk_ra_booking    FOREIGN KEY (booking_id)      REFERENCES bookings(id),
    CONSTRAINT fk_ra_owner      FOREIGN KEY (owner_user_id)   REFERENCES users(id),
    CONSTRAINT fk_ra_renter     FOREIGN KEY (renter_user_id)  REFERENCES users(id),

    -- Status constraint
    CONSTRAINT chk_ra_status CHECK (status IN ('PENDING', 'OWNER_ACCEPTED', 'RENTER_ACCEPTED', 'FULLY_ACCEPTED', 'EXPIRED', 'VOIDED'))
);

-- 2. Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_ra_owner_user_id  ON rental_agreements (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_ra_renter_user_id ON rental_agreements (renter_user_id);
CREATE INDEX IF NOT EXISTS idx_ra_status         ON rental_agreements (status);

-- 3. Immutability trigger: block UPDATE on evidence/identity columns
CREATE OR REPLACE FUNCTION prevent_rental_agreement_immutable_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.content_hash IS DISTINCT FROM NEW.content_hash THEN
        RAISE EXCEPTION 'rental_agreements.content_hash is immutable after creation';
    END IF;
    IF OLD.agreement_version IS DISTINCT FROM NEW.agreement_version THEN
        RAISE EXCEPTION 'rental_agreements.agreement_version is immutable after creation';
    END IF;
    IF OLD.vehicle_snapshot_json IS DISTINCT FROM NEW.vehicle_snapshot_json THEN
        RAISE EXCEPTION 'rental_agreements.vehicle_snapshot_json is immutable after creation';
    END IF;
    IF OLD.terms_snapshot_json IS DISTINCT FROM NEW.terms_snapshot_json THEN
        RAISE EXCEPTION 'rental_agreements.terms_snapshot_json is immutable after creation';
    END IF;
    IF OLD.generated_at IS DISTINCT FROM NEW.generated_at THEN
        RAISE EXCEPTION 'rental_agreements.generated_at is immutable after creation';
    END IF;
    IF OLD.booking_id IS DISTINCT FROM NEW.booking_id THEN
        RAISE EXCEPTION 'rental_agreements.booking_id is immutable after creation';
    END IF;
    IF OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id THEN
        RAISE EXCEPTION 'rental_agreements.owner_user_id is immutable after creation';
    END IF;
    IF OLD.renter_user_id IS DISTINCT FROM NEW.renter_user_id THEN
        RAISE EXCEPTION 'rental_agreements.renter_user_id is immutable after creation';
    END IF;
    -- Acceptance evidence: once set (non-NULL), cannot be changed
    IF OLD.owner_accepted_at IS NOT NULL AND OLD.owner_accepted_at IS DISTINCT FROM NEW.owner_accepted_at THEN
        RAISE EXCEPTION 'rental_agreements.owner_accepted_at is immutable once set';
    END IF;
    IF OLD.owner_ip IS NOT NULL AND OLD.owner_ip IS DISTINCT FROM NEW.owner_ip THEN
        RAISE EXCEPTION 'rental_agreements.owner_ip is immutable once set';
    END IF;
    IF OLD.owner_user_agent IS NOT NULL AND OLD.owner_user_agent IS DISTINCT FROM NEW.owner_user_agent THEN
        RAISE EXCEPTION 'rental_agreements.owner_user_agent is immutable once set';
    END IF;
    IF OLD.renter_accepted_at IS NOT NULL AND OLD.renter_accepted_at IS DISTINCT FROM NEW.renter_accepted_at THEN
        RAISE EXCEPTION 'rental_agreements.renter_accepted_at is immutable once set';
    END IF;
    IF OLD.renter_ip IS NOT NULL AND OLD.renter_ip IS DISTINCT FROM NEW.renter_ip THEN
        RAISE EXCEPTION 'rental_agreements.renter_ip is immutable once set';
    END IF;
    IF OLD.renter_user_agent IS NOT NULL AND OLD.renter_user_agent IS DISTINCT FROM NEW.renter_user_agent THEN
        RAISE EXCEPTION 'rental_agreements.renter_user_agent is immutable once set';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rental_agreement_immutable_fields
    BEFORE UPDATE ON rental_agreements
    FOR EACH ROW EXECUTE FUNCTION prevent_rental_agreement_immutable_update();

-- 4. Block DELETE entirely (audit trail preservation)
CREATE OR REPLACE FUNCTION prevent_rental_agreement_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'rental_agreements rows cannot be deleted (audit trail preservation)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rental_agreement_no_delete
    BEFORE DELETE ON rental_agreements
    FOR EACH ROW EXECUTE FUNCTION prevent_rental_agreement_delete();
