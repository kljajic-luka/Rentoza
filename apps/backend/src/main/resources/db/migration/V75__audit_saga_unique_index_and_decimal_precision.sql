-- V75: Audit remediation — C-3 and H-8 fixes for checkout_saga_state
--
-- C-3 (CRITICAL): Prevent concurrent sagas on same booking.
-- Partial unique index ensures at most one RUNNING or SUSPENDED saga per booking.
--
-- H-8 (HIGH): Widen DECIMAL precision on financial columns.
-- DECIMAL(10,2) overflows at ~80M RSD. DECIMAL(19,2) supports up to ~10 quadrillion.

-- ========== C-3: Partial unique index ==========
CREATE UNIQUE INDEX IF NOT EXISTS idx_saga_booking_active
    ON checkout_saga_state (booking_id)
    WHERE status IN ('RUNNING', 'SUSPENDED');

-- ========== H-8: Widen financial column precision ==========
ALTER TABLE checkout_saga_state ALTER COLUMN extra_mileage_charge TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN fuel_charge TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN late_fee TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN damage_claim_charge TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN total_charges TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN captured_amount TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN released_amount TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN remainder_amount TYPE DECIMAL(19, 2);
