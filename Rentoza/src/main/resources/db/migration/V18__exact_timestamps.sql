-- ============================================================================
-- V18: Exact Timestamp Architecture Migration
-- ============================================================================
-- Purpose: Migrate from "Date + Time Window" model to "Exact Timestamp" model
-- 
-- Old Model: startDate (DATE) + pickupTimeWindow (ENUM) + pickupTime (TIME)
-- New Model: startTime (DATETIME) + endTime (DATETIME)
--
-- Timezone: All times stored as Europe/Belgrade local time (no UTC conversion)
-- Minimum Duration: 24 hours (overnight rental required)
-- Time Granularity: 30-minute slots
--
-- Migration Strategy: Two-phase to prevent data loss
--   Phase 1: Add new columns, migrate data, verify
--   Phase 2: Drop old columns (separate migration V19 recommended for safety)
-- ============================================================================

-- ============================================================================
-- PHASE 1: ADD NEW TIMESTAMP COLUMNS
-- ============================================================================

ALTER TABLE bookings
    ADD COLUMN start_time DATETIME NULL AFTER end_date,
    ADD COLUMN end_time DATETIME NULL AFTER start_time;

-- ============================================================================
-- PHASE 2: MIGRATE EXISTING DATA
-- ============================================================================
-- Conversion Rules:
--   MORNING    -> 09:00:00
--   AFTERNOON  -> 14:00:00
--   EVENING    -> 18:00:00
--   EXACT      -> use pickup_time value
--   NULL/Other -> 09:00:00 (default)
--
-- End Time: Set to 10:00:00 on end_date (standard return time)
-- ============================================================================

UPDATE bookings 
SET start_time = CASE
        WHEN pickup_time_window = 'MORNING' THEN 
            TIMESTAMP(start_date, '09:00:00')
        WHEN pickup_time_window = 'AFTERNOON' THEN 
            TIMESTAMP(start_date, '14:00:00')
        WHEN pickup_time_window = 'EVENING' THEN 
            TIMESTAMP(start_date, '18:00:00')
        WHEN pickup_time_window = 'EXACT' AND pickup_time IS NOT NULL THEN 
            TIMESTAMP(start_date, pickup_time)
        ELSE 
            TIMESTAMP(start_date, '09:00:00')
    END,
    end_time = TIMESTAMP(end_date, '10:00:00')
WHERE start_time IS NULL;

-- ============================================================================
-- PHASE 3: ENFORCE NOT NULL CONSTRAINTS
-- ============================================================================

ALTER TABLE bookings
    MODIFY COLUMN start_time DATETIME NOT NULL,
    MODIFY COLUMN end_time DATETIME NOT NULL;

-- ============================================================================
-- PHASE 4: CREATE INDEXES FOR TIMESTAMP-BASED QUERIES
-- ============================================================================

-- Index for scheduler queries (finding bookings by start time)
CREATE INDEX idx_booking_start_time ON bookings(start_time);

-- Index for checkout/late detection queries
CREATE INDEX idx_booking_end_time ON bookings(end_time);

-- Composite index for overlap detection (most critical for performance)
-- Supports: existsOverlappingBookings, existsOverlappingBookingsWithLock
CREATE INDEX idx_booking_time_overlap ON bookings(car_id, start_time, end_time, status);

-- Composite index for user overlap detection
CREATE INDEX idx_booking_renter_time_overlap ON bookings(renter_id, start_time, end_time, status);

-- ============================================================================
-- PHASE 5: DROP DEPRECATED COLUMNS
-- ============================================================================
-- NOTE: These columns are now redundant. Dropping them in this migration.
-- If you prefer a safer approach, move this to V19 after verifying data.
-- ============================================================================

ALTER TABLE bookings
    DROP COLUMN pickup_time_window,
    DROP COLUMN pickup_time,
    DROP COLUMN start_date,
    DROP COLUMN end_date;

-- ============================================================================
-- PHASE 6: UPDATE TRIGGER FOR RENTER OVERLAP DETECTION
-- ============================================================================
-- The V10 trigger used start_date/end_date. We need to update it for timestamps.
-- ============================================================================

DROP TRIGGER IF EXISTS prevent_overlapping_renter_bookings;

DELIMITER //

CREATE TRIGGER prevent_overlapping_renter_bookings
BEFORE INSERT ON bookings
FOR EACH ROW
BEGIN
    DECLARE overlap_count INT;
    
    -- Check for overlapping bookings for the same renter
    -- Overlap Formula: (A.start < B.end) AND (A.end > B.start)
    -- Only check against blocking statuses
    SELECT COUNT(*) INTO overlap_count
    FROM bookings
    WHERE renter_id = NEW.renter_id
      AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 
                     'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;
    
    IF overlap_count > 0 THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'USER_OVERLAP: User already has an active booking for this time period';
    END IF;
END//

DELIMITER ;

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================
-- Post-Migration Verification Queries:
-- 
-- 1. Check all bookings have valid timestamps:
--    SELECT COUNT(*) FROM bookings WHERE start_time IS NULL OR end_time IS NULL;
--    Expected: 0
--
-- 2. Check no booking has end_time before start_time:
--    SELECT COUNT(*) FROM bookings WHERE end_time <= start_time;
--    Expected: 0
--
-- 3. Verify data migration accuracy (sample):
--    SELECT id, start_time, end_time FROM bookings LIMIT 10;
-- ============================================================================

