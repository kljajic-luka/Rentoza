-- ============================================================================
-- V57: PostgreSQL-Compatible Booking Overlap Prevention
-- ============================================================================
-- Replaces MySQL-specific triggers from V10 and V18 with PostgreSQL functions.
-- 
-- V10 and V18 used MySQL syntax (DELIMITER, SIGNAL SQLSTATE, DATETIME)
-- which is incompatible with PostgreSQL (the actual database in use).
--
-- This migration creates:
-- 1. A trigger function to prevent overlapping renter bookings
-- 2. A trigger function to prevent overlapping car bookings (double booking)
-- 3. Indexes to support both overlap checks efficiently
--
-- These are "hard guardrails" - the last line of defense after application
-- layer validation (BookingService) and pessimistic locking (BookingRepository).
-- ============================================================================

-- ============================================================================
-- 1. DROP EXISTING MYSQL-SYNTAX TRIGGERS (if somehow applied)
-- ============================================================================
DROP TRIGGER IF EXISTS trg_prevent_overlapping_renter_bookings ON bookings;
DROP TRIGGER IF EXISTS prevent_overlapping_renter_bookings ON bookings;
DROP TRIGGER IF EXISTS trg_prevent_overlapping_car_bookings ON bookings;

DROP FUNCTION IF EXISTS fn_prevent_overlapping_renter_bookings();
DROP FUNCTION IF EXISTS fn_prevent_overlapping_car_bookings();

-- ============================================================================
-- 2. RENTER OVERLAP PREVENTION (One Driver, One Car)
-- ============================================================================
-- A renter cannot physically drive two cars simultaneously.
-- Blocking statuses: PENDING_APPROVAL, ACTIVE, CHECK_IN_OPEN,
--                    CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE, IN_TRIP
--
-- Overlap Formula: (A.start < B.end) AND (A.end > B.start)
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_renter_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
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

CREATE TRIGGER trg_prevent_overlapping_renter_bookings
    BEFORE INSERT OR UPDATE ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_overlapping_renter_bookings();

-- ============================================================================
-- 3. CAR DOUBLE-BOOKING PREVENTION (Race Condition Hard Guardrail)
-- ============================================================================
-- A car cannot be booked by two different users for overlapping times.
-- This is the database-level enforcement of the pessimistic lock in
-- BookingRepository.findOverlappingBookingsWithLock().
--
-- Even if two transactions slip past the application lock, this trigger
-- ensures only one booking succeeds at the database level.
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_car_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
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

CREATE TRIGGER trg_prevent_overlapping_car_bookings
    BEFORE INSERT OR UPDATE ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_overlapping_car_bookings();

-- ============================================================================
-- 4. ENSURE INDEXES EXIST (idempotent)
-- ============================================================================
-- These support both the trigger queries and the repository lock queries.

CREATE INDEX IF NOT EXISTS idx_booking_renter_overlap 
    ON bookings (renter_id, status, start_time, end_time);

CREATE INDEX IF NOT EXISTS idx_booking_time_overlap 
    ON bookings (car_id, start_time, end_time, status);

CREATE INDEX IF NOT EXISTS idx_booking_renter_time_overlap 
    ON bookings (renter_id, start_time, end_time, status);

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================
-- Verification:
--   SELECT tgname FROM pg_trigger WHERE tgrelid = 'bookings'::regclass;
--   Expected: trg_prevent_overlapping_renter_bookings,
--             trg_prevent_overlapping_car_bookings
-- ============================================================================
