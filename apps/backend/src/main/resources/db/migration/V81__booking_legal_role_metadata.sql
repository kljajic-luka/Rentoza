-- V81: Add legal-role metadata to bookings table.
-- Phase 6 of Serbia compliance remediation.
--
-- Records the platform's contractual role and terms version at booking creation.
-- This supports regulatory audit trails establishing Rentoza as
-- marketplace-intermediary (not rental party).

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS platform_role      VARCHAR(30)  DEFAULT 'INTERMEDIARY',
    ADD COLUMN IF NOT EXISTS contract_type      VARCHAR(30)  DEFAULT 'OWNER_RENTER_DIRECT',
    ADD COLUMN IF NOT EXISTS terms_version      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS terms_content_hash VARCHAR(128);
