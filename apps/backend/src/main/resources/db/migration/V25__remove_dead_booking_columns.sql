-- V25__remove_dead_booking_columns.sql
-- =============================================
-- BOOKING LIFECYCLE CLEANUP - Remove Dead Code Columns
-- =============================================
-- Date: 2025-12-06
-- Ticket: BOOKING_COLUMN_ANALYSIS.md remediation
-- 
-- These columns were declared in Booking.java but never populated:
-- 1. execution_location_updated_by - Pickup location refinement UI never implemented
-- 2. execution_location_updated_at - Pickup location refinement UI never implemented
--
-- If these features are implemented in the future, create new migration to add them back.
-- =============================================

-- Step 1: Drop foreign key constraint first (if it exists)
-- Using dynamic SQL for maximum compatibility (MySQL < 8.0.13 does not support IF EXISTS for FK)
SET @drop_fk_sql = NULL;
SELECT IF(COUNT(*) > 0, 'ALTER TABLE bookings DROP FOREIGN KEY fk_booking_execution_location_updated_by', 'SELECT 1')
INTO @drop_fk_sql
FROM information_schema.table_constraints
WHERE table_schema = DATABASE()
  AND table_name = 'bookings'
  AND constraint_name = 'fk_booking_execution_location_updated_by';

PREPARE stmt FROM @drop_fk_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 2: Drop the dead columns using dynamic SQL
-- Drop 'execution_location_updated_by'
SET @drop_col_sql = NULL;
SELECT IF(COUNT(*) > 0, 'ALTER TABLE bookings DROP COLUMN execution_location_updated_by', 'SELECT 1')
INTO @drop_col_sql
FROM information_schema.columns 
WHERE table_schema = DATABASE() 
  AND table_name = 'bookings' 
  AND column_name = 'execution_location_updated_by';

PREPARE stmt FROM @drop_col_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Drop 'execution_location_updated_at'
SET @drop_col_sql = NULL;
SELECT IF(COUNT(*) > 0, 'ALTER TABLE bookings DROP COLUMN execution_location_updated_at', 'SELECT 1')
INTO @drop_col_sql
FROM information_schema.columns 
WHERE table_schema = DATABASE() 
  AND table_name = 'bookings' 
  AND column_name = 'execution_location_updated_at';

PREPARE stmt FROM @drop_col_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add comment to table documenting the change (this line is a comment, not executable SQL)
-- Note: MySQL doesn't support column-level comments via ALTER, this is just for audit
-- Future developers: see BOOKING_COLUMN_ANALYSIS.md for full lifecycle documentation