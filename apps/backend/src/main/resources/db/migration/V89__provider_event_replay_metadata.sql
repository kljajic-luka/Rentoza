ALTER TABLE provider_events
ADD COLUMN IF NOT EXISTS provider_authorization_id VARCHAR(150);

CREATE INDEX IF NOT EXISTS idx_pe_provider_authorization_id
    ON provider_events(provider_authorization_id);