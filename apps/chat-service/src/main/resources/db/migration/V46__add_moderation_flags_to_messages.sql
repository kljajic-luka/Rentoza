-- ============================================================================
-- V46__add_moderation_flags_to_messages.sql
-- Add moderation_flags column to messages table for admin review queue
-- ============================================================================
-- CONTEXT:
-- Content moderation can flag messages that are approved but suspicious
-- (e.g., URL_DETECTED, POSSIBLE_OBFUSCATION). These flags need to be
-- persisted so the admin review queue can surface them.
--
-- Column is nullable — null/empty means no flags.
-- Comma-separated string (e.g., "URL_DETECTED,POSSIBLE_OBFUSCATION").
-- ============================================================================

ALTER TABLE messages ADD COLUMN IF NOT EXISTS moderation_flags VARCHAR(500);

-- Index for admin review queue queries (find flagged messages efficiently)
CREATE INDEX IF NOT EXISTS idx_messages_moderation_flags
    ON messages (moderation_flags)
    WHERE moderation_flags IS NOT NULL;

-- ============================================================================
-- VERIFICATION
-- ============================================================================
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'messages' AND column_name = 'moderation_flags'
    ) THEN
        RAISE EXCEPTION 'Column moderation_flags was not added to messages table';
    END IF;
END $$;
