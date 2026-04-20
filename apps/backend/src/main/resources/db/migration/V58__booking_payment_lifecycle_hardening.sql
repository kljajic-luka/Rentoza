-- ============================================================================
-- V58: Booking Payment Lifecycle Hardening
-- ============================================================================
-- Second follow-up audit fixes for Feature 5 (Booking Creation).
--
-- Changes:
-- 1. Add booking_authorization_id column (P0: track booking payment hold)
-- 2. Add deposit_authorization_id column (P0: track deposit hold)
-- 3. Add service_fee_snapshot column (P2: prevent price drift)
-- 4. Add insurance_cost_snapshot column (P2: prevent price drift)
-- 5. Add idempotency_key column with unique constraint (P1: prevent duplicates)
-- 6. Improve trigger functions with advisory locks (P0: empty-slot race fix)
-- ============================================================================

-- ============================================================================
-- 1. NEW COLUMNS ON BOOKINGS TABLE
-- ============================================================================

-- P0: Authorization ID for the booking payment hold
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS booking_authorization_id VARCHAR(100);

-- P0: Authorization ID for the security deposit hold
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS deposit_authorization_id VARCHAR(100);

-- P2: Service fee snapshot at booking creation (prevents price drift)
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS service_fee_snapshot DECIMAL(19, 2);

-- P2: Insurance cost snapshot at booking creation (prevents price drift)
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS insurance_cost_snapshot DECIMAL(19, 2);

-- P1: Idempotency key for duplicate booking prevention
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

-- Unique constraint on idempotency key (allows NULLs — only existing keys must be unique)
CREATE UNIQUE INDEX IF NOT EXISTS idx_booking_idempotency_key
    ON bookings (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ============================================================================
-- 2. IMPROVED TRIGGER FUNCTIONS WITH ADVISORY LOCKS
-- ============================================================================
-- P0 FIX: The original V57 triggers use SELECT COUNT(*) which is a snapshot
-- read under PostgreSQL's MVCC. Two concurrent transactions can both see
-- count=0 and both proceed (empty-slot race condition).
--
-- Fix: Use pg_advisory_xact_lock() to serialize access per renter/car.
-- The advisory lock is transaction-scoped and auto-releases on commit/rollback.
-- This ensures only one INSERT can check-and-insert at a time for the same entity.
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_renter_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
    -- Serialize all booking INSERTs for this renter.
    -- Uses a different namespace (hashtext) to avoid collision with car locks.
    PERFORM pg_advisory_xact_lock(hashtext('renter_booking_' || NEW.renter_id::text));

    SELECT COUNT(*) INTO overlap_count
    FROM bookings
    WHERE renter_id = NEW.renter_id
      AND id != COALESCE(NEW.id, 0)
      AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                     'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;

    IF overlap_count > 0 THEN
        RAISE EXCEPTION 'USER_OVERLAP: User already has an active booking for this time period'
            USING ERRCODE = '23505'; -- unique_violation
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_car_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
    -- Serialize all booking INSERTs for this car.
    -- This prevents the empty-slot race where two transactions both see count=0.
    PERFORM pg_advisory_xact_lock(NEW.car_id);

    SELECT COUNT(*) INTO overlap_count
    FROM bookings
    WHERE car_id = NEW.car_id
      AND id != COALESCE(NEW.id, 0)
      AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                     'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;

    IF overlap_count > 0 THEN
        RAISE EXCEPTION 'CAR_UNAVAILABLE: Car is already booked for the requested time period'
            USING ERRCODE = '23505'; -- unique_violation
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================
-- Verification:
--   SELECT column_name FROM information_schema.columns 
--   WHERE table_name = 'bookings' 
--   AND column_name IN ('booking_authorization_id', 'deposit_authorization_id',
--                       'service_fee_snapshot', 'insurance_cost_snapshot', 'idempotency_key');
--   Expected: 5 rows
-- ============================================================================
