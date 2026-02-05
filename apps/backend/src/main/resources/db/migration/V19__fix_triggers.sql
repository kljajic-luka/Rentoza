-- ============================================================================
-- V19: Fix Database Triggers
-- ============================================================================
-- Purpose: 
-- 1. Drop legacy triggers that reference dropped columns (start_date/end_date)
-- 2. Restore car overlap check using new timestamp columns
-- ============================================================================

-- 1. Drop legacy triggers from V13/V10 that reference 'start_date'
-- These caused "Unknown column 'start_date' in 'NEW'" error after V18 dropped the column
DROP TRIGGER IF EXISTS trg_prevent_overlapping_renter_bookings;
DROP TRIGGER IF EXISTS trg_prevent_overlapping_car_bookings;

-- 2. Restore car overlap check using new timestamp columns
-- V18 recreated the renter trigger but missed the car trigger
DELIMITER //

CREATE TRIGGER prevent_overlapping_car_bookings
BEFORE INSERT ON bookings
FOR EACH ROW
BEGIN
    DECLARE car_overlap_count INT;
    
    -- Count overlapping bookings for the same CAR with blocking statuses
    -- Overlap Formula: (A.start < B.end) AND (A.end > B.start)
    -- 
    -- Blocking Statuses:
    -- - PENDING_APPROVAL: Awaiting host decision
    -- - ACTIVE: Confirmed trip
    -- - CHECK_IN_*: Check-in in progress
    -- - IN_TRIP: Currently on trip
    SELECT COUNT(*) INTO car_overlap_count
    FROM bookings
    WHERE car_id = NEW.car_id
      AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 
                     'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;
    
    IF car_overlap_count > 0 THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'CAR_UNAVAILABLE: This car is already booked for the selected dates.';
    END IF;
END//

DELIMITER ;
