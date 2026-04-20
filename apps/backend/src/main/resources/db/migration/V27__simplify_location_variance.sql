-- ============================================================================
-- V27: Phase 2 - Turo-Style Simplification (GPS Coordinate Fix)
-- ============================================================================
-- AUTHOR: Principal Software Architect
-- DATE: December 7, 2025
-- JIRA: RENTOZA-PHASE2-GPS-FIX
--
-- PROBLEM SUMMARY:
-- - carLatitude/carLongitude fields are orphaned (never populated)
-- - Pickup location variance validation never executes (40% dead code)
-- - EXIF validation has chicken-and-egg problem (requires car location at upload time)
--
-- PHASE 2 SOLUTION:
-- - Derive car location from first valid photo EXIF GPS (at host submission)
-- - Soft-deprecate pickup_location_variance_meters (not calculated anymore)
-- - Soft-deprecate execution_location_updated_* (location refinement UI never built)
-- - Keep columns for 12-week grace period (backward compatibility)
-- - Full removal in Phase 3 (V28+) after production data analysis
--
-- BACKWARD COMPATIBILITY:
-- - Non-breaking: columns remain, only FK constraint dropped
-- - Rollback safe: Re-add FK constraint via rollback script
-- - No data loss: existing NULL values preserved
-- ============================================================================

-- ============================================================================
-- STEP 1: Create deprecation tracking table
-- ============================================================================
-- ============================================================================
-- STEP 1: Create deprecation tracking table
-- ============================================================================
CREATE TABLE IF NOT EXISTS schema_deprecations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    column_name VARCHAR(64) NOT NULL,
    deprecated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scheduled_removal_at DATETIME NOT NULL
        COMMENT '12 weeks after deprecation - gives ops team buffer for analysis',
    deprecation_reason TEXT NOT NULL,
    migration_version VARCHAR(50) NOT NULL,
    INDEX idx_removal_date (scheduled_removal_at),
    UNIQUE KEY uk_table_column (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Tracks deprecated schema elements for phased removal';


-- ============================================================================
-- STEP 2: Drop FK constraint (enables future column removal)
-- ============================================================================
-- NOTE: DROP FOREIGN KEY IF EXISTS only works in MySQL 8.0.19+
-- Using conditional drop for compatibility with older versions

-- Check if FK exists and drop it conditionally
SET @fk_exists := (
    SELECT COUNT(*) 
    FROM information_schema.TABLE_CONSTRAINTS 
    WHERE CONSTRAINT_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'bookings' 
      AND CONSTRAINT_NAME = 'fk_booking_execution_location_updated_by'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);

SET @drop_fk_sql := IF(
    @fk_exists > 0,
    'ALTER TABLE bookings DROP FOREIGN KEY fk_booking_execution_location_updated_by',
    'SELECT "FK constraint does not exist, skipping drop" AS info'
);

PREPARE stmt FROM @drop_fk_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ============================================================================
-- STEP 3: Log deprecations for Phase 3 cleanup tracking
-- ============================================================================
INSERT INTO schema_deprecations (table_name, column_name, deprecated_at, scheduled_removal_at, deprecation_reason, migration_version)
VALUES 
    ('bookings', 'pickup_location_variance_meters', NOW(), DATE_ADD(NOW(), INTERVAL 12 WEEK),
     'Phase 2: Location variance validation removed (Turo-style simplification). Variance never calculated because carLatitude/carLongitude fields were orphaned. Now trust photos by default, use audit trail for disputes.',
     'V27'),
    ('bookings', 'execution_location_updated_by', NOW(), DATE_ADD(NOW(), INTERVAL 12 WEEK),
     'Phase 2: Location refinement UI never implemented (dead code). Column has 100% NULL values in production. FK constraint already dropped.',
     'V27'),
    ('bookings', 'execution_location_updated_at', NOW(), DATE_ADD(NOW(), INTERVAL 12 WEEK),
     'Phase 2: Location refinement UI never implemented (dead code). Column has 100% NULL values in production.',
     'V27')
ON DUPLICATE KEY UPDATE 
    deprecated_at = NOW(),
    scheduled_removal_at = DATE_ADD(NOW(), INTERVAL 12 WEEK),
    deprecation_reason = VALUES(deprecation_reason),
    migration_version = VALUES(migration_version);


-- ============================================================================
-- STEP 4: Add comments documenting deprecation (in-database documentation)
-- ============================================================================
ALTER TABLE bookings 
    MODIFY COLUMN pickup_location_variance_meters INT NULL
        COMMENT 'DEPRECATED (V27): No longer calculated. Phase 3 removal scheduled. See schema_deprecations.',
    MODIFY COLUMN execution_location_updated_by BIGINT NULL
        COMMENT 'DEPRECATED (V27): Dead column (location refinement UI never built). Phase 3 removal scheduled.',
    MODIFY COLUMN execution_location_updated_at DATETIME NULL
        COMMENT 'DEPRECATED (V27): Dead column (location refinement UI never built). Phase 3 removal scheduled.';


-- ============================================================================
-- VERIFICATION QUERIES (Run these to confirm migration success)
-- ============================================================================
-- 1. Check deprecation log entries
SELECT * FROM schema_deprecations WHERE table_name = 'bookings' ORDER BY deprecated_at DESC;

-- 2. Verify FK constraint dropped
SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS 
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' 
  AND CONSTRAINT_NAME = 'fk_booking_execution_location_updated_by';
-- (Should return 0 rows)

-- 3. Check column comments updated
SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS 
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' 
  AND COLUMN_NAME IN ('pickup_location_variance_meters', 'execution_location_updated_by', 'execution_location_updated_at');


-- ============================================================================
-- ROLLBACK SCRIPT (If Phase 2 needs to be reverted)
-- ============================================================================
-- WARNING: Only run this if rolling back to pre-Phase 2 codebase

/*
-- Re-add FK constraint
ALTER TABLE bookings 
    ADD CONSTRAINT fk_booking_execution_location_updated_by
    FOREIGN KEY (execution_location_updated_by) 
    REFERENCES users(id) ON DELETE SET NULL;

-- Remove deprecation comments
ALTER TABLE bookings 
    MODIFY COLUMN pickup_location_variance_meters INT NULL
        COMMENT 'Distance car moved from booked location (at check-in)',
    MODIFY COLUMN execution_location_updated_by BIGINT NULL
        COMMENT 'Host who refined pickup location at check-in',
    MODIFY COLUMN execution_location_updated_at DATETIME NULL
        COMMENT 'When host refined the pickup location';

-- Delete deprecation log entries
DELETE FROM schema_deprecations WHERE migration_version = 'V27';
*/
