-- V45__extend_damage_claim_for_checkin_disputes.sql
-- Purpose: Support pre-existing damage disputes at check-in (VAL-004)
-- Author: AI Code Agent
-- Date: 2026-01-31
-- Related Issue: VAL-004 - Guest Cannot Reject Pre-Existing Damage
--
-- Background:
--   - Currently guests MUST accept vehicle condition or abandon booking
--   - No formal way to report undisclosed pre-existing damage
--   - This migration enables check-in disputes alongside existing checkout disputes
--
-- Changes:
--   - Add dispute_stage column (CHECK_IN vs CHECKOUT)
--   - Add dispute_type column (PRE_EXISTING_DAMAGE, CHECKOUT_DAMAGE, etc.)
--   - Add disputed_photo_ids for check-in disputes
--   - Remove unique constraint to allow multiple claims per booking
--   - Add reportedBy field to track who initiated the claim
-- ============================================================================

-- ============================================================================
-- Step 1: Add dispute stage column
-- ============================================================================
-- Indicates when the dispute occurred in the booking lifecycle
ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS dispute_stage VARCHAR(20);

-- Backfill existing claims as CHECKOUT disputes
UPDATE damage_claims 
SET dispute_stage = 'CHECKOUT'
WHERE dispute_stage IS NULL;

-- Make column NOT NULL after backfill
ALTER TABLE damage_claims
ALTER COLUMN dispute_stage SET NOT NULL;

ALTER TABLE damage_claims
ALTER COLUMN dispute_stage SET DEFAULT 'CHECKOUT';

-- ============================================================================
-- Step 2: Add dispute type column
-- ============================================================================
-- Categorizes the type of damage/issue being disputed
ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS dispute_type VARCHAR(50);

-- Backfill existing claims as CHECKOUT_DAMAGE
UPDATE damage_claims 
SET dispute_type = 'CHECKOUT_DAMAGE'
WHERE dispute_type IS NULL;

-- Make column NOT NULL after backfill
ALTER TABLE damage_claims
ALTER COLUMN dispute_type SET NOT NULL;

ALTER TABLE damage_claims
ALTER COLUMN dispute_type SET DEFAULT 'CHECKOUT_DAMAGE';

-- ============================================================================
-- Step 3: Add disputed photo IDs array
-- ============================================================================
-- For check-in disputes: IDs of photos guest flagged as showing undisclosed damage
ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS disputed_photo_ids BIGINT[];

-- ============================================================================
-- Step 4: Add reported_by field
-- ============================================================================
-- Track who initiated the claim (guest for check-in, host for checkout)
ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS reported_by_user_id BIGINT REFERENCES users(id);

-- Backfill: For existing checkout claims, reporter is the host
UPDATE damage_claims 
SET reported_by_user_id = host_id
WHERE reported_by_user_id IS NULL;

-- ============================================================================
-- Step 5: Add resolution documentation field
-- ============================================================================
-- For PROCEED_WITH_DAMAGE_NOTED: List of documented pre-existing damage items
ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS documented_damage TEXT;

-- ============================================================================
-- Step 6: Add cancellation reason tracking
-- ============================================================================
-- For CANCEL_BOOKING resolution: Track why booking was cancelled
ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(100);

-- ============================================================================
-- Step 7: Remove unique constraint on booking_id
-- ============================================================================
-- Allow multiple claims per booking (check-in + checkout disputes possible)
-- First check if constraint exists before dropping
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'damage_claims_booking_id_key'
    ) THEN
        ALTER TABLE damage_claims DROP CONSTRAINT damage_claims_booking_id_key;
    END IF;
    
    IF EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'uk_damage_claim_booking'
    ) THEN
        ALTER TABLE damage_claims DROP CONSTRAINT uk_damage_claim_booking;
    END IF;
END $$;

-- ============================================================================
-- Step 8: Create indexes for new columns
-- ============================================================================

-- Index for filtering by dispute stage
CREATE INDEX IF NOT EXISTS idx_damage_claims_stage 
ON damage_claims(dispute_stage);

-- Composite index for admin dispute queries (stage + status)
CREATE INDEX IF NOT EXISTS idx_damage_claims_stage_status 
ON damage_claims(dispute_stage, status) 
WHERE status IN ('PENDING', 'DISPUTED', 'CHECK_IN_DISPUTE_PENDING');

-- Index for filtering by dispute type
CREATE INDEX IF NOT EXISTS idx_damage_claims_type 
ON damage_claims(dispute_type);

-- ============================================================================
-- Step 9: Documentation
-- ============================================================================

COMMENT ON COLUMN damage_claims.dispute_stage IS 
    'When dispute occurred: CHECK_IN (pre-existing damage) or CHECKOUT (damage during trip). (VAL-004)';

COMMENT ON COLUMN damage_claims.dispute_type IS 
    'Category of dispute: PRE_EXISTING_DAMAGE, CHECKOUT_DAMAGE, CLEANING_FEE, FUEL_SHORTAGE. (VAL-004)';

COMMENT ON COLUMN damage_claims.disputed_photo_ids IS 
    'Array of photo IDs guest flagged as showing undisclosed damage (check-in disputes only). (VAL-004)';

COMMENT ON COLUMN damage_claims.reported_by_user_id IS 
    'User who initiated the claim. Guest for check-in disputes, host for checkout claims. (VAL-004)';

COMMENT ON COLUMN damage_claims.documented_damage IS 
    'List of pre-existing damage items documented when admin resolves with PROCEED_WITH_DAMAGE_NOTED. (VAL-004)';

COMMENT ON COLUMN damage_claims.cancellation_reason IS 
    'Reason code when booking is cancelled due to dispute resolution. (VAL-004)';

-- ============================================================================
-- Step 10: Add escalation tracking fields (VAL-004 Phase 6)
-- ============================================================================
-- Track if dispute was escalated to senior admin due to timeout

ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS escalated BOOLEAN DEFAULT FALSE;

ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMPTZ;

ALTER TABLE damage_claims
ADD COLUMN IF NOT EXISTS resolution_notes TEXT;

COMMENT ON COLUMN damage_claims.escalated IS 
    'Whether dispute was escalated to senior admin due to timeout. (VAL-004 Phase 6)';

COMMENT ON COLUMN damage_claims.escalated_at IS 
    'Timestamp when dispute was escalated to senior admin. (VAL-004 Phase 6)';

COMMENT ON COLUMN damage_claims.resolution_notes IS 
    'Resolution notes from admin or auto-resolution system. (VAL-004 Phase 6)';

-- Index for finding escalated disputes
CREATE INDEX IF NOT EXISTS idx_damage_claims_escalated 
ON damage_claims(escalated) 
WHERE escalated = TRUE;

-- ============================================================================
-- Verification Queries
-- ============================================================================
--
-- 1. Verify new columns exist:
--    SELECT column_name, data_type, is_nullable, column_default
--    FROM information_schema.columns 
--    WHERE table_name = 'damage_claims' 
--    AND column_name IN ('dispute_stage', 'dispute_type', 'disputed_photo_ids', 
--                        'reported_by_user_id', 'documented_damage', 'cancellation_reason');
--
-- 2. Verify existing claims have stage/type set:
--    SELECT COUNT(*) FROM damage_claims WHERE dispute_stage IS NULL OR dispute_type IS NULL;
--    (Should return 0)
--
-- 3. Verify unique constraint removed:
--    SELECT conname FROM pg_constraint WHERE conrelid = 'damage_claims'::regclass;
--    (Should NOT include booking_id unique constraint)
--
-- 4. Verify indexes created:
--    SELECT indexname FROM pg_indexes WHERE tablename = 'damage_claims';
-- ============================================================================
