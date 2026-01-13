-- =============================================================================
-- Rentoza Supabase Migration: Core Tables
-- Version: 002
-- Description: Users, Cars, Delivery POIs - Foundation tables with no FK dependencies
-- Depends On: 001_extensions_and_types.sql (all enums, sequences, helper functions)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- TABLE: users
-- Source: User.java, V2-V4 (auth), V33 (date_of_birth)
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id BIGINT PRIMARY KEY DEFAULT nextval('users_id_seq'),
    auth_user_id TEXT UNIQUE,  -- Links to Supabase auth.users
    
    -- Basic info
    email TEXT NOT NULL UNIQUE,
    phone TEXT,
    first_name TEXT,
    last_name TEXT,
    profile_photo_url TEXT,
    date_of_birth DATE,
    country TEXT DEFAULT 'Serbia',
    preferred_language VARCHAR(10) DEFAULT 'sr',
    
    -- Role & verification
    role user_role NOT NULL DEFAULT 'RENTER',
    verification_status user_verification_status DEFAULT 'UNVERIFIED',
    owner_type owner_type,
    
    -- Owner-specific (legal entity)
    company_name TEXT,
    pib_encrypted TEXT,  -- Vault encrypted
    
    -- Renter-specific (PII - encrypted)
    jmbg_encrypted TEXT,  -- Vault encrypted (Serbian national ID)
    driver_license_number_encrypted TEXT,  -- Vault encrypted
    driver_license_expiry DATE,
    driver_license_verified_at TIMESTAMPTZ,
    
    -- Financial
    bank_account_number_encrypted TEXT,  -- Vault encrypted
    stripe_customer_id TEXT,
    stripe_connect_account_id TEXT,
    
    -- Security
    refresh_token_hash TEXT,
    refresh_token_expires_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMPTZ,
    
    -- Status flags
    is_active BOOLEAN DEFAULT TRUE,
    is_banned BOOLEAN DEFAULT FALSE,
    banned_at TIMESTAMPTZ,
    banned_by BIGINT REFERENCES users(id),
    ban_reason TEXT,
    
    -- OAuth
    oauth_provider TEXT,
    oauth_provider_id TEXT,
    
    -- Metadata
    version INT DEFAULT 0,  -- Optimistic locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for users
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_phone ON users(phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_verification_status ON users(verification_status);
CREATE INDEX idx_users_auth_user_id ON users(auth_user_id) WHERE auth_user_id IS NOT NULL;
CREATE INDEX idx_users_stripe_customer ON users(stripe_customer_id) WHERE stripe_customer_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- TABLE: cars
-- Source: Car.java, V23 (geospatial), V29 (version), V38 (booking settings)
-- -----------------------------------------------------------------------------
CREATE TABLE cars (
    id BIGINT PRIMARY KEY DEFAULT nextval('cars_id_seq'),
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Basic info
    make TEXT NOT NULL,
    model TEXT NOT NULL,
    year INT NOT NULL,
    license_plate TEXT NOT NULL,
    vin TEXT,
    color TEXT,
    
    -- Technical specs
    transmission transmission_type NOT NULL DEFAULT 'MANUAL',
    fuel_type fuel_type NOT NULL DEFAULT 'PETROL',
    seats INT NOT NULL DEFAULT 5,
    doors INT DEFAULT 4,
    engine_capacity_cc INT,
    horsepower INT,
    
    -- Location (PostGIS)
    location_address TEXT,
    location_city TEXT,
    location_latitude DOUBLE PRECISION,
    location_longitude DOUBLE PRECISION,
    location_point GEOGRAPHY(POINT, 4326),  -- PostGIS geospatial
    
    -- Pricing
    daily_rate NUMERIC(12, 2) NOT NULL,
    hourly_rate NUMERIC(12, 2),
    weekly_rate NUMERIC(12, 2),
    monthly_rate NUMERIC(12, 2),
    security_deposit NUMERIC(12, 2) DEFAULT 0,
    
    -- Features & Add-ons (JSONB for flexibility)
    features JSONB DEFAULT '[]'::jsonb,  -- ["GPS", "Bluetooth", "AC"]
    add_ons JSONB DEFAULT '[]'::jsonb,   -- [{"name": "Child Seat", "price": 5.00}]
    
    -- Photos (array of URLs)
    photo_urls TEXT[] DEFAULT '{}'::text[],
    main_photo_url TEXT,
    
    -- Availability & Status
    availability_status car_availability_status NOT NULL DEFAULT 'AVAILABLE',
    is_listed BOOLEAN DEFAULT TRUE,
    is_instant_book BOOLEAN DEFAULT FALSE,
    
    -- Booking settings (from V38)
    min_booking_hours INT DEFAULT 4,
    max_booking_days INT DEFAULT 30,
    advance_notice_hours INT DEFAULT 2,
    
    -- Insurance & compliance
    insurance_tier insurance_tier DEFAULT 'BASIC',
    registration_expiry DATE,
    insurance_expiry DATE,
    last_service_date DATE,
    next_service_due DATE,
    
    -- Lockbox for self-service (encrypted)
    lockbox_code_encrypted TEXT,  -- Vault encrypted
    lockbox_location TEXT,
    
    -- Stats
    total_trips INT DEFAULT 0,
    average_rating NUMERIC(3, 2),
    total_reviews INT DEFAULT 0,
    
    -- Metadata
    version INT DEFAULT 0,  -- Optimistic locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for cars
CREATE INDEX idx_cars_owner_id ON cars(owner_id);
CREATE INDEX idx_cars_availability ON cars(availability_status) WHERE is_listed = TRUE;
CREATE INDEX idx_cars_location ON cars USING GIST(location_point);
CREATE INDEX idx_cars_city ON cars(location_city);
CREATE INDEX idx_cars_make_model ON cars(make, model);
CREATE INDEX idx_cars_daily_rate ON cars(daily_rate);
CREATE INDEX idx_cars_transmission ON cars(transmission);
CREATE INDEX idx_cars_fuel_type ON cars(fuel_type);
CREATE INDEX idx_cars_seats ON cars(seats);
CREATE INDEX idx_cars_instant_book ON cars(is_instant_book) WHERE is_instant_book = TRUE;

-- -----------------------------------------------------------------------------
-- TABLE: car_documents
-- Source: CarDocument.java, V30
-- -----------------------------------------------------------------------------
CREATE TABLE car_documents (
    id BIGINT PRIMARY KEY DEFAULT nextval('car_documents_id_seq'),
    car_id BIGINT NOT NULL REFERENCES cars(id) ON DELETE CASCADE,
    
    document_type document_type NOT NULL,
    storage_path TEXT NOT NULL,  -- Supabase Storage path
    original_filename TEXT,
    file_size_bytes BIGINT,
    mime_type TEXT,
    
    -- Verification
    verification_status document_verification_status DEFAULT 'PENDING',
    verified_at TIMESTAMPTZ,
    verified_by BIGINT REFERENCES users(id),
    rejection_reason TEXT,
    
    -- Expiry tracking
    expires_at DATE,
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_car_documents_car_id ON car_documents(car_id);
CREATE INDEX idx_car_documents_type ON car_documents(document_type);
CREATE INDEX idx_car_documents_status ON car_documents(verification_status);
CREATE INDEX idx_car_documents_expiry ON car_documents(expires_at) WHERE expires_at IS NOT NULL;

-- -----------------------------------------------------------------------------
-- TABLE: car_photos
-- For additional car photos with metadata
-- -----------------------------------------------------------------------------
CREATE TABLE car_photos (
    id BIGINT PRIMARY KEY DEFAULT nextval('car_photos_id_seq'),
    car_id BIGINT NOT NULL REFERENCES cars(id) ON DELETE CASCADE,
    
    photo_url TEXT NOT NULL,
    storage_path TEXT,
    is_main BOOLEAN DEFAULT FALSE,
    display_order INT DEFAULT 0,
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_car_photos_car_id ON car_photos(car_id);

-- -----------------------------------------------------------------------------
-- TABLE: delivery_pois
-- Source: DeliveryPoi.java, V24
-- -----------------------------------------------------------------------------
CREATE TABLE delivery_pois (
    id BIGINT PRIMARY KEY DEFAULT nextval('delivery_pois_id_seq'),
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- POI details
    name TEXT NOT NULL,
    address TEXT NOT NULL,
    city TEXT,
    
    -- Location (PostGIS)
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    location_point GEOGRAPHY(POINT, 4326),
    
    -- Pricing
    delivery_fee NUMERIC(10, 2) DEFAULT 0,
    pickup_fee NUMERIC(10, 2) DEFAULT 0,
    
    -- Availability
    is_active BOOLEAN DEFAULT TRUE,
    available_hours JSONB,  -- {"mon": "08:00-20:00", "tue": "08:00-20:00", ...}
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_pois_owner ON delivery_pois(owner_id);
CREATE INDEX idx_delivery_pois_location ON delivery_pois USING GIST(location_point);
CREATE INDEX idx_delivery_pois_city ON delivery_pois(city);
CREATE INDEX idx_delivery_pois_active ON delivery_pois(is_active) WHERE is_active = TRUE;

-- -----------------------------------------------------------------------------
-- TABLE: user_device_tokens
-- Source: UserDeviceToken.java
-- -----------------------------------------------------------------------------
CREATE TABLE user_device_tokens (
    id BIGINT PRIMARY KEY DEFAULT nextval('user_device_tokens_id_seq'),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    device_token TEXT NOT NULL,
    device_type TEXT,  -- 'ios', 'android', 'web'
    device_name TEXT,
    
    is_active BOOLEAN DEFAULT TRUE,
    last_used_at TIMESTAMPTZ,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    UNIQUE(user_id, device_token)
);

CREATE INDEX idx_device_tokens_user ON user_device_tokens(user_id);
CREATE INDEX idx_device_tokens_active ON user_device_tokens(is_active) WHERE is_active = TRUE;

-- -----------------------------------------------------------------------------
-- TABLE: favorites
-- Source: Favorite.java
-- -----------------------------------------------------------------------------
CREATE TABLE favorites (
    id BIGINT PRIMARY KEY DEFAULT nextval('favorites_id_seq'),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    car_id BIGINT NOT NULL REFERENCES cars(id) ON DELETE CASCADE,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    UNIQUE(user_id, car_id)
);

CREATE INDEX idx_favorites_user ON favorites(user_id);
CREATE INDEX idx_favorites_car ON favorites(car_id);

-- -----------------------------------------------------------------------------
-- TRIGGERS: Auto-update updated_at
-- -----------------------------------------------------------------------------
CREATE TRIGGER users_updated_at 
    BEFORE UPDATE ON users 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER cars_updated_at 
    BEFORE UPDATE ON cars 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER car_documents_updated_at 
    BEFORE UPDATE ON car_documents 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER delivery_pois_updated_at 
    BEFORE UPDATE ON delivery_pois 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER user_device_tokens_updated_at 
    BEFORE UPDATE ON user_device_tokens 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- -----------------------------------------------------------------------------
-- TRIGGER: Auto-generate location_point from lat/lng
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION generate_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.location_latitude IS NOT NULL AND NEW.location_longitude IS NOT NULL THEN
        NEW.location_point := ST_SetSRID(
            ST_MakePoint(NEW.location_longitude, NEW.location_latitude),
            4326
        )::geography;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cars_location_point 
    BEFORE INSERT OR UPDATE ON cars 
    FOR EACH ROW EXECUTE FUNCTION generate_location_point();

CREATE OR REPLACE FUNCTION generate_poi_location_point()
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

CREATE TRIGGER delivery_pois_location_point 
    BEFORE INSERT OR UPDATE ON delivery_pois 
    FOR EACH ROW EXECUTE FUNCTION generate_poi_location_point();

-- -----------------------------------------------------------------------------
-- COMMENTS
-- -----------------------------------------------------------------------------
COMMENT ON TABLE users IS 'User accounts - renters, owners, admins';
COMMENT ON TABLE cars IS 'Car listings with geospatial location';
COMMENT ON TABLE car_documents IS 'Car registration, insurance documents';
COMMENT ON TABLE delivery_pois IS 'Pickup/delivery points for owners';
COMMENT ON TABLE user_device_tokens IS 'Push notification tokens';
COMMENT ON TABLE favorites IS 'User favorite cars';

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
    AND table_name IN ('users', 'cars', 'car_documents', 'car_photos', 
                       'delivery_pois', 'user_device_tokens', 'favorites');
    
    RAISE NOTICE '=== 002_core_tables.sql VALIDATION ===';
    RAISE NOTICE 'Core tables created: % (expected: 7)', v_table_count;
    
    IF v_table_count < 7 THEN
        RAISE WARNING 'Missing tables! Expected 7, got %', v_table_count;
    END IF;
END $$;

-- =============================================================================
-- HANDOFF TO 003_booking_system.sql
-- Tables now available: users, cars, car_documents, car_photos, 
--                       delivery_pois, user_device_tokens, favorites
-- =============================================================================
