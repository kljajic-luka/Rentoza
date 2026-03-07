ALTER TABLE provider_events
ADD COLUMN IF NOT EXISTS replay_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE provider_events
ADD COLUMN IF NOT EXISTS dead_lettered BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_pe_dead_lettered_processed_at
    ON provider_events(dead_lettered, processed_at);
