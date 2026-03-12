ALTER TABLE rental_agreements
    ADD COLUMN IF NOT EXISTS acceptance_deadline_at TIMESTAMP WITHOUT TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS required_next_actor VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS expired_due_to_actor VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS expired_reason VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS settlement_policy_applied VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS settlement_record_id BIGINT NULL;

UPDATE rental_agreements
SET required_next_actor = CASE
    WHEN owner_accepted_at IS NOT NULL AND renter_accepted_at IS NOT NULL THEN 'NONE'
    WHEN owner_accepted_at IS NULL AND renter_accepted_at IS NULL THEN 'BOTH'
    WHEN owner_accepted_at IS NULL THEN 'OWNER'
    ELSE 'RENTER'
END
WHERE required_next_actor IS NULL;

CREATE INDEX IF NOT EXISTS idx_rental_agreement_deadline_status
    ON rental_agreements(status, acceptance_deadline_at);