CREATE TABLE IF NOT EXISTS conversation_creation_outbox (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      VARCHAR(64) NOT NULL,
    renter_id       VARCHAR(64) NOT NULL,
    owner_id        VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 5,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conv_outbox_status_next_attempt
    ON conversation_creation_outbox (status, next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_conv_outbox_booking
    ON conversation_creation_outbox (booking_id);