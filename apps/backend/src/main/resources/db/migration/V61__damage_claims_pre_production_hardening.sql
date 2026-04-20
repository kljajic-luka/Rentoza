-- ============================================================================
-- V61: Damage Claims Pre-Production Hardening
-- ============================================================================
-- Addresses Turo-standard audit findings for Feature 9: Damage Claims & Disputes.
--
-- Changes:
-- 1. Add image_hash column for duplicate photo detection (fraudulent photo reuse)
-- 2. Add repair_quote_document_url for mechanic quote artifact upload
-- 3. Add admin_review_threshold_flagged for >500 EUR mandatory review
-- 4. Add composite unique constraint (booking_id + dispute_stage + initiator) 
--    to prevent duplicate claims per booking/stage/party
-- 5. Add checkout_photo_ids to damage_claims for photo persistence
-- ============================================================================

-- 1. Image hash column for duplicate detection on host_checkout_photos
ALTER TABLE host_checkout_photos
    ADD COLUMN IF NOT EXISTS image_hash VARCHAR(128);

-- Index for fast hash lookups (duplicate detection)
CREATE INDEX IF NOT EXISTS idx_host_checkout_photo_hash
    ON host_checkout_photos(image_hash)
    WHERE image_hash IS NOT NULL;

-- 2. Image hash on check_in_photos as well (for cross-stage duplicate detection)
ALTER TABLE check_in_photos
    ADD COLUMN IF NOT EXISTS image_hash VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_check_in_photo_hash
    ON check_in_photos(image_hash)
    WHERE image_hash IS NOT NULL;

-- 3. Repair quote document URL on damage_claims
ALTER TABLE damage_claims
    ADD COLUMN IF NOT EXISTS repair_quote_document_url TEXT;

-- 4. Admin review threshold flag
ALTER TABLE damage_claims
    ADD COLUMN IF NOT EXISTS admin_review_required BOOLEAN DEFAULT FALSE;

-- 5. Composite unique index: prevent duplicate active claims per booking + stage + initiator
-- Only enforced for non-resolved statuses (PENDING, CHECKOUT_PENDING, etc.)
CREATE UNIQUE INDEX IF NOT EXISTS idx_damage_claim_booking_stage_initiator_active
    ON damage_claims(booking_id, dispute_stage, initiator)
    WHERE status IN (
        'PENDING', 'DISPUTED', 'ESCALATED', 'ACCEPTED_BY_GUEST', 'AUTO_APPROVED',
        'CHECKOUT_PENDING', 'CHECKOUT_GUEST_ACCEPTED', 'CHECKOUT_GUEST_DISPUTED',
        'CHECKOUT_TIMEOUT_ESCALATED', 'CHECK_IN_DISPUTE_PENDING'
    );

-- ============================================================================
-- Verification
-- ============================================================================
-- SELECT column_name, data_type
-- FROM information_schema.columns
-- WHERE table_name = 'damage_claims'
--   AND column_name IN ('repair_quote_document_url', 'admin_review_required');
--
-- SELECT column_name, data_type
-- FROM information_schema.columns
-- WHERE table_name = 'host_checkout_photos'
--   AND column_name = 'image_hash';
--
-- SELECT indexname FROM pg_indexes
-- WHERE tablename = 'damage_claims'
--   AND indexname = 'idx_damage_claim_booking_stage_initiator_active';
