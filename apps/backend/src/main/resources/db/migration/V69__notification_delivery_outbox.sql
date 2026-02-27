-- C1 FIX: Notification delivery outbox table for durable email/push delivery.
-- Part of the transactional outbox pattern — entries are written in the same transaction
-- as the notification and retried by NotificationDeliveryRetryWorker.

CREATE TABLE IF NOT EXISTS notification_delivery_outbox (
    id              BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    channel_name    VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    delivered_at    TIMESTAMP WITH TIME ZONE
);

-- Index for the retry worker: find PENDING entries ready for attempt
CREATE INDEX IF NOT EXISTS idx_outbox_status_next_attempt
    ON notification_delivery_outbox (status, next_attempt_at)
    WHERE status = 'PENDING';

-- Index for querying delivery status of a specific notification
CREATE INDEX IF NOT EXISTS idx_outbox_notification
    ON notification_delivery_outbox (notification_id);

-- Index for dead-letter monitoring and alerting
CREATE INDEX IF NOT EXISTS idx_outbox_dead_letter
    ON notification_delivery_outbox (status)
    WHERE status = 'DEAD_LETTER';
