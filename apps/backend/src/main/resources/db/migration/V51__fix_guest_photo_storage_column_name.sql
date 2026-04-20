-- ============================================================================
-- V51: Fix photo table storage column name mismatch
-- ============================================================================
-- Problem: The Supabase migration scripts created photo tables with column
-- "storage_path", but JPA entities map to "storage_key" (matching the
-- Flyway V14/V36 definitions). Since V36 uses CREATE TABLE IF NOT EXISTS,
-- it was a no-op on Supabase where the tables already existed with
-- "storage_path".
--
-- Result: Hibernate writes to "storage_key" but "storage_path" (NOT NULL)
-- is never populated → DataIntegrityViolationException on guest photo upload.
--
-- Fix: Normalize all photo tables to "storage_key" to match entity mappings.
-- Handles three cases per table:
--   Case 1: Both columns exist → merge data, drop storage_path
--   Case 2: Only storage_path → rename to storage_key
--   Case 3: Only storage_key → no-op
-- ============================================================================

-- ===== guest_check_in_photos =====
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'guest_check_in_photos' AND column_name = 'storage_path'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'guest_check_in_photos' AND column_name = 'storage_key'
    ) THEN
        RAISE NOTICE 'guest_check_in_photos: both columns exist, merging.';
        UPDATE guest_check_in_photos
           SET storage_key = storage_path
         WHERE storage_key IS NULL AND storage_path IS NOT NULL;
        ALTER TABLE guest_check_in_photos DROP COLUMN storage_path;

    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'guest_check_in_photos' AND column_name = 'storage_path'
    ) THEN
        RAISE NOTICE 'guest_check_in_photos: renaming storage_path to storage_key.';
        ALTER TABLE guest_check_in_photos RENAME COLUMN storage_path TO storage_key;

    ELSE
        RAISE NOTICE 'guest_check_in_photos: already correct.';
    END IF;
END $$;

ALTER TABLE guest_check_in_photos ALTER COLUMN storage_key SET NOT NULL;

-- ===== host_checkout_photos (same issue, proactive fix) =====
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'host_checkout_photos' AND column_name = 'storage_path'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'host_checkout_photos' AND column_name = 'storage_key'
    ) THEN
        RAISE NOTICE 'host_checkout_photos: both columns exist, merging.';
        UPDATE host_checkout_photos
           SET storage_key = storage_path
         WHERE storage_key IS NULL AND storage_path IS NOT NULL;
        ALTER TABLE host_checkout_photos DROP COLUMN storage_path;

    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'host_checkout_photos' AND column_name = 'storage_path'
    ) THEN
        RAISE NOTICE 'host_checkout_photos: renaming storage_path to storage_key.';
        ALTER TABLE host_checkout_photos RENAME COLUMN storage_path TO storage_key;

    ELSE
        RAISE NOTICE 'host_checkout_photos: already correct.';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'host_checkout_photos' AND column_name = 'storage_key'
    ) THEN
        ALTER TABLE host_checkout_photos ALTER COLUMN storage_key SET NOT NULL;
    END IF;
END $$;

-- ===== check_in_photos (verify, likely already correct since host uploads work) =====
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'check_in_photos' AND column_name = 'storage_path'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'check_in_photos' AND column_name = 'storage_key'
    ) THEN
        RAISE NOTICE 'check_in_photos: both columns exist, merging.';
        UPDATE check_in_photos
           SET storage_key = storage_path
         WHERE storage_key IS NULL AND storage_path IS NOT NULL;
        ALTER TABLE check_in_photos DROP COLUMN storage_path;

    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'check_in_photos' AND column_name = 'storage_path'
    ) THEN
        RAISE NOTICE 'check_in_photos: renaming storage_path to storage_key.';
        ALTER TABLE check_in_photos RENAME COLUMN storage_path TO storage_key;

    ELSE
        RAISE NOTICE 'check_in_photos: already correct.';
    END IF;
END $$;
