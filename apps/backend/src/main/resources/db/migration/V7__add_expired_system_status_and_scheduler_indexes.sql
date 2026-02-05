-- V7: Add EXPIRED_SYSTEM status support and index for scheduler optimization
-- This migration documents the new booking status for system auto-expiry
-- and optimizes the scheduler query performance.
-- 
-- Database: MySQL 8.0+

-- Step 1: Document the EXPIRED_SYSTEM status in the status column
-- Note: JPA/Hibernate manages enum values, but we update the column size for safety
ALTER TABLE bookings MODIFY COLUMN status VARCHAR(25) NOT NULL;

-- Step 2: Add index for scheduler query optimization
-- Query pattern: SELECT * FROM bookings WHERE status = 'PENDING_APPROVAL' AND decision_deadline_at < NOW()
-- Note: MySQL doesn't support partial indexes (WHERE clause in CREATE INDEX)
-- We use a composite index instead for efficient filtering
-- Using procedure to handle "index doesn't exist" error gracefully
DROP PROCEDURE IF EXISTS drop_index_if_exists;

DELIMITER //
CREATE PROCEDURE drop_index_if_exists()
BEGIN
    -- Drop idx_bookings_pending_deadline if it exists
    IF EXISTS (SELECT 1 FROM information_schema.statistics 
               WHERE table_schema = DATABASE() 
               AND table_name = 'bookings' 
               AND index_name = 'idx_bookings_pending_deadline') THEN
        DROP INDEX idx_bookings_pending_deadline ON bookings;
    END IF;
    
    -- Drop idx_bookings_deadline_at if it exists
    IF EXISTS (SELECT 1 FROM information_schema.statistics 
               WHERE table_schema = DATABASE() 
               AND table_name = 'bookings' 
               AND index_name = 'idx_bookings_deadline_at') THEN
        DROP INDEX idx_bookings_deadline_at ON bookings;
    END IF;
END //
DELIMITER ;

CALL drop_index_if_exists();
DROP PROCEDURE IF EXISTS drop_index_if_exists;

-- Now create the indexes
CREATE INDEX idx_bookings_pending_deadline ON bookings(status, decision_deadline_at);

-- Step 3: Add index on decision_deadline_at for range queries
-- Optimizes: ORDER BY decision_deadline_at, WHERE decision_deadline_at < :threshold
CREATE INDEX idx_bookings_deadline_at ON bookings(decision_deadline_at);

-- Migration Notes:
-- 
-- EXPIRED_SYSTEM vs EXPIRED:
-- - EXPIRED: Legacy status (backwards compatible, still used for user-initiated expirations)
-- - EXPIRED_SYSTEM: New status for system auto-expiry due to host inactivity
--                   Enables analytics differentiation and distinct notification text
-- 
-- Scheduler Frequency Change:
-- - Previous: Every 6 hours (cron: 0 0 0/6 * * *)
-- - New: Every 15 minutes (cron: 0 0/15 * * * *)
-- - Rationale: "Short Notice" bookings need faster expiry detection
-- 
-- Decision Deadline Formula Change:
-- - Previous: NOW() + 48h (fixed)
-- - New: MIN(NOW() + 48h, TripStartTime - 1h) (dynamic)
-- - Rationale: Ensures minimum 1h buffer for guest between approval and trip start
