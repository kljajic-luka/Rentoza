-- =============================================================================
-- UTF-8 (utf8mb4) Migration Script for Rentoza Database
-- =============================================================================
-- This script converts the entire database and all tables to utf8mb4 charset
-- to support Serbian characters (č, ć, đ, š, ž) and emojis.
--
-- SAFE TO RUN MULTIPLE TIMES (idempotent)
-- Existing data will be preserved and converted.
--
-- Usage:
--   mysql -u root -p rentoza < utf8mb4-migration.sql
--   Or run manually in MySQL Workbench/CLI
-- =============================================================================

-- Step 1: Convert database default charset
ALTER DATABASE rentoza CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Step 2: Convert all tables to utf8mb4
-- This will convert all VARCHAR, TEXT, and ENUM columns in each table

-- Core tables
ALTER TABLE users CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE cars CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE bookings CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE reviews CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Notification tables
ALTER TABLE notifications CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE user_device_tokens CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Authentication tables
ALTER TABLE refresh_tokens CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Owner availability calendar (if exists)
-- Uncomment if this table exists in your schema
-- ALTER TABLE owner_availability CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- Verification Queries
-- =============================================================================
-- After running this migration, verify the changes:

-- 1. Check database charset
-- SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
-- FROM information_schema.SCHEMATA
-- WHERE SCHEMA_NAME = 'rentoza';

-- 2. Check all tables charset
-- SELECT TABLE_NAME, TABLE_COLLATION
-- FROM information_schema.TABLES
-- WHERE TABLE_SCHEMA = 'rentoza';

-- 3. Check specific columns (e.g., notifications.message)
-- SHOW FULL COLUMNS FROM notifications;

-- Expected output for all text columns:
-- Collation: utf8mb4_unicode_ci

-- =============================================================================
-- NOTES
-- =============================================================================
-- 1. This migration preserves all existing data
-- 2. Indexes are automatically rebuilt during CONVERT TO
-- 3. utf8mb4 uses up to 4 bytes per character (vs 3 for utf8)
-- 4. VARCHAR(255) in utf8mb4 = max 255 characters (not bytes)
-- 5. All new tables/columns will inherit utf8mb4 by default
-- =============================================================================
