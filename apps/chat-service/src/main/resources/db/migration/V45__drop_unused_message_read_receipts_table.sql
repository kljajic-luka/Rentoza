-- ============================================================================
-- V45__drop_unused_message_read_receipts_table.sql
-- Cleanup: Remove unused message_read_receipts table
-- ============================================================================
-- CONTEXT:
-- The codebase has TWO read receipt tables:
-- 1. message_read_by (message_id, user_id, read_at) - ACTIVE, per-message tracking
-- 2. message_read_receipts (id, message_id, user_id, read_at) - UNUSED
--
-- The active code uses message_read_by (via MessageReadReceipt entity).
-- The message_read_receipts table was created but never used.
-- ============================================================================

-- Drop the unused table
DROP TABLE IF EXISTS message_read_receipts CASCADE;

-- Drop associated indexes (if not already dropped by CASCADE)
DROP INDEX IF EXISTS idx_read_receipts_message;
DROP INDEX IF EXISTS idx_read_receipts_user;
DROP INDEX IF EXISTS idx_read_receipts_message_user;

-- ============================================================================
-- VERIFICATION
-- ============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'message_read_receipts') THEN
        RAISE EXCEPTION 'Table message_read_receipts still exists after DROP';
    END IF;
    
    RAISE NOTICE '✅ V45: Unused message_read_receipts table dropped successfully';
END;
$$;
