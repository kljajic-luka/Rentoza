-- =====================================================================
-- V31: Owner Verification Submitted Timestamp
-- =====================================================================
-- Purpose:
-- - Track when an owner submitted identity verification for admin queue ordering
-- - Enables sorting newest-first without inferring from other timestamps
--
-- Security:
-- - This column does NOT store PII (only a timestamp)
-- =====================================================================

ALTER TABLE users
    ADD COLUMN owner_verification_submitted_at TIMESTAMP NULL AFTER bank_account_number_encrypted;

-- Backfill for existing pending submissions (best-effort ordering)
UPDATE users
SET owner_verification_submitted_at = COALESCE(updated_at, created_at)
WHERE owner_verification_submitted_at IS NULL
    AND is_identity_verified = FALSE
    AND (jmbg_encrypted IS NOT NULL OR pib_encrypted IS NOT NULL);

CREATE INDEX idx_users_owner_verification_submitted_at
    ON users (owner_verification_submitted_at);
