ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_notification_deleted
    ON notifications (deleted_at)
    WHERE deleted_at IS NOT NULL;