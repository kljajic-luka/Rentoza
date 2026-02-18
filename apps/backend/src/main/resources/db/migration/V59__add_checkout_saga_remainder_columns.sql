-- ============================================================================
-- V59: Add Checkout Saga Remainder Columns
-- ============================================================================
-- Adds persistence fields introduced in CheckoutSagaState entity:
-- 1) remainder_amount          DECIMAL(10,2)
-- 2) remainder_transaction_id  VARCHAR(100)
--
-- This fixes runtime failures where saga/payment code reads/writes these fields
-- but the checkout_saga_state table is missing the columns.
-- ============================================================================

ALTER TABLE checkout_saga_state
    ADD COLUMN IF NOT EXISTS remainder_amount DECIMAL(10, 2);

ALTER TABLE checkout_saga_state
    ADD COLUMN IF NOT EXISTS remainder_transaction_id VARCHAR(100);

-- ============================================================================
-- Verification
-- ============================================================================
-- SELECT column_name, data_type, numeric_precision, numeric_scale
-- FROM information_schema.columns
-- WHERE table_name = 'checkout_saga_state'
--   AND column_name IN ('remainder_amount', 'remainder_transaction_id')
-- ORDER BY column_name;
--
-- Expected:
--  - remainder_amount           | numeric | 10 | 2
--  - remainder_transaction_id   | character varying
-- ============================================================================
