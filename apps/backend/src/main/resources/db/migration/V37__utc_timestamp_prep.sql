-- ============================================================================
-- V37: UTC Timestamp Migration Preparation (Phase 1)
-- ============================================================================
-- 
-- GOAL: Prepare for UTC-based timestamp storage without breaking existing code.
-- 
-- STRATEGY (Dual-Write Pattern):
-- 1. Add new UTC columns alongside existing LocalDateTime columns
-- 2. Application writes to BOTH columns during transition period
-- 3. After monitoring confirms consistency, switch reads to UTC columns
-- 4. Drop old columns in V38 (Phase 2)
-- 
-- RATIONALE:
-- - LocalDateTime cannot represent DST transitions unambiguously
-- - October 26, 02:30 in Belgrade could be +01:00 or +02:00
-- - TIMESTAMP WITH TIME ZONE stores UTC internally, converts on display
-- 
-- AFFECTED TABLES: bookings, trip_extensions
-- 
-- ============================================================================

-- ============================================================================
-- BOOKINGS TABLE - Core trip start/end times
-- ============================================================================

-- Add UTC equivalent columns (nullable for transition period)
ALTER TABLE bookings 
    ADD COLUMN start_time_utc TIMESTAMP NULL COMMENT 'Trip start in UTC (Phase 1 prep)',
    ADD COLUMN end_time_utc TIMESTAMP NULL COMMENT 'Trip end in UTC (Phase 1 prep)';

-- Add index for UTC columns (will be used after migration)
CREATE INDEX idx_bookings_start_time_utc ON bookings(start_time_utc);
CREATE INDEX idx_bookings_end_time_utc ON bookings(end_time_utc);

-- ============================================================================
-- TRIP_EXTENSIONS TABLE - Extension deadline tracking
-- ============================================================================

-- Add UTC column for response deadline
-- Note: response_deadline is already Instant (UTC) in Java, but let's ensure DB alignment
ALTER TABLE trip_extensions 
    ADD COLUMN requested_end_date_utc TIMESTAMP NULL COMMENT 'Requested new end date in UTC';

-- ============================================================================
-- MIGRATION TRIGGER (Optional - for automated dual-write)
-- ============================================================================
-- 
-- This trigger automatically populates UTC columns when old columns are updated.
-- Assumes Europe/Belgrade timezone (Serbia).
-- 
-- NOTE: This is a SAFETY NET. Application should write both columns directly.
-- Trigger ensures consistency if legacy code path misses UTC column.
-- 
-- ============================================================================

DELIMITER //

CREATE TRIGGER trg_booking_utc_sync_insert
BEFORE INSERT ON bookings
FOR EACH ROW
BEGIN
    -- If UTC columns not provided, convert from local time
    -- Assumes start_time/end_time are in Europe/Belgrade
    -- MySQL CONVERT_TZ handles DST automatically
    IF NEW.start_time_utc IS NULL AND NEW.start_time IS NOT NULL THEN
        SET NEW.start_time_utc = CONVERT_TZ(NEW.start_time, 'Europe/Belgrade', 'UTC');
    END IF;
    
    IF NEW.end_time_utc IS NULL AND NEW.end_time IS NOT NULL THEN
        SET NEW.end_time_utc = CONVERT_TZ(NEW.end_time, 'Europe/Belgrade', 'UTC');
    END IF;
END //

CREATE TRIGGER trg_booking_utc_sync_update
BEFORE UPDATE ON bookings
FOR EACH ROW
BEGIN
    -- Sync UTC columns on update if local time changed
    IF NEW.start_time <> OLD.start_time OR OLD.start_time_utc IS NULL THEN
        SET NEW.start_time_utc = CONVERT_TZ(NEW.start_time, 'Europe/Belgrade', 'UTC');
    END IF;
    
    IF NEW.end_time <> OLD.end_time OR OLD.end_time_utc IS NULL THEN
        SET NEW.end_time_utc = CONVERT_TZ(NEW.end_time, 'Europe/Belgrade', 'UTC');
    END IF;
END //

DELIMITER ;

-- ============================================================================
-- BACKFILL EXISTING DATA (One-time migration)
-- ============================================================================
-- 
-- Convert existing local times to UTC.
-- IMPORTANT: Run during low-traffic period.
-- 
-- ============================================================================

UPDATE bookings 
SET start_time_utc = CONVERT_TZ(start_time, 'Europe/Belgrade', 'UTC'),
    end_time_utc = CONVERT_TZ(end_time, 'Europe/Belgrade', 'UTC')
WHERE start_time_utc IS NULL;

-- Verify conversion (should return 0 rows after backfill)
-- SELECT COUNT(*) FROM bookings WHERE start_time_utc IS NULL AND start_time IS NOT NULL;

-- ============================================================================
-- MONITORING QUERY (For post-migration validation)
-- ============================================================================
-- 
-- Run this query daily for 1 week to verify dual-write consistency:
-- 
-- SELECT 
--     id,
--     start_time,
--     start_time_utc,
--     CONVERT_TZ(start_time, 'Europe/Belgrade', 'UTC') AS expected_utc,
--     CASE 
--         WHEN start_time_utc = CONVERT_TZ(start_time, 'Europe/Belgrade', 'UTC') THEN 'OK'
--         ELSE 'MISMATCH'
--     END AS status
-- FROM bookings
-- WHERE updated_at > DATE_SUB(NOW(), INTERVAL 1 DAY)
-- HAVING status = 'MISMATCH';
-- 
-- ============================================================================
