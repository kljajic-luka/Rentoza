-- ============================================================================
-- V10: Prevent Overlapping Renter Bookings (Single-Booking Constraint)
-- ============================================================================
-- 
-- Business Rule: "One Driver, One Car"
-- A renter cannot physically drive two cars simultaneously. This migration
-- implements a database-level constraint to prevent overlapping bookings.
--
-- Architecture:
-- - BEFORE INSERT trigger: Hard guardrail against race conditions
-- - Application layer check: Soft guardrail for user-friendly messages
--
-- Why Database Trigger?
-- The application layer check (BookingService.existsOverlappingUserBooking) is
-- insufficient alone. A user could open two browser tabs and submit two bookings
-- simultaneously, bypassing the application check. The trigger ensures atomicity.
--
-- Overlap Formula:
-- Two ranges [A_start, A_end] and [B_start, B_end] overlap if:
--   (A_start < B_end) AND (A_end > B_start)
--
-- Status Filter (blocking statuses only):
-- - PENDING_APPROVAL: Request awaiting host decision
-- - ACTIVE: Confirmed and ongoing/future trip
--
-- Non-blocking statuses:
-- - CANCELLED, DECLINED, COMPLETED, EXPIRED, EXPIRED_SYSTEM
--
-- Author: System Architect
-- Date: 2025-11-27
-- ============================================================================

-- Drop trigger if it exists (for idempotency during development)
DROP TRIGGER IF EXISTS trg_prevent_overlapping_renter_bookings;

DELIMITER //

CREATE TRIGGER trg_prevent_overlapping_renter_bookings
BEFORE INSERT ON bookings
FOR EACH ROW
BEGIN
    DECLARE overlap_count INT DEFAULT 0;
    
    -- Count overlapping bookings for the same user with blocking statuses
    -- Overlap formula: (NewStart < ExistingEnd) AND (NewEnd > ExistingStart)
    SELECT COUNT(*) INTO overlap_count
    FROM bookings b
    WHERE b.user_id = NEW.user_id
      AND b.status IN ('PENDING_APPROVAL', 'ACTIVE')
      AND NEW.start_date < b.end_date
      AND NEW.end_date > b.start_date;
    
    -- If overlap found, reject the insert with a clear error message
    IF overlap_count > 0 THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Constraint Violation: User has overlapping booking. A renter cannot book two cars for the same dates.';
    END IF;
END //

DELIMITER ;

-- ============================================================================
-- Performance Index: Optimize the overlap check query
-- ============================================================================
-- Composite index on (user_id, status, start_date, end_date) for fast lookups
-- This index supports both the trigger query and the repository method

CREATE INDEX IF NOT EXISTS idx_booking_renter_overlap 
ON bookings (user_id, status, start_date, end_date);

-- ============================================================================
-- Verification Query (for testing/debugging)
-- ============================================================================
-- Run this to verify the trigger exists:
-- SHOW TRIGGERS LIKE 'bookings';
-- 
-- Test case (should fail if user 1 has active booking 2025-01-01 to 2025-01-05):
-- INSERT INTO bookings (user_id, car_id, start_date, end_date, status, ...)
-- VALUES (1, 999, '2025-01-03', '2025-01-07', 'PENDING_APPROVAL', ...);
-- Expected: SQLSTATE 45000 error
