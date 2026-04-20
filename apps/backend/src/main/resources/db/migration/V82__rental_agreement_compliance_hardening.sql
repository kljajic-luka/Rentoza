-- ============================================================================
-- V82: Rental Agreement Compliance Hardening
-- ============================================================================
-- Delta migration for changes that were applied to V78 after it was already
-- run against the database.
--
-- 1. Adds RENTER_ACCEPTED to the status CHECK constraint (C2 fix)
-- 2. Adds acceptance evidence immutability checks to the trigger (C3 fix)
-- ============================================================================

-- 1. Update CHECK constraint to allow RENTER_ACCEPTED status
ALTER TABLE rental_agreements DROP CONSTRAINT IF EXISTS chk_ra_status;
ALTER TABLE rental_agreements ADD CONSTRAINT chk_ra_status
    CHECK (status IN ('PENDING', 'OWNER_ACCEPTED', 'RENTER_ACCEPTED', 'FULLY_ACCEPTED', 'EXPIRED', 'VOIDED'));

-- 2. Replace immutability trigger to add acceptance evidence protection
--    Once acceptance evidence fields are set (non-NULL → value), they cannot
--    be changed. This allows the initial write (NULL → value) but blocks
--    subsequent modifications.
CREATE OR REPLACE FUNCTION prevent_rental_agreement_immutable_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Core identity fields: always immutable
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
