-- D3 FIX: Add moderation review history columns to messages table.
-- Preserves original moderation flags while recording review metadata
-- for retrospective moderation analysis and audit trail.

ALTER TABLE messages ADD COLUMN IF NOT EXISTS reviewed_by BIGINT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS review_outcome VARCHAR(20);

-- Index for efficient admin review queue queries (find unreviewed flagged messages)
CREATE INDEX IF NOT EXISTS idx_messages_unreviewed_flags
    ON messages (review_outcome)
    WHERE moderation_flags IS NOT NULL AND review_outcome IS NULL;
