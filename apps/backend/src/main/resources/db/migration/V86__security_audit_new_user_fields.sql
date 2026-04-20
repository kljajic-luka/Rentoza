-- =============================================================================
-- V86: Security audit - new user entity fields
-- SECURITY: Adds columns for identity rejection tracking and DOB correction workflow
-- =============================================================================

-- Identity rejection tracking (from security audit H-series findings)
ALTER TABLE users ADD COLUMN IF NOT EXISTS identity_rejection_reason VARCHAR(500);
ALTER TABLE users ADD COLUMN IF NOT EXISTS identity_rejected_at TIMESTAMP;

-- DOB correction workflow (M-9: admin review process)
ALTER TABLE users ADD COLUMN IF NOT EXISTS dob_correction_requested_value DATE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS dob_correction_requested_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS dob_correction_reason VARCHAR(500);
ALTER TABLE users ADD COLUMN IF NOT EXISTS dob_correction_status VARCHAR(20);

-- Index for admin dashboard: quickly find pending DOB correction requests
CREATE INDEX IF NOT EXISTS idx_users_dob_correction_status
    ON users (dob_correction_status)
    WHERE dob_correction_status IS NOT NULL;
