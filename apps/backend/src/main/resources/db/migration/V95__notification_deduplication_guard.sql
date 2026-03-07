WITH ranked_notifications AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY type, related_entity_id, recipient_id
               ORDER BY created_at DESC, id DESC
           ) AS duplicate_rank
    FROM notifications
    WHERE related_entity_id IS NOT NULL
      AND deleted_at IS NULL
)
UPDATE notifications
SET deleted_at = NOW()
WHERE id IN (
    SELECT id
    FROM ranked_notifications
    WHERE duplicate_rank > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_notifications_type_related_recipient
    ON notifications (type, related_entity_id, recipient_id)
    WHERE related_entity_id IS NOT NULL
      AND deleted_at IS NULL;