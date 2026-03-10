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

-- PostgreSQL-safe note:
-- Later migration V97 owns bookings.start_time_utc/end_time_utc creation and
-- backfill. This migration therefore only keeps the trip_extensions UTC prep
-- that does not conflict with the later canonical UTC rollout.

ALTER TABLE IF EXISTS trip_extensions
    ADD COLUMN IF NOT EXISTS requested_end_date_utc TIMESTAMP WITH TIME ZONE NULL;

COMMENT ON COLUMN trip_extensions.requested_end_date_utc IS
    'Requested new end date in UTC (legacy prep column; canonical booking UTC columns arrive in V97)';

UPDATE trip_extensions
SET requested_end_date_utc = (requested_end_date::timestamp AT TIME ZONE 'Europe/Belgrade')
WHERE requested_end_date_utc IS NULL
  AND requested_end_date IS NOT NULL;
