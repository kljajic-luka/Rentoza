-- ============================================================================
-- V35: H-Priority Enterprise Fixes
-- ============================================================================
-- Date: 2025-12-19
-- Author: Phase 2 Security Hardening
-- 
-- This migration implements database-level changes for H-priority fixes:
--   H9: Add @Version (optimistic locking) to cancellation_records
--   H3: No schema changes needed (cascade types are JPA-only)
--   H1, H4: No schema changes needed (business logic only)
-- ============================================================================

-- ============================================================================
-- H9 FIX: Add version column for optimistic locking on cancellation_records
-- ============================================================================
-- Purpose: Prevent lost updates when concurrent modifications occur.
-- The @Version annotation in JPA will automatically manage this column.
-- Default 0 for existing records.

ALTER TABLE cancellation_records 
ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;

-- Ensure all existing records have a version
UPDATE cancellation_records SET version = 0 WHERE version IS NULL;

-- Make column NOT NULL after setting defaults
ALTER TABLE cancellation_records 
ALTER COLUMN version SET NOT NULL;

-- Add comment for documentation
COMMENT ON COLUMN cancellation_records.version IS 
    'JPA @Version field for optimistic locking (H9 fix - prevents concurrent update conflicts)';

-- ============================================================================
-- VERIFICATION QUERIES (for manual verification after migration)
-- ============================================================================
-- Run these queries manually to verify migration success:
--
-- 1. Check version column exists:
--    SELECT column_name, data_type, is_nullable 
--    FROM information_schema.columns 
--    WHERE table_name = 'cancellation_records' AND column_name = 'version';
--
-- 2. Check all records have version:
--    SELECT COUNT(*) FROM cancellation_records WHERE version IS NULL;
--    (Should return 0)
-- ============================================================================
