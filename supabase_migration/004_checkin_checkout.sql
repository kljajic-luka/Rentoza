-- =============================================================================
-- Rentoza Supabase Migration: Check-in/Checkout System
-- Version: 004
-- Description: Check-in events, photos, checkout saga, damage claims
-- Depends On: 003_booking_system.sql (bookings)
-- Source: V14-V16, V20-V21, V26, V36, V40
-- =============================================================================

-- -----------------------------------------------------------------------------
-- TABLE: check_in_events
-- Source: CheckInEvent.java, V14
-- Event sourcing for check-in/checkout workflow
-- -----------------------------------------------------------------------------
CREATE TABLE check_in_events (
    id BIGINT PRIMARY KEY DEFAULT nextval('check_in_events_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Event details
    event_type check_in_event_type NOT NULL,
    actor_id BIGINT REFERENCES users(id),  -- Who triggered the event
    actor_role TEXT,  -- 'host', 'guest', 'system'
    
    -- Event data (flexible JSON for different event types)
    event_data JSONB DEFAULT '{}'::jsonb,
    
    -- Location at time of event
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    location_point GEOGRAPHY(POINT, 4326),
    
    -- Timing
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- For photo events
    photo_count INT,
    
    -- For odometer/fuel events
    odometer_reading INT,
    fuel_level INT,
    
    -- Sequence for ordering
    sequence_number INT,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_check_in_events_booking ON check_in_events(booking_id);
CREATE INDEX idx_check_in_events_type ON check_in_events(event_type);
CREATE INDEX idx_check_in_events_occurred ON check_in_events(occurred_at DESC);
CREATE INDEX idx_check_in_events_actor ON check_in_events(actor_id);

-- -----------------------------------------------------------------------------
-- TABLE: check_in_photos
-- Source: CheckInPhoto.java, V14, V26
-- Host's check-in photos
-- -----------------------------------------------------------------------------
CREATE TABLE check_in_photos (
    id BIGINT PRIMARY KEY DEFAULT nextval('check_in_photos_id_seq'),
    check_in_event_id BIGINT REFERENCES check_in_events(id) ON DELETE CASCADE,
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Photo details
    photo_url TEXT NOT NULL,
    storage_path TEXT,
    category check_in_photo_category NOT NULL DEFAULT 'OTHER',
    
    -- Metadata
    sequence_number INT DEFAULT 0,
    file_size_bytes BIGINT,
    
    -- EXIF data (stripped for privacy, but saved for verification)
    exif_data JSONB,
    taken_at TIMESTAMPTZ,  -- From EXIF if available
    
    -- Verification
    status photo_status DEFAULT 'PENDING',
    verified_at TIMESTAMPTZ,
    verified_by BIGINT REFERENCES users(id),
    rejection_reason photo_rejection_reason,
    rejection_notes TEXT,
    
    -- Who uploaded
    uploaded_by BIGINT REFERENCES users(id),
    uploaded_by_role TEXT,  -- 'host', 'guest'
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_check_in_photos_event ON check_in_photos(check_in_event_id);
CREATE INDEX idx_check_in_photos_booking ON check_in_photos(booking_id);
CREATE INDEX idx_check_in_photos_category ON check_in_photos(category);
CREATE INDEX idx_check_in_photos_status ON check_in_photos(status);

-- -----------------------------------------------------------------------------
-- TABLE: guest_check_in_photos
-- Source: GuestCheckInPhoto.java, V36
-- Guest's check-in photos (for dual-party verification)
-- -----------------------------------------------------------------------------
CREATE TABLE guest_check_in_photos (
    id BIGINT PRIMARY KEY DEFAULT nextval('guest_check_in_photos_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    photo_url TEXT NOT NULL,
    storage_path TEXT,
    category check_in_photo_category NOT NULL DEFAULT 'OTHER',
    
    sequence_number INT DEFAULT 0,
    
    -- EXIF
    exif_data JSONB,
    taken_at TIMESTAMPTZ,
    
    -- Verification
    status photo_status DEFAULT 'PENDING',
    
    -- Discrepancy tracking
    has_discrepancy BOOLEAN DEFAULT FALSE,
    discrepancy_notes TEXT,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_guest_check_in_photos_booking ON guest_check_in_photos(booking_id);
CREATE INDEX idx_guest_check_in_photos_discrepancy ON guest_check_in_photos(has_discrepancy) 
    WHERE has_discrepancy = TRUE;

-- -----------------------------------------------------------------------------
-- TABLE: host_checkout_photos
-- Source: HostCheckoutPhoto.java, V36
-- Host's checkout photos
-- -----------------------------------------------------------------------------
CREATE TABLE host_checkout_photos (
    id BIGINT PRIMARY KEY DEFAULT nextval('host_checkout_photos_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    photo_url TEXT NOT NULL,
    storage_path TEXT,
    category check_in_photo_category NOT NULL DEFAULT 'OTHER',
    
    sequence_number INT DEFAULT 0,
    
    -- EXIF
    exif_data JSONB,
    taken_at TIMESTAMPTZ,
    
    -- Verification
    status photo_status DEFAULT 'PENDING',
    
    -- Damage detection
    has_damage BOOLEAN DEFAULT FALSE,
    damage_description TEXT,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_host_checkout_photos_booking ON host_checkout_photos(booking_id);
CREATE INDEX idx_host_checkout_photos_damage ON host_checkout_photos(has_damage) 
    WHERE has_damage = TRUE;

-- -----------------------------------------------------------------------------
-- TABLE: photo_discrepancies
-- Source: PhotoDiscrepancy.java, V36
-- Tracks differences between host and guest photos
-- -----------------------------------------------------------------------------
CREATE TABLE photo_discrepancies (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    host_photo_id BIGINT REFERENCES check_in_photos(id),
    guest_photo_id BIGINT REFERENCES guest_check_in_photos(id),
    
    category check_in_photo_category,
    discrepancy_type TEXT,  -- 'missing', 'damage_detected', 'mismatch'
    description TEXT,
    
    -- Resolution
    resolved BOOLEAN DEFAULT FALSE,
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT REFERENCES users(id),
    resolution_notes TEXT,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_photo_discrepancies_booking ON photo_discrepancies(booking_id);
CREATE INDEX idx_photo_discrepancies_unresolved ON photo_discrepancies(resolved) 
    WHERE resolved = FALSE;

-- -----------------------------------------------------------------------------
-- TABLE: check_in_id_verification
-- Source: CheckInIdVerification.java, V14
-- ID verification at check-in
-- -----------------------------------------------------------------------------
CREATE TABLE check_in_id_verification (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Verification result
    verified BOOLEAN NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified_by BIGINT REFERENCES users(id),
    
    -- ID document details
    document_type document_type,
    document_number_hash TEXT,  -- Hashed for privacy
    document_expiry DATE,
    
    -- Photo match
    selfie_match_score NUMERIC(5, 2),  -- 0-100
    selfie_url TEXT,
    
    -- Failure reasons
    failure_reason TEXT,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_check_in_id_verification_booking ON check_in_id_verification(booking_id);

-- -----------------------------------------------------------------------------
-- TABLE: checkout_saga_state
-- Source: CheckoutSagaState.java, V21
-- Saga pattern for multi-step checkout
-- -----------------------------------------------------------------------------
CREATE TABLE checkout_saga_state (
    id BIGINT PRIMARY KEY DEFAULT nextval('checkout_saga_state_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE UNIQUE,
    
    -- Saga status
    status checkout_saga_status NOT NULL DEFAULT 'RUNNING',
    current_step checkout_saga_step NOT NULL DEFAULT 'INITIATED',
    
    -- Step tracking
    steps_completed JSONB DEFAULT '[]'::jsonb,  -- Array of completed step names
    last_step_at TIMESTAMPTZ,
    
    -- Retry tracking
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    next_retry_at TIMESTAMPTZ,
    
    -- Compensation
    compensation_started_at TIMESTAMPTZ,
    compensation_reason TEXT,
    
    -- Error tracking
    last_error TEXT,
    error_stack TEXT,
    
    -- Calculated values during saga
    late_fee_minutes INT,
    late_fee_amount NUMERIC(12, 2),
    damage_amount NUMERIC(12, 2),
    fuel_charge NUMERIC(12, 2),
    total_additional_charges NUMERIC(12, 2),
    
    -- Deposit handling
    deposit_action TEXT,  -- 'release', 'capture_partial', 'capture_full'
    deposit_amount_to_capture NUMERIC(12, 2),
    
    -- Host payout
    host_payout_amount NUMERIC(12, 2),
    
    -- Timestamps
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    
    -- Metadata
    version INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_checkout_saga_booking ON checkout_saga_state(booking_id);
CREATE INDEX idx_checkout_saga_status ON checkout_saga_state(status);
CREATE INDEX idx_checkout_saga_retry ON checkout_saga_state(next_retry_at) 
    WHERE status = 'RUNNING' AND next_retry_at IS NOT NULL;

-- -----------------------------------------------------------------------------
-- TABLE: damage_claims
-- Source: DamageClaim.java, V16
-- -----------------------------------------------------------------------------
CREATE TABLE damage_claims (
    id BIGINT PRIMARY KEY DEFAULT nextval('damage_claims_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Claim details
    status damage_claim_status NOT NULL DEFAULT 'PENDING',
    description TEXT NOT NULL,
    
    -- Damage assessment
    damage_category TEXT,  -- 'exterior', 'interior', 'mechanical', 'other'
    severity TEXT,  -- 'minor', 'moderate', 'major'
    
    -- Financial
    estimated_cost NUMERIC(12, 2),
    approved_amount NUMERIC(12, 2),
    deducted_from_deposit NUMERIC(12, 2),
    additional_charge NUMERIC(12, 2),
    
    -- Evidence
    photo_urls TEXT[],
    
    -- Review
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    reviewer_notes TEXT,
    
    -- Claimant
    filed_by BIGINT REFERENCES users(id),
    filed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Resolution
    resolved_at TIMESTAMPTZ,
    resolution_notes TEXT,
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_damage_claims_booking ON damage_claims(booking_id);
CREATE INDEX idx_damage_claims_status ON damage_claims(status);
CREATE INDEX idx_damage_claims_filed_by ON damage_claims(filed_by);

-- -----------------------------------------------------------------------------
-- VIEW: check_in_status_view (CQRS)
-- Source: CheckInStatusView.java, V20
-- Materialized view for fast check-in status queries
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW check_in_status_view AS
SELECT 
    b.id as booking_id,
    b.booking_number,
    b.status as booking_status,
    b.car_id,
    b.renter_id,
    c.owner_id,
    
    -- Check-in status
    b.check_in_opened_at,
    b.check_in_completed_at,
    
    -- Latest events
    (
        SELECT MAX(occurred_at) 
        FROM check_in_events ce 
        WHERE ce.booking_id = b.id 
        AND ce.event_type = 'HOST_ARRIVED'
    ) as host_arrived_at,
    (
        SELECT MAX(occurred_at) 
        FROM check_in_events ce 
        WHERE ce.booking_id = b.id 
        AND ce.event_type = 'GUEST_ARRIVED'
    ) as guest_arrived_at,
    
    -- Photo counts
    (SELECT COUNT(*) FROM check_in_photos cp WHERE cp.booking_id = b.id) as host_photo_count,
    (SELECT COUNT(*) FROM guest_check_in_photos gcp WHERE gcp.booking_id = b.id) as guest_photo_count,
    
    -- Verification
    (
        SELECT verified 
        FROM check_in_id_verification civ 
        WHERE civ.booking_id = b.id 
        ORDER BY created_at DESC 
        LIMIT 1
    ) as id_verified,
    
    -- Odometer/Fuel
    b.start_odometer,
    b.start_fuel_level,
    
    -- Checkout status
    b.checkout_opened_at,
    b.checkout_completed_at,
    b.end_odometer,
    b.end_fuel_level

FROM bookings b
JOIN cars c ON b.car_id = c.id;

-- -----------------------------------------------------------------------------
-- TRIGGERS
-- -----------------------------------------------------------------------------
CREATE TRIGGER check_in_photos_updated_at 
    BEFORE UPDATE ON check_in_photos 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER guest_check_in_photos_updated_at 
    BEFORE UPDATE ON guest_check_in_photos 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER host_checkout_photos_updated_at 
    BEFORE UPDATE ON host_checkout_photos 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER checkout_saga_state_updated_at 
    BEFORE UPDATE ON checkout_saga_state 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER damage_claims_updated_at 
    BEFORE UPDATE ON damage_claims 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Auto-generate location point for events
CREATE OR REPLACE FUNCTION generate_event_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL THEN
        NEW.location_point := ST_SetSRID(
            ST_MakePoint(NEW.longitude, NEW.latitude),
            4326
        )::geography;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER check_in_events_location_point 
    BEFORE INSERT OR UPDATE ON check_in_events 
    FOR EACH ROW EXECUTE FUNCTION generate_event_location_point();

-- -----------------------------------------------------------------------------
-- COMMENTS
-- -----------------------------------------------------------------------------
COMMENT ON TABLE check_in_events IS 'Event sourcing for check-in/checkout workflow';
COMMENT ON TABLE check_in_photos IS 'Host photos taken during check-in';
COMMENT ON TABLE guest_check_in_photos IS 'Guest photos for dual-party verification';
COMMENT ON TABLE host_checkout_photos IS 'Host photos at checkout';
COMMENT ON TABLE photo_discrepancies IS 'Differences between host/guest photos';
COMMENT ON TABLE checkout_saga_state IS 'Saga pattern state for multi-step checkout';
COMMENT ON TABLE damage_claims IS 'Damage claims filed during/after trip';
COMMENT ON VIEW check_in_status_view IS 'CQRS view for fast check-in status queries';

-- =============================================================================
-- VALIDATION
-- =============================================================================
DO $$
DECLARE
    v_table_count INT;
BEGIN
    SELECT COUNT(*) INTO v_table_count
    FROM information_schema.tables 
    WHERE table_schema = 'public' 
    AND table_type = 'BASE TABLE'
    AND table_name IN ('check_in_events', 'check_in_photos', 'guest_check_in_photos',
                       'host_checkout_photos', 'photo_discrepancies', 'check_in_id_verification',
                       'checkout_saga_state', 'damage_claims');
    
    RAISE NOTICE '=== 004_checkin_checkout.sql VALIDATION ===';
    RAISE NOTICE 'Check-in/checkout tables created: % (expected: 8)', v_table_count;
END $$;

-- =============================================================================
-- HANDOFF TO 005_supporting.sql
-- =============================================================================
