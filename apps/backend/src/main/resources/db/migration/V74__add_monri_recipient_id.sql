-- V10: Add Monri recipient ID for host payout disbursement
-- Used by MonriPaymentProvider.payout() to identify the host's onboarded
-- Monri account for marketplace disbursements. The previous code incorrectly
-- sent the internal user ID as recipient_id to the Monri payout API.

ALTER TABLE users
    ADD COLUMN monri_recipient_id VARCHAR(150);

-- Index for reverse-lookup by provider recipient reference
CREATE INDEX idx_users_monri_recipient_id
    ON users (monri_recipient_id)
    WHERE monri_recipient_id IS NOT NULL;
