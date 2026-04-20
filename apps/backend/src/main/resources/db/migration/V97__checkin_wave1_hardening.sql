-- V97: Wave 1 check-in hardening foundations
-- - Canonical UTC booking trip times
-- - Immutable check-in attestation persistence
-- - Server-side rejection budget persistence
-- - Expand check_in_events.event_type to support new immutable event names

-- -----------------------------------------------------------------------------
-- Booking canonical UTC fields (dual-write transition)
-- -----------------------------------------------------------------------------
ALTER TABLE bookings
    ADD COLUMN start_time_utc TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN end_time_utc TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN check_in_admin_override_at TIMESTAMP WITH TIME ZONE NULL;

-- Backfill by interpreting legacy local timestamps as Europe/Belgrade wall-clock time.
UPDATE bookings
SET start_time_utc = (start_time AT TIME ZONE 'Europe/Belgrade'),
    end_time_utc = (end_time AT TIME ZONE 'Europe/Belgrade')
WHERE start_time_utc IS NULL
   OR end_time_utc IS NULL;

ALTER TABLE bookings
    ALTER COLUMN start_time_utc SET NOT NULL,
    ALTER COLUMN end_time_utc SET NOT NULL;

CREATE INDEX idx_bookings_start_time_utc ON bookings(start_time_utc);
CREATE INDEX idx_bookings_end_time_utc ON bookings(end_time_utc);

-- -----------------------------------------------------------------------------
-- Attestation persistence
-- -----------------------------------------------------------------------------
CREATE TABLE check_in_attestations (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    check_in_session_id VARCHAR(36) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    artifact_storage_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_checkin_attestation_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id),
    CONSTRAINT uq_checkin_attestation_session UNIQUE (check_in_session_id)
);

CREATE INDEX idx_checkin_attestation_created_at ON check_in_attestations(created_at);
CREATE INDEX idx_checkin_attestation_booking_session ON check_in_attestations(booking_id, check_in_session_id);

ALTER TABLE guest_check_in_photos
    ADD COLUMN image_hash VARCHAR(128) NULL;

-- -----------------------------------------------------------------------------
-- Server-side rejection budgets/cooldowns
-- -----------------------------------------------------------------------------
CREATE TABLE photo_rejection_budgets (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    actor_role VARCHAR(20) NOT NULL,
    photo_type VARCHAR(64) NOT NULL,
    ip_address_hash VARCHAR(64) NOT NULL,
    device_fingerprint_hash VARCHAR(64) NOT NULL,
    rejection_count INT NOT NULL,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cooldown_until TIMESTAMP WITH TIME ZONE NULL,
    last_rejection_code VARCHAR(64) NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_photo_rejection_budget_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id),
    CONSTRAINT fk_photo_rejection_budget_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_photo_rejection_budget_actor_scope
        UNIQUE (booking_id, user_id, actor_role, photo_type, ip_address_hash, device_fingerprint_hash)
);

CREATE INDEX idx_photo_rejection_budget_cooldown ON photo_rejection_budgets(cooldown_until);

CREATE OR REPLACE FUNCTION set_photo_rejection_budgets_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_photo_rejection_budgets_updated_at ON photo_rejection_budgets;
CREATE TRIGGER trg_photo_rejection_budgets_updated_at
    BEFORE UPDATE ON photo_rejection_budgets
    FOR EACH ROW
    EXECUTE FUNCTION set_photo_rejection_budgets_updated_at();

-- -----------------------------------------------------------------------------
-- Allow new immutable check-in event names without future enum table rewrites
-- -----------------------------------------------------------------------------
ALTER TABLE check_in_events
    ALTER COLUMN event_type TYPE VARCHAR(100);
