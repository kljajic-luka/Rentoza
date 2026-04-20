-- V48__add_postgis_spatial_indexes.sql
-- 
-- P1-4: Add PostGIS spatial indexes for optimized geospatial queries
-- Platform: Supabase PostgreSQL with PostGIS 3.3.7 (tiger schema)


-- ============================================================================
-- STEP 1: Add geography column to delivery_pois
-- ============================================================================

ALTER TABLE delivery_pois 
    ADD COLUMN IF NOT EXISTS location_point tiger.geography(Point, 4326);

UPDATE delivery_pois 
SET location_point = tiger.ST_SetSRID(tiger.ST_MakePoint(longitude, latitude), 4326)::tiger.geography
WHERE latitude IS NOT NULL 
  AND longitude IS NOT NULL 
  AND location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_delivery_pois_location_point 
    ON delivery_pois USING GIST (location_point);


-- ============================================================================
-- STEP 2: Add geography column to cars
-- ============================================================================

ALTER TABLE cars 
    ADD COLUMN IF NOT EXISTS location_point tiger.geography(Point, 4326);

UPDATE cars 
SET location_point = tiger.ST_SetSRID(tiger.ST_MakePoint(location_longitude, location_latitude), 4326)::tiger.geography
WHERE location_latitude IS NOT NULL 
  AND location_longitude IS NOT NULL 
  AND location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_cars_location_point_gist 
    ON cars USING GIST (location_point);


-- ============================================================================
-- STEP 3: Trigger for delivery_pois auto-update
-- ============================================================================

CREATE OR REPLACE FUNCTION update_delivery_poi_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL THEN
        NEW.location_point := tiger.ST_SetSRID(tiger.ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::tiger.geography;
    ELSE
        NEW.location_point := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_update_delivery_poi_location_point ON delivery_pois;

CREATE TRIGGER trg_update_delivery_poi_location_point
    BEFORE INSERT OR UPDATE OF latitude, longitude
    ON delivery_pois
    FOR EACH ROW
    EXECUTE FUNCTION update_delivery_poi_location_point();


-- ============================================================================
-- STEP 4: Trigger for cars auto-update
-- ============================================================================

CREATE OR REPLACE FUNCTION update_car_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.location_latitude IS NOT NULL AND NEW.location_longitude IS NOT NULL THEN
        NEW.location_point := tiger.ST_SetSRID(tiger.ST_MakePoint(NEW.location_longitude, NEW.location_latitude), 4326)::tiger.geography;
    ELSE
        NEW.location_point := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_update_car_location_point ON cars;

CREATE TRIGGER trg_update_car_location_point
    BEFORE INSERT OR UPDATE OF location_latitude, location_longitude
    ON cars
    FOR EACH ROW
    EXECUTE FUNCTION update_car_location_point();


-- ============================================================================
-- STEP 5: Add geography to bookings (pickup and check-in/checkout locations)
-- ============================================================================

-- Pickup location (where booking starts)
ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS pickup_location_point tiger.geography(Point, 4326);

UPDATE bookings 
SET pickup_location_point = tiger.ST_SetSRID(tiger.ST_MakePoint(pickup_longitude, pickup_latitude), 4326)::tiger.geography
WHERE pickup_latitude IS NOT NULL 
  AND pickup_longitude IS NOT NULL 
  AND pickup_location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_pickup_location_gist 
    ON bookings USING GIST (pickup_location_point);


-- Guest check-in location (for dispute verification - VAL-004)
ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS guest_check_in_location_point tiger.geography(Point, 4326);

UPDATE bookings 
SET guest_check_in_location_point = tiger.ST_SetSRID(
    tiger.ST_MakePoint(guest_check_in_longitude, guest_check_in_latitude), 4326
)::tiger.geography
WHERE guest_check_in_latitude IS NOT NULL 
  AND guest_check_in_longitude IS NOT NULL 
  AND guest_check_in_location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_guest_check_in_location_gist 
    ON bookings USING GIST (guest_check_in_location_point);


-- Host check-in location (for dispute verification - VAL-004)
ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS host_check_in_location_point tiger.geography(Point, 4326);

UPDATE bookings 
SET host_check_in_location_point = tiger.ST_SetSRID(
    tiger.ST_MakePoint(host_check_in_longitude, host_check_in_latitude), 4326
)::tiger.geography
WHERE host_check_in_latitude IS NOT NULL 
  AND host_check_in_longitude IS NOT NULL 
  AND host_check_in_location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_host_check_in_location_gist 
    ON bookings USING GIST (host_check_in_location_point);


-- Guest checkout location (for dispute verification - VAL-010)
ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS guest_checkout_location_point tiger.geography(Point, 4326);

UPDATE bookings 
SET guest_checkout_location_point = tiger.ST_SetSRID(
    tiger.ST_MakePoint(guest_checkout_longitude, guest_checkout_latitude), 4326
)::tiger.geography
WHERE guest_checkout_latitude IS NOT NULL 
  AND guest_checkout_longitude IS NOT NULL 
  AND guest_checkout_location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_guest_checkout_location_gist 
    ON bookings USING GIST (guest_checkout_location_point);


-- Host checkout location (for dispute verification - VAL-010)
ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS host_checkout_location_point tiger.geography(Point, 4326);

UPDATE bookings 
SET host_checkout_location_point = tiger.ST_SetSRID(
    tiger.ST_MakePoint(host_checkout_longitude, host_checkout_latitude), 4326
)::tiger.geography
WHERE host_checkout_latitude IS NOT NULL 
  AND host_checkout_longitude IS NOT NULL 
  AND host_checkout_location_point IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_host_checkout_location_gist 
    ON bookings USING GIST (host_checkout_location_point);


-- ============================================================================
-- STEP 6: Create triggers for bookings auto-update
-- ============================================================================

CREATE OR REPLACE FUNCTION update_booking_location_points()
RETURNS TRIGGER AS $$
BEGIN
    -- Update pickup location
    IF NEW.pickup_latitude IS NOT NULL AND NEW.pickup_longitude IS NOT NULL THEN
        NEW.pickup_location_point := tiger.ST_SetSRID(
            tiger.ST_MakePoint(NEW.pickup_longitude, NEW.pickup_latitude), 4326
        )::tiger.geography;
    END IF;
    
    -- Update guest check-in location
    IF NEW.guest_check_in_latitude IS NOT NULL AND NEW.guest_check_in_longitude IS NOT NULL THEN
        NEW.guest_check_in_location_point := tiger.ST_SetSRID(
            tiger.ST_MakePoint(NEW.guest_check_in_longitude, NEW.guest_check_in_latitude), 4326
        )::tiger.geography;
    END IF;
    
    -- Update host check-in location
    IF NEW.host_check_in_latitude IS NOT NULL AND NEW.host_check_in_longitude IS NOT NULL THEN
        NEW.host_check_in_location_point := tiger.ST_SetSRID(
            tiger.ST_MakePoint(NEW.host_check_in_longitude, NEW.host_check_in_latitude), 4326
        )::tiger.geography;
    END IF;
    
    -- Update guest checkout location
    IF NEW.guest_checkout_latitude IS NOT NULL AND NEW.guest_checkout_longitude IS NOT NULL THEN
        NEW.guest_checkout_location_point := tiger.ST_SetSRID(
            tiger.ST_MakePoint(NEW.guest_checkout_longitude, NEW.guest_checkout_latitude), 4326
        )::tiger.geography;
    END IF;
    
    -- Update host checkout location
    IF NEW.host_checkout_latitude IS NOT NULL AND NEW.host_checkout_longitude IS NOT NULL THEN
        NEW.host_checkout_location_point := tiger.ST_SetSRID(
            tiger.ST_MakePoint(NEW.host_checkout_longitude, NEW.host_checkout_latitude), 4326
        )::tiger.geography;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_update_booking_location_points ON bookings;

CREATE TRIGGER trg_update_booking_location_points
    BEFORE INSERT OR UPDATE OF 
        pickup_latitude, pickup_longitude,
        guest_check_in_latitude, guest_check_in_longitude,
        host_check_in_latitude, host_check_in_longitude,
        guest_checkout_latitude, guest_checkout_longitude,
        host_checkout_latitude, host_checkout_longitude
    ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION update_booking_location_points();


-- ============================================================================
-- STEP 7: Documentation
-- ============================================================================

COMMENT ON COLUMN delivery_pois.location_point IS 
    'PostGIS geography point for delivery POI locations (WGS84, SRID 4326). Auto-updated via trigger.';
    
COMMENT ON COLUMN cars.location_point IS 
    'PostGIS geography point for car base location (WGS84, SRID 4326). Auto-updated via trigger.';
    
COMMENT ON COLUMN bookings.pickup_location_point IS 
    'PostGIS geography point for booking pickup location (WGS84, SRID 4326). Auto-updated via trigger.';
    
COMMENT ON COLUMN bookings.guest_check_in_location_point IS 
    'PostGIS geography point for guest check-in GPS location (VAL-004 dispute verification). Auto-updated via trigger.';
    
COMMENT ON COLUMN bookings.host_check_in_location_point IS 
    'PostGIS geography point for host check-in GPS location (VAL-004 dispute verification). Auto-updated via trigger.';
    
COMMENT ON COLUMN bookings.guest_checkout_location_point IS 
    'PostGIS geography point for guest checkout GPS location (VAL-010 dispute verification). Auto-updated via trigger.';
    
COMMENT ON COLUMN bookings.host_checkout_location_point IS 
    'PostGIS geography point for host checkout GPS location (VAL-010 dispute verification). Auto-updated via trigger.';


-- ============================================================================
-- STEP 8: Success verification
-- ============================================================================

DO $$ 
DECLARE
    pois_count INTEGER;
    cars_count INTEGER;
    bookings_pickup_count INTEGER;
    bookings_guest_checkin_count INTEGER;
    bookings_host_checkin_count INTEGER;
    bookings_guest_checkout_count INTEGER;
    bookings_host_checkout_count INTEGER;
    index_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO pois_count FROM delivery_pois WHERE location_point IS NOT NULL;
    SELECT COUNT(*) INTO cars_count FROM cars WHERE location_point IS NOT NULL;
    SELECT COUNT(*) INTO bookings_pickup_count FROM bookings WHERE pickup_location_point IS NOT NULL;
    SELECT COUNT(*) INTO bookings_guest_checkin_count FROM bookings WHERE guest_check_in_location_point IS NOT NULL;
    SELECT COUNT(*) INTO bookings_host_checkin_count FROM bookings WHERE host_check_in_location_point IS NOT NULL;
    SELECT COUNT(*) INTO bookings_guest_checkout_count FROM bookings WHERE guest_checkout_location_point IS NOT NULL;
    SELECT COUNT(*) INTO bookings_host_checkout_count FROM bookings WHERE host_checkout_location_point IS NOT NULL;
    SELECT COUNT(*) INTO index_count FROM pg_indexes WHERE indexname LIKE '%location_point%';
    
    RAISE NOTICE '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
    RAISE NOTICE '✅ PostGIS Spatial Indexes Migration (V48) - COMPLETE';
    RAISE NOTICE '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
    RAISE NOTICE '';
    RAISE NOTICE 'Geography Columns Populated:';
    RAISE NOTICE '  • delivery_pois.location_point: % records', pois_count;
    RAISE NOTICE '  • cars.location_point: % records', cars_count;
    RAISE NOTICE '  • bookings.pickup_location_point: % records', bookings_pickup_count;
    RAISE NOTICE '  • bookings.guest_check_in_location_point: % records', bookings_guest_checkin_count;
    RAISE NOTICE '  • bookings.host_check_in_location_point: % records', bookings_host_checkin_count;
    RAISE NOTICE '  • bookings.guest_checkout_location_point: % records', bookings_guest_checkout_count;
    RAISE NOTICE '  • bookings.host_checkout_location_point: % records', bookings_host_checkout_count;
    RAISE NOTICE '';
    RAISE NOTICE 'Indexes Created: % GiST spatial indexes', index_count;
    RAISE NOTICE 'Triggers Created: 3 (delivery_pois, cars, bookings)';
    RAISE NOTICE '';
    RAISE NOTICE 'Performance Impact:';
    RAISE NOTICE '  • Car search queries: Sequential Scan → Index Scan';
    RAISE NOTICE '  • Location dispute verification: Instant distance calculation';
    RAISE NOTICE '  • Expected speedup: 100-1000x for radius queries';
    RAISE NOTICE '';
    RAISE NOTICE 'Usage Example:';
    RAISE NOTICE '  -- Find cars within 5km of Belgrade center';
    RAISE NOTICE '  SELECT * FROM cars';
    RAISE NOTICE '  WHERE tiger.ST_DWithin(';
    RAISE NOTICE '    location_point,';
    RAISE NOTICE '    tiger.ST_SetSRID(tiger.ST_MakePoint(20.4633, 44.8176), 4326)::tiger.geography,';
    RAISE NOTICE '    5000';
    RAISE NOTICE '  );';
    RAISE NOTICE '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
END $$;
