-- ============================================================================
-- V12: Fix Renter Overlap Trigger Column Name
-- ============================================================================
-- 
-- Bug Fix: V10 trigger used wrong column name 'user_id' instead of 'renter_id'.
-- This caused the trigger to fail silently on MySQL or not match any rows.
--
-- This migration:
-- 1. Drops the broken trigger from V10
-- 2. Recreates it with correct column name 'renter_id'
-- 3. Fixes the index to match
--
-- Author: System Architect
-- Date: 2025-11-28
-- ============================================================================

-- Drop the broken trigger
DROP TRIGGER IF EXISTS trg_prevent_overlapping_renter_bookings;



-- Recreate trigger with correct column name (renter_id, not user_id)
CREATE TRIGGER trg_prevent_overlapping_renter_bookings
BEFORE INSERT ON bookings
FOR EACH ROW
BEGIN
    DECLARE overlap_count INT DEFAULT 0;
    
    -- Count overlapping bookings for the same RENTER with blocking statuses
    -- Overlap formula: (NewStart < ExistingEnd) AND (NewEnd > ExistingStart)
    -- 
    -- Blocking Statuses:
    -- - PENDING_APPROVAL: Awaiting host decision (blocks user's dates)
    -- - ACTIVE: Confirmed and ongoing/future trip
    --
    -- Non-blocking: CANCELLED, DECLINED, COMPLETED, EXPIRED, EXPIRED_SYSTEM
    SELECT COUNT(*) INTO overlap_count
    FROM bookings b
    WHERE b.renter_id = NEW.renter_id  -- FIXED: was user_id, now renter_id
      AND b.status IN ('PENDING_APPROVAL', 'ACTIVE')
      AND NEW.start_date < b.end_date
      AND NEW.end_date > b.start_date;
    
    -- If overlap found, reject the insert with a clear error message
    IF overlap_count > 0 THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'USER_OVERLAP: Renter already has an active/pending booking for these dates. One driver cannot rent two cars simultaneously.';
    END IF;
END //

DELIMITER ;

-- ============================================================================
-- Also add trigger for CAR overlap (prevent double-booking the same car)
-- This provides database-level enforcement beyond the application layer
-- ============================================================================

DROP TRIGGER IF EXISTS trg_prevent_overlapping_car_bookings;

DELIMITER //

CREATE TRIGGER trg_prevent_overlapping_car_bookings
BEFORE INSERT ON bookings
FOR EACH ROW
BEGIN
    DECLARE car_overlap_count INT DEFAULT 0;
    
    -- Count overlapping bookings for the same CAR with blocking statuses
    -- This prevents the "phantom blocking" bug where cancelled bookings still block dates
    --
    -- Blocking Statuses:
    -- - PENDING_APPROVAL: Awaiting host decision
    -- - ACTIVE: Confirmed trip
    --
    -- Non-blocking (dates are FREE): CANCELLED, DECLINED, COMPLETED, EXPIRED
    SELECT COUNT(*) INTO car_overlap_count
    FROM bookings b
    WHERE b.car_id = NEW.car_id
      AND b.status IN ('PENDING_APPROVAL', 'ACTIVE')
      AND NEW.start_date < b.end_date
      AND NEW.end_date > b.start_date;
    
    -- If overlap found, reject the insert
    IF car_overlap_count > 0 THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'CAR_UNAVAILABLE: This car is already booked for the selected dates. Please choose different dates.';
    END IF;
END //

DELIMITER ;

-- ============================================================================
-- Fix the index to use correct column name
-- ============================================================================

-- Drop old index if exists
DROP INDEX IF EXISTS idx_booking_renter_overlap ON bookings;

-- Create corrected index for renter overlap queries
CREATE INDEX idx_booking_renter_overlap 
ON bookings (renter_id, status, start_date, end_date)
COMMENT 'Optimizes renter overlap detection trigger and queries';

-- ============================================================================
-- Verification (uncomment to test):
-- ============================================================================
-- SHOW TRIGGERS LIKE 'bookings';
-- Expected: Two triggers - trg_prevent_overlapping_renter_bookings, trg_prevent_overlapping_car_bookings
--
-- SHOW INDEX FROM bookings WHERE Key_name = 'idx_booking_renter_overlap';
-- Expected: Index on (renter_id, status, start_date, end_date)
