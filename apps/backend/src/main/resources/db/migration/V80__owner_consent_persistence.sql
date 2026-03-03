-- V80: Persist owner consent/agreement acceptance with provenance metadata.
-- Phase 4 of Serbia compliance remediation.
--
-- Currently, owner agreements (host agreement, vehicle insurance confirmation,
-- vehicle registration confirmation) are validated at registration time but not
-- persisted. This migration adds timestamp + IP/UA provenance columns.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS host_agreement_accepted_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS vehicle_insurance_confirmed_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS vehicle_registration_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS consent_ip         VARCHAR(45),
    ADD COLUMN IF NOT EXISTS consent_user_agent VARCHAR(500);
