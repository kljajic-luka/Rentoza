-- ============================================================================
-- V8: Phase 1 Critical Remediation
-- ============================================================================
-- Purpose: Address three critical audit findings:
--   1. Financial Precision: Double -> DECIMAL(19,2) for currency fields
--   2. Concurrency: Index for pessimistic locking during booking creation
--   3. Admin Tooling: User moderation fields (ban/suspend capability)
--
-- Database: MySQL 8.0+
-- Audit Reference: COMPREHENSIVE_5_SECTOR_ARCHITECTURAL_AUDIT.md
-- Risk Level: CRITICAL (Financial Integrity, Race Conditions, Platform Safety)
-- ============================================================================

-- ============================================================================
-- SUB-TASK A: Financial Precision Migration (Double -> BigDecimal)
-- ============================================================================
-- Problem: IEEE 754 floating-point cannot precisely represent decimal fractions.
--          For Serbian Dinar (RSD), 10.10 becomes 10.0999999999...
--          This causes cumulative rounding errors in multi-day bookings.
--
-- Solution: Use DECIMAL(19, 2) - 19 digits total, 2 after decimal point.
--          This supports values up to 99,999,999,999,999,999.99 RSD
--          which is sufficient for any car rental scenario.
--
-- CRITICAL: ALTER COLUMN preserves existing data with precision loss only at
--           conversion (existing doubles are truncated to 2 decimal places).
-- ============================================================================

-- 1. Migrate bookings.total_price (Double -> DECIMAL)
ALTER TABLE bookings 
MODIFY COLUMN total_price DECIMAL(19, 2) NOT NULL DEFAULT 0.00
COMMENT 'Total booking price in RSD with 2 decimal precision';

-- 2. Migrate cars.price_per_day (Double -> DECIMAL)
ALTER TABLE cars 
MODIFY COLUMN price_per_day DECIMAL(19, 2) NOT NULL DEFAULT 0.00
COMMENT 'Daily rental price in RSD with 2 decimal precision';

-- 3. Migrate cars.fuel_consumption (optional, but for consistency)
-- Note: Fuel consumption is L/100km, not currency, but benefits from precision
ALTER TABLE cars 
MODIFY COLUMN fuel_consumption DECIMAL(5, 2) DEFAULT NULL
COMMENT 'Fuel consumption in liters per 100km';


-- ============================================================================
-- SUB-TASK B: Concurrency Index for Pessimistic Locking
-- ============================================================================
-- Problem: Booking creation uses existsOverlappingBookings() without locking.
--          Two simultaneous requests can both pass the check and create
--          overlapping bookings (race condition).
--
-- Solution: Create composite index for efficient SELECT ... FOR UPDATE queries.
--          The pessimistic lock in BookingRepository will use this index
--          to lock only relevant rows (not entire table).
--
-- Index Strategy:
--   (car_id, start_date, end_date) - Covers all fields in overlap check query
--   Partial index on status not supported in MySQL, but query includes it
-- ============================================================================

-- Drop if exists to make migration idempotent
DROP INDEX IF EXISTS idx_booking_concurrency_lock ON bookings;

-- Create composite index for concurrent booking prevention
CREATE INDEX idx_booking_concurrency_lock 
ON bookings (car_id, start_date, end_date, status)
COMMENT 'Optimizes pessimistic locking queries for booking creation';

-- Additional index for scheduler queries (find pending bookings by deadline)
DROP INDEX IF EXISTS idx_booking_pending_deadline ON bookings;
CREATE INDEX idx_booking_pending_deadline 
ON bookings (status, decision_deadline_at)
COMMENT 'Optimizes scheduler queries for expired pending bookings';


-- ============================================================================
-- SUB-TASK C: Admin Infrastructure - User Moderation Fields
-- ============================================================================
-- Problem: No ability to ban/suspend malicious users.
--          User.java lacks banned, banReason, bannedAt fields.
--
-- Solution: Add moderation fields to users table.
--          These will be controlled by AdminUserController (ROLE_ADMIN only).
--
-- Fields:
--   banned      - Boolean flag for immediate login block
--   ban_reason  - Admin-provided reason (audit trail)
--   banned_at   - Timestamp for ban duration tracking
--   banned_by   - Admin who performed the action (foreign key to users)
-- ============================================================================

-- Add user moderation columns
ALTER TABLE users
ADD COLUMN banned BOOLEAN NOT NULL DEFAULT FALSE
COMMENT 'If true, user cannot login or perform any actions';

ALTER TABLE users
ADD COLUMN ban_reason VARCHAR(500) DEFAULT NULL
COMMENT 'Admin-provided reason for the ban (audit trail)';

ALTER TABLE users
ADD COLUMN banned_at TIMESTAMP NULL DEFAULT NULL
COMMENT 'Timestamp when user was banned';

ALTER TABLE users
ADD COLUMN banned_by BIGINT NULL DEFAULT NULL
COMMENT 'Admin user ID who performed the ban action';

-- Add foreign key for banned_by (references users.id)
ALTER TABLE users
ADD CONSTRAINT fk_users_banned_by 
FOREIGN KEY (banned_by) REFERENCES users(id)
ON DELETE SET NULL
ON UPDATE CASCADE;

-- Index for efficient banned user queries
CREATE INDEX idx_users_banned ON users (banned)
COMMENT 'Optimizes banned user filtering in security checks';


-- ============================================================================
-- VERIFICATION QUERIES (Run after migration to confirm success)
-- ============================================================================
-- Uncomment and run these to verify migration:
--
-- SELECT COLUMN_NAME, DATA_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE 
-- FROM INFORMATION_SCHEMA.COLUMNS 
-- WHERE TABLE_NAME = 'bookings' AND COLUMN_NAME = 'total_price';
-- -- Expected: DECIMAL, 19, 2
--
-- SELECT COLUMN_NAME, DATA_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE 
-- FROM INFORMATION_SCHEMA.COLUMNS 
-- WHERE TABLE_NAME = 'cars' AND COLUMN_NAME = 'price_per_day';
-- -- Expected: DECIMAL, 19, 2
--
-- SELECT COLUMN_NAME, DATA_TYPE 
-- FROM INFORMATION_SCHEMA.COLUMNS 
-- WHERE TABLE_NAME = 'users' AND COLUMN_NAME IN ('banned', 'ban_reason', 'banned_at', 'banned_by');
-- -- Expected: 4 rows with correct types
--
-- SHOW INDEX FROM bookings WHERE Key_name = 'idx_booking_concurrency_lock';
-- -- Expected: 1 row showing composite index
-- ============================================================================
