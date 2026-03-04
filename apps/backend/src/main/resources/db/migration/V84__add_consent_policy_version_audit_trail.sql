-- V84: Add consent policy version and hash columns for audit trail.
-- Phase 2 registration security hardening: tracks which terms version
-- the user consented to at registration or profile completion time.
--
-- These fields, along with existing consent provenance columns (consent_ip,
-- consent_user_agent, host_agreement_accepted_at, etc.), become immutable
-- once set via a database trigger to prevent post-fact tampering.

-- Add new consent audit columns
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS consent_policy_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS consent_policy_hash VARCHAR(64);

-- Immutability trigger: prevent updating consent fields after initial set.
-- Consent provenance must be an append-only audit record — once a user has
-- agreed to terms, the evidence of that agreement cannot be retroactively changed.
CREATE OR REPLACE FUNCTION prevent_consent_field_mutation()
RETURNS TRIGGER AS $$
BEGIN
    -- Only block mutations when OLD value was already set (non-null).
    -- Setting a NULL field for the first time (via UPDATE) is allowed.

    IF OLD.consent_policy_version IS NOT NULL
       AND NEW.consent_policy_version IS DISTINCT FROM OLD.consent_policy_version THEN
        RAISE EXCEPTION 'consent_policy_version is immutable once set';
    END IF;

    IF OLD.consent_policy_hash IS NOT NULL
       AND NEW.consent_policy_hash IS DISTINCT FROM OLD.consent_policy_hash THEN
        RAISE EXCEPTION 'consent_policy_hash is immutable once set';
    END IF;

    IF OLD.consent_ip IS NOT NULL
       AND NEW.consent_ip IS DISTINCT FROM OLD.consent_ip THEN
        RAISE EXCEPTION 'consent_ip is immutable once set';
    END IF;

    IF OLD.consent_user_agent IS NOT NULL
       AND NEW.consent_user_agent IS DISTINCT FROM OLD.consent_user_agent THEN
        RAISE EXCEPTION 'consent_user_agent is immutable once set';
    END IF;

    IF OLD.host_agreement_accepted_at IS NOT NULL
       AND NEW.host_agreement_accepted_at IS DISTINCT FROM OLD.host_agreement_accepted_at THEN
        RAISE EXCEPTION 'host_agreement_accepted_at is immutable once set';
    END IF;

    IF OLD.vehicle_insurance_confirmed_at IS NOT NULL
       AND NEW.vehicle_insurance_confirmed_at IS DISTINCT FROM OLD.vehicle_insurance_confirmed_at THEN
        RAISE EXCEPTION 'vehicle_insurance_confirmed_at is immutable once set';
    END IF;

    IF OLD.vehicle_registration_confirmed_at IS NOT NULL
       AND NEW.vehicle_registration_confirmed_at IS DISTINCT FROM OLD.vehicle_registration_confirmed_at THEN
        RAISE EXCEPTION 'vehicle_registration_confirmed_at is immutable once set';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop existing trigger if present (idempotent re-run safety)
DROP TRIGGER IF EXISTS trg_immutable_consent_fields ON users;

CREATE TRIGGER trg_immutable_consent_fields
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION prevent_consent_field_mutation();
