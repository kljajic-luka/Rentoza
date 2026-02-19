-- ============================================================================
-- V60: Add damage_claim_charge Column to checkout_saga_state
-- ============================================================================
-- Adds the damage_claim_charge field introduced in CheckoutSagaState entity.
-- This column stores the approved damage amount from checkout disputes,
-- included in the saga's total charge calculation.
-- ============================================================================

ALTER TABLE checkout_saga_state
    ADD COLUMN IF NOT EXISTS damage_claim_charge DECIMAL(10, 2);

-- ============================================================================
-- Verification
-- ============================================================================
-- SELECT column_name, data_type, numeric_precision, numeric_scale
-- FROM information_schema.columns
-- WHERE table_name = 'checkout_saga_state'
--   AND column_name = 'damage_claim_charge';
--
-- Expected:
--  - damage_claim_charge | numeric | 10 | 2
-- ============================================================================
