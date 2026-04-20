ALTER TABLE rental_agreements
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Normalize active workflow rows so runtime-configured deadline derivation,
-- not a baked-in SQL interval, becomes the source of truth across environments.
UPDATE rental_agreements
SET acceptance_deadline_at = NULL
WHERE status IN ('PENDING', 'OWNER_ACCEPTED', 'RENTER_ACCEPTED')
  AND acceptance_deadline_at IS NOT NULL;
