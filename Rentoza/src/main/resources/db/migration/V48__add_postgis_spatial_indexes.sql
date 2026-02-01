-- V48__add_postgis_spatial_indexes.sql
-- 
-- P1-4: Add PostGIS spatial indexes for optimized geospatial queries
-- 
-- This migration:
-- 1. Adds geography columns for PostGIS spatial indexing
-- 2. Creates GiST indexes for sub-10ms radius queries
-- 3. Sets up triggers to auto-populate geography from lat/lon
-- 
-- Database: PostgreSQL 15+ with PostGIS extension
-- Risk: LOW (additive only, no data modification)
-- Performance: Reduces geospatial queries from O(n) to O(log n)

-- ============================================================================
-- STEP 1: Ensure PostGIS extension is enabled
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- ============================================================================
-- STEP 2: Add geography column to delivery_pois table
-- ============================================================================

-- Add the geography column (nullable initially for safe migration)
ALTER TABLE delivery_pois 
    ADD COLUMN IF NOT EXISTS location_point geography(Point, 4326);

-- Populate existing records with geography from lat/lon
UPDATE delivery_pois 
SET location_point = ST_SetSRID(
    ST_MakePoint(longitude, latitude),
    4326
)::geography
WHERE latitude IS NOT NULL 
  AND longitude IS NOT NULL 
  AND location_point IS NULL;

-- Create GiST index for spatial queries
CREATE INDEX IF NOT EXISTS idx_delivery_pois_location_point 
    ON delivery_pois USING GIST (location_point);

-- ============================================================================
-- STEP 3: Add geography column to cars table (if not already present)
-- ============================================================================

-- Add the geography column
ALTER TABLE cars 
    ADD COLUMN IF NOT EXISTS location_point geography(Point, 4326);

-- Populate existing records
UPDATE cars 
SET location_point = ST_SetSRID(
    ST_MakePoint(location_longitude, location_latitude),
    4326
)::geography
WHERE location_latitude IS NOT NULL 
  AND location_longitude IS NOT NULL 
  AND location_point IS NULL;

-- Create GiST index for spatial queries
CREATE INDEX IF NOT EXISTS idx_cars_location_point_gist 
    ON cars USING GIST (location_point);

-- ============================================================================
-- STEP 4: Create trigger function to auto-update geography on INSERT/UPDATE
-- ============================================================================

CREATE OR REPLACE FUNCTION update_delivery_poi_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL THEN
        NEW.location_point := ST_SetSRID(
            ST_MakePoint(NEW.longitude, NEW.latitude),
            4326
        )::geography;
    ELSE
        NEW.location_point := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop existing trigger if any, then create
DROP TRIGGER IF EXISTS trg_update_delivery_poi_location_point ON delivery_pois;

CREATE TRIGGER trg_update_delivery_poi_location_point
    BEFORE INSERT OR UPDATE OF latitude, longitude
    ON delivery_pois
    FOR EACH ROW
    EXECUTE FUNCTION update_delivery_poi_location_point();

-- ============================================================================
-- STEP 5: Create trigger for cars table
-- ============================================================================

CREATE OR REPLACE FUNCTION update_car_location_point()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.location_latitude IS NOT NULL AND NEW.location_longitude IS NOT NULL THEN
        NEW.location_point := ST_SetSRID(
            ST_MakePoint(NEW.location_longitude, NEW.location_latitude),
            4326
        )::geography;
    ELSE
        NEW.location_point := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop existing trigger if any, then create
DROP TRIGGER IF EXISTS trg_update_car_location_point ON cars;

CREATE TRIGGER trg_update_car_location_point
    BEFORE INSERT OR UPDATE OF location_latitude, location_longitude
    ON cars
    FOR EACH ROW
    EXECUTE FUNCTION update_car_location_point();

-- ============================================================================
-- STEP 6: Add geography to bookings for pickup/return locations
-- ============================================================================

ALTER TABLE bookings 
    ADD COLUMN IF NOT EXISTS pickup_location_point geography(Point, 4326),
    ADD COLUMN IF NOT EXISTS return_location_point geography(Point, 4326);

-- Populate existing records
UPDATE bookings 
SET pickup_location_point = ST_SetSRID(
    ST_MakePoint(pickup_longitude, pickup_latitude),
    4326
)::geography
WHERE pickup_latitude IS NOT NULL 
  AND pickup_longitude IS NOT NULL 
  AND pickup_location_point IS NULL;

UPDATE bookings 
SET return_location_point = ST_SetSRID(
    ST_MakePoint(return_longitude, return_latitude),
    4326
)::geography
WHERE return_latitude IS NOT NULL 
  AND return_longitude IS NOT NULL 
  AND return_location_point IS NULL;

-- Create GiST indexes for spatial queries
CREATE INDEX IF NOT EXISTS idx_bookings_pickup_location_gist 
    ON bookings USING GIST (pickup_location_point);
    
CREATE INDEX IF NOT EXISTS idx_bookings_return_location_gist 
    ON bookings USING GIST (return_location_point);

-- ============================================================================
-- Comments for documentation
-- ============================================================================

COMMENT ON COLUMN delivery_pois.location_point IS 
    'PostGIS geography point for GiST-indexed spatial queries (WGS84)';
    
COMMENT ON COLUMN cars.location_point IS 
    'PostGIS geography point for GiST-indexed spatial queries (WGS84)';
    
COMMENT ON INDEX idx_delivery_pois_location_point IS 
    'GiST index for ST_DWithin radius queries on delivery POIs';
    
COMMENT ON INDEX idx_cars_location_point_gist IS 
    'GiST index for ST_DWithin radius queries on car locations';
