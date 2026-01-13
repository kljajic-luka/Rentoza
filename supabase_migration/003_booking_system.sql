-- =============================================================================
-- Rentoza Supabase Migration: Booking System
-- Version: 003
-- Description: Bookings, payments, cancellations, trip extensions, blocked dates
-- Depends On: 002_core_tables.sql (users, cars)
-- Source: V5-V13 (booking system), V17 (extensions), V22 (deposit)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- TABLE: bookings
-- Source: Booking.java, V5-V13
-- -----------------------------------------------------------------------------
CREATE TABLE bookings (
    id BIGINT PRIMARY KEY DEFAULT nextval('bookings_id_seq'),
    booking_number VARCHAR(20) UNIQUE NOT NULL,
    
    -- Parties
    car_id BIGINT NOT NULL REFERENCES cars(id),
    renter_id BIGINT NOT NULL REFERENCES users(id),
    
    -- Time period
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    start_time TIME,
    end_time TIME,
    
    -- Timestamps (exact, from V18)
    actual_start_at TIMESTAMPTZ,
    actual_end_at TIMESTAMPTZ,
    expected_end_at TIMESTAMPTZ,
    
    -- Location (PostGIS)
    pickup_location_address TEXT,
    pickup_location_latitude DOUBLE PRECISION,
    pickup_location_longitude DOUBLE PRECISION,
    pickup_location_point GEOGRAPHY(POINT, 4326),
    
    return_location_address TEXT,
    return_location_latitude DOUBLE PRECISION,
    return_location_longitude DOUBLE PRECISION,
    return_location_point GEOGRAPHY(POINT, 4326),
    
    -- Delivery POI references
    pickup_poi_id BIGINT REFERENCES delivery_pois(id),
    return_poi_id BIGINT REFERENCES delivery_pois(id),
    
    -- Status tracking
    status booking_status NOT NULL DEFAULT 'PENDING_APPROVAL',
    host_approval_status host_approval_status DEFAULT 'PENDING',
    host_approved_at TIMESTAMPTZ,
    host_declined_at TIMESTAMPTZ,
    host_decline_reason TEXT,
    
    -- Pricing
    daily_rate NUMERIC(12, 2) NOT NULL,
    total_days INT,
    subtotal NUMERIC(12, 2),
    service_fee NUMERIC(12, 2) DEFAULT 0,
    delivery_fee NUMERIC(12, 2) DEFAULT 0,
    total_price NUMERIC(12, 2) NOT NULL,
    
    -- Security deposit (from V22)
    security_deposit NUMERIC(12, 2) DEFAULT 0,
    deposit_captured_amount NUMERIC(12, 2),
    deposit_captured_at TIMESTAMPTZ,
    deposit_released_at TIMESTAMPTZ,
    deposit_deducted_amount NUMERIC(12, 2),
    
    -- Insurance
    insurance_tier insurance_tier DEFAULT 'BASIC',
    insurance_fee NUMERIC(12, 2) DEFAULT 0,
    
    -- Add-ons selected
    selected_add_ons JSONB DEFAULT '[]'::jsonb,
    add_ons_total NUMERIC(12, 2) DEFAULT 0,
    
    -- Cancellation (from V11-V12)
    cancellation_initiator cancellation_initiator,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason TEXT,
    cancellation_charges NUMERIC(12, 2) DEFAULT 0,
    refund_amount NUMERIC(12, 2),
    refund_processed_at TIMESTAMPTZ,
    
    -- Late fees (from V34)
    late_return_minutes INT,
    late_fee_amount NUMERIC(12, 2),
    
    -- Check-in/Checkout references
    check_in_opened_at TIMESTAMPTZ,
    check_in_completed_at TIMESTAMPTZ,
    checkout_opened_at TIMESTAMPTZ,
    checkout_completed_at TIMESTAMPTZ,
    
    -- Trip state
    is_extended BOOLEAN DEFAULT FALSE,
    original_end_date DATE,
    
    -- Odometer (check-in/checkout)
    start_odometer INT,
    end_odometer INT,
    total_distance_km INT,
    
    -- Fuel levels
    start_fuel_level INT,  -- Percentage 0-100
    end_fuel_level INT,
    
    -- Renter notes
    renter_notes TEXT,
    owner_notes TEXT,
    
    -- Payment
    payment_intent_id TEXT,
    payment_status payment_status DEFAULT 'PENDING',
    
    -- Host payout
    host_payout_amount NUMERIC(12, 2),
    host_payout_status payout_status,
    host_payout_scheduled_at TIMESTAMPTZ,
    host_payout_completed_at TIMESTAMPTZ,
    
    -- Metadata
    version INT DEFAULT 0,  -- Optimistic locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT valid_date_range CHECK (end_date >= start_date),
    CONSTRAINT valid_odometer CHECK (end_odometer IS NULL OR end_odometer >= start_odometer)
);

-- Critical index for conflict detection (from V10, V13)
CREATE INDEX idx_booking_conflict_detection ON bookings(
    car_id,
    start_date,
    end_date,
    status
) WHERE status IN (
    'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 
    'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_GUEST_COMPLETE', 'CHECK_IN_COMPLETE',
    'IN_TRIP', 
    'CHECKOUT_OPEN', 'CHECKOUT_HOST_COMPLETE', 'CHECKOUT_GUEST_COMPLETE'
);

-- Other booking indexes
CREATE INDEX idx_bookings_renter ON bookings(renter_id);
CREATE INDEX idx_bookings_car ON bookings(car_id);
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_dates ON bookings(start_date, end_date);
CREATE INDEX idx_bookings_created ON bookings(created_at DESC);
CREATE INDEX idx_bookings_number ON bookings(booking_number);
CREATE INDEX idx_bookings_payment_intent ON bookings(payment_intent_id) WHERE payment_intent_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- TABLE: booking_payments
-- Payment transactions for bookings
-- -----------------------------------------------------------------------------
CREATE TABLE booking_payments (
    id BIGINT PRIMARY KEY DEFAULT nextval('booking_payments_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Stripe
    stripe_payment_intent_id TEXT,
    stripe_charge_id TEXT,
    stripe_refund_id TEXT,
    
    -- Amount
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'RSD',
    
    -- Type & Status
    payment_type TEXT NOT NULL,  -- 'rental', 'deposit', 'damage', 'late_fee', 'refund'
    status payment_status NOT NULL DEFAULT 'PENDING',
    
    -- Processing
    processed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_reason TEXT,
    
    -- Metadata
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_booking_payments_booking ON booking_payments(booking_id);
CREATE INDEX idx_booking_payments_status ON booking_payments(status);
CREATE INDEX idx_booking_payments_stripe ON booking_payments(stripe_payment_intent_id);

-- -----------------------------------------------------------------------------
-- TABLE: cancellation_records
-- Source: CancellationRecord.java, V11-V12
-- -----------------------------------------------------------------------------
CREATE TABLE cancellation_records (
    id BIGINT PRIMARY KEY DEFAULT nextval('cancellation_records_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Who cancelled
    initiated_by cancellation_initiator NOT NULL,
    initiated_by_user_id BIGINT REFERENCES users(id),
    
    -- Reason
    reason TEXT,
    reason_category TEXT,  -- 'emergency', 'schedule_change', 'vehicle_issue', 'other'
    
    -- Financial impact
    cancellation_fee NUMERIC(12, 2) DEFAULT 0,
    refund_amount NUMERIC(12, 2) DEFAULT 0,
    refund_percentage NUMERIC(5, 2),
    
    -- Policy applied
    hours_before_start INT,
    policy_tier TEXT,  -- 'flexible', 'moderate', 'strict'
    
    -- Processing
    processed_at TIMESTAMPTZ,
    refund_processed BOOLEAN DEFAULT FALSE,
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cancellation_records_booking ON cancellation_records(booking_id);
CREATE INDEX idx_cancellation_records_initiator ON cancellation_records(initiated_by);

-- -----------------------------------------------------------------------------
-- TABLE: host_cancellation_stats
-- Source: HostCancellationStats.java
-- -----------------------------------------------------------------------------
CREATE TABLE host_cancellation_stats (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    
    total_cancellations INT DEFAULT 0,
    cancellations_last_30_days INT DEFAULT 0,
    cancellations_last_90_days INT DEFAULT 0,
    
    last_cancellation_at TIMESTAMPTZ,
    penalty_level INT DEFAULT 0,  -- 0=none, 1=warning, 2=restricted, 3=suspended
    
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_host_cancellation_stats_owner ON host_cancellation_stats(owner_id);

-- -----------------------------------------------------------------------------
-- TABLE: trip_extensions
-- Source: TripExtension.java, V17
-- -----------------------------------------------------------------------------
CREATE TABLE trip_extensions (
    id BIGINT PRIMARY KEY DEFAULT nextval('trip_extensions_id_seq'),
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    
    -- Extension details
    original_end_date DATE NOT NULL,
    requested_end_date DATE NOT NULL,
    extension_days INT NOT NULL,
    
    -- Pricing
    additional_amount NUMERIC(12, 2) NOT NULL,
    daily_rate NUMERIC(12, 2) NOT NULL,
    
    -- Status
    status trip_extension_status NOT NULL DEFAULT 'PENDING',
    
    -- Approval
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at TIMESTAMPTZ,
    approved_by BIGINT REFERENCES users(id),
    decline_reason TEXT,
    
    -- Payment
    payment_status payment_status DEFAULT 'PENDING',
    payment_captured_at TIMESTAMPTZ,
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trip_extensions_booking ON trip_extensions(booking_id);
CREATE INDEX idx_trip_extensions_status ON trip_extensions(status);

-- -----------------------------------------------------------------------------
-- TABLE: blocked_dates
-- For owner-blocked availability
-- -----------------------------------------------------------------------------
CREATE TABLE blocked_dates (
    id BIGINT PRIMARY KEY DEFAULT nextval('blocked_dates_id_seq'),
    car_id BIGINT NOT NULL REFERENCES cars(id) ON DELETE CASCADE,
    
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    
    reason blocked_date_reason NOT NULL DEFAULT 'OWNER_BLOCKED',
    notes TEXT,
    
    -- If blocked due to booking
    booking_id BIGINT REFERENCES bookings(id) ON DELETE SET NULL,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT valid_blocked_range CHECK (end_date >= start_date)
);

CREATE INDEX idx_blocked_dates_car ON blocked_dates(car_id);
CREATE INDEX idx_blocked_dates_range ON blocked_dates(car_id, start_date, end_date);

-- -----------------------------------------------------------------------------
-- TRIGGERS
-- -----------------------------------------------------------------------------
CREATE TRIGGER bookings_updated_at 
    BEFORE UPDATE ON bookings 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER booking_payments_updated_at 
    BEFORE UPDATE ON booking_payments 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trip_extensions_updated_at 
    BEFORE UPDATE ON trip_extensions 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Generate booking number
CREATE OR REPLACE FUNCTION generate_booking_number()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.booking_number IS NULL THEN
        NEW.booking_number := 'RNT-' || TO_CHAR(NOW(), 'YYYYMMDD') || '-' || 
                              LPAD(NEW.id::TEXT, 6, '0');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_generate_number 
    BEFORE INSERT ON bookings 
    FOR EACH ROW EXECUTE FUNCTION generate_booking_number();

-- Auto-generate location points for bookings
CREATE OR REPLACE FUNCTION generate_booking_location_points()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.pickup_location_latitude IS NOT NULL AND NEW.pickup_location_longitude IS NOT NULL THEN
        NEW.pickup_location_point := ST_SetSRID(
            ST_MakePoint(NEW.pickup_location_longitude, NEW.pickup_location_latitude),
            4326
        )::geography;
    END IF;
    
    IF NEW.return_location_latitude IS NOT NULL AND NEW.return_location_longitude IS NOT NULL THEN
        NEW.return_location_point := ST_SetSRID(
            ST_MakePoint(NEW.return_location_longitude, NEW.return_location_latitude),
            4326
        )::geography;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_location_points 
    BEFORE INSERT OR UPDATE ON bookings 
    FOR EACH ROW EXECUTE FUNCTION generate_booking_location_points();

-- -----------------------------------------------------------------------------
-- FUNCTION: Check for booking conflicts
-- Source: V10, V13 (overlap detection)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_booking_conflicts(
    p_car_id BIGINT,
    p_start_date DATE,
    p_end_date DATE,
    p_exclude_booking_id BIGINT DEFAULT NULL
)
RETURNS TABLE (
    conflicting_booking_id BIGINT,
    conflicting_status booking_status,
    conflict_start DATE,
    conflict_end DATE
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        b.id,
        b.status,
        b.start_date,
        b.end_date
    FROM bookings b
    WHERE b.car_id = p_car_id
    AND b.status IN (
        'PENDING_APPROVAL', 'APPROVED', 'ACTIVE',
        'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_GUEST_COMPLETE', 'CHECK_IN_COMPLETE',
        'IN_TRIP',
        'CHECKOUT_OPEN', 'CHECKOUT_HOST_COMPLETE', 'CHECKOUT_GUEST_COMPLETE'
    )
    AND (p_exclude_booking_id IS NULL OR b.id != p_exclude_booking_id)
    AND (
        (b.start_date <= p_end_date AND b.end_date >= p_start_date)
    );
END;
$$ LANGUAGE plpgsql STABLE;

-- -----------------------------------------------------------------------------
-- COMMENTS
-- -----------------------------------------------------------------------------
COMMENT ON TABLE bookings IS 'Car rental bookings with full lifecycle tracking';
COMMENT ON TABLE booking_payments IS 'Payment transactions (Stripe integration)';
COMMENT ON TABLE cancellation_records IS 'Booking cancellation history with fee tracking';
COMMENT ON TABLE trip_extensions IS 'Requests to extend booking duration';
COMMENT ON TABLE blocked_dates IS 'Owner-blocked dates for car availability';

COMMENT ON INDEX idx_booking_conflict_detection IS 'Critical index for O(1) conflict detection';
COMMENT ON FUNCTION check_booking_conflicts IS 'Detect overlapping bookings for a car';

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
    AND table_name IN ('bookings', 'booking_payments', 'cancellation_records',
                       'host_cancellation_stats', 'trip_extensions', 'blocked_dates');
    
    RAISE NOTICE '=== 003_booking_system.sql VALIDATION ===';
    RAISE NOTICE 'Booking tables created: % (expected: 6)', v_table_count;
END $$;

-- =============================================================================
-- HANDOFF TO 004_checkin_checkout.sql
-- =============================================================================
