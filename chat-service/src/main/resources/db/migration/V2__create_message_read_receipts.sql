-- ============================================================================
-- V2__create_message_read_receipts.sql
-- Flyway migration for Phase 2: Read Receipts Migration
-- ============================================================================
-- Replaces @ElementCollection (anti-pattern) with dedicated table
-- Benefits:
-- 1. Proper timestamps for read receipts (not just boolean)
-- 2. No N+1 queries - batch INSERT with saveAll()
-- 3. Efficient queries with proper indexes
-- 4. Supports future features like "seen by" lists with timestamps
-- ============================================================================

-- Create normalized read receipts table
CREATE TABLE IF NOT EXISTS message_read_receipts (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key constraint
    CONSTRAINT fk_read_receipt_message 
        FOREIGN KEY (message_id) 
        REFERENCES messages(id) 
        ON DELETE CASCADE,
    
    -- Ensure each user can only read a message once
    CONSTRAINT uk_message_user UNIQUE (message_id, user_id)
);

-- Create indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_read_receipts_message 
    ON message_read_receipts(message_id);

CREATE INDEX IF NOT EXISTS idx_read_receipts_user 
    ON message_read_receipts(user_id);

-- Composite index for checking if specific user read specific message
CREATE INDEX IF NOT EXISTS idx_read_receipts_message_user 
    ON message_read_receipts(message_id, user_id);

-- ============================================================================
-- DATA MIGRATION: Migrate existing data from message_read_by (ElementCollection)
-- ============================================================================
-- IMPORTANT: Run this AFTER deploying new code but BEFORE dropping old table
-- This preserves existing read status during migration

INSERT INTO message_read_receipts (message_id, user_id, read_at)
SELECT message_id, user_id, NOW()
FROM message_read_by
ON CONFLICT (message_id, user_id) DO NOTHING;

-- ============================================================================
-- CLEANUP: Drop old ElementCollection table (after verification)
-- ============================================================================
-- IMPORTANT: Only run after confirming new table has all data
-- Uncomment when ready:
-- DROP TABLE IF EXISTS message_read_by;
