-- ============================================================================
-- VAL-010: Damage Claim Blocks Deposit Release
-- ============================================================================
-- This migration adds fields to support holding the security deposit when 
-- the host reports damage at checkout. The deposit is held until:
-- 1. Admin resolves the dispute
-- 2. Guest accepts the damage claim
-- 3. 7-day timeout triggers auto-escalation
-- ============================================================================

-- Step 1: Add deposit hold tracking columns to bookings table
ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS security_deposit_hold_reason VARCHAR(50),
    ADD COLUMN IF NOT EXISTS security_deposit_hold_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS security_deposit_released BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS security_deposit_resolved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS checkout_damage_claim_id BIGINT;

-- Step 2: Add foreign key constraint to damage_claims table
ALTER TABLE bookings
    ADD CONSTRAINT fk_booking_checkout_damage_claim
    FOREIGN KEY (checkout_damage_claim_id)
    REFERENCES damage_claims(id)
    ON DELETE SET NULL;

-- Step 3: Add index for efficient queries on held deposits
CREATE INDEX IF NOT EXISTS idx_bookings_deposit_hold 
    ON bookings(security_deposit_hold_until, security_deposit_released)
    WHERE security_deposit_hold_reason IS NOT NULL;

-- Step 4: Add index for checkout damage disputes status lookup
CREATE INDEX IF NOT EXISTS idx_bookings_checkout_damage_dispute
    ON bookings(status, security_deposit_hold_until)
    WHERE status = 'CHECKOUT_DAMAGE_DISPUTE';

-- Step 5: Add dispute_reason column to damage_claims for guest dispute reason
ALTER TABLE damage_claims
    ADD COLUMN IF NOT EXISTS dispute_reason TEXT;

COMMENT ON COLUMN damage_claims.dispute_reason IS 'Reason given by guest when disputing checkout damage claim (VAL-010)';

-- Step 6: Add comment for clarity
COMMENT ON COLUMN bookings.security_deposit_hold_reason IS 'Reason deposit is held: DAMAGE_CLAIM, DISPUTE, LATE_RETURN_EXCESSIVE';
COMMENT ON COLUMN bookings.security_deposit_hold_until IS 'Deadline for dispute resolution (default: 7 days from damage report)';
COMMENT ON COLUMN bookings.security_deposit_released IS 'Whether deposit has been released or captured';
COMMENT ON COLUMN bookings.security_deposit_resolved_at IS 'When deposit hold was resolved';
COMMENT ON COLUMN bookings.checkout_damage_claim_id IS 'Reference to DamageClaim for checkout damage dispute (VAL-010)';

-- ============================================================================
-- End of V46 Migration
-- ============================================================================
