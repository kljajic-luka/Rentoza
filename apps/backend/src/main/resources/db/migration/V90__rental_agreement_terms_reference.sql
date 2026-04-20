-- ============================================================================
-- V90: Add terms template reference to rental agreements
-- ============================================================================
-- Adds fields to reference the actual legal terms document version that
-- parties accepted. The terms content itself is managed externally (counsel).
-- This creates the structural link between acceptance evidence and terms text.
-- ============================================================================

ALTER TABLE rental_agreements
    ADD COLUMN IF NOT EXISTS terms_template_id VARCHAR(50) NULL,
    ADD COLUMN IF NOT EXISTS terms_template_hash VARCHAR(128) NULL;

COMMENT ON COLUMN rental_agreements.terms_template_id IS 'Identifier of the legal terms template version accepted by parties';
COMMENT ON COLUMN rental_agreements.terms_template_hash IS 'SHA-256 hash of the terms template content at acceptance time';

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
    -- Terms template reference: immutable once set
    IF OLD.terms_template_id IS NOT NULL AND OLD.terms_template_id IS DISTINCT FROM NEW.terms_template_id THEN
        RAISE EXCEPTION 'rental_agreements.terms_template_id is immutable once set';
    END IF;
    IF OLD.terms_template_hash IS NOT NULL AND OLD.terms_template_hash IS DISTINCT FROM NEW.terms_template_hash THEN
        RAISE EXCEPTION 'rental_agreements.terms_template_hash is immutable once set';
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