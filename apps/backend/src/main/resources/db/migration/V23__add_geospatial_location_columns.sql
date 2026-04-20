-- V23__add_geospatial_location_columns.sql
-- 
-- Phase 1 of Geospatial Location Migration: Add columns, generated POINT, and SPATIAL INDEX
-- 
-- This migration adds geospatial support to cars and bookings tables:
-- - DECIMAL lat/lon columns for entity mapping
-- - Generated POINT columns for SPATIAL INDEX (MySQL requires stored generated columns)
-- - SPATIAL INDEX for sub-100ms radius queries
-- - Delivery pricing fields for Turo-style fee calculation
-- 
-- CRITICAL: MySQL SPATIAL uses POINT(longitude, latitude) order!
-- All queries must use: POINT(location_longitude, location_latitude) NOT POINT(lat, lon)
--
-- Database: MySQL 8.0+
-- Risk: LOW (additive only, no data modification)
-- Rollback: DROP COLUMN statements below

-- ============================================================================
-- STEP 1: Add geospatial columns to cars table
-- ============================================================================

ALTER TABLE cars
    ADD COLUMN location_latitude DECIMAL(10, 8) NULL 
        COMMENT 'Car parking location latitude (-90 to +90)',
    ADD COLUMN location_longitude DECIMAL(11, 8) NULL 
        COMMENT 'Car parking location longitude (-180 to +180)',
    ADD COLUMN location_address VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT 'Human-readable address from geocoding',
    ADD COLUMN location_city VARCHAR(50) NULL
        COMMENT 'City name for UI grouping and legacy search',
    ADD COLUMN location_zip_code VARCHAR(10) NULL
        COMMENT 'Postal code',
    ADD COLUMN location_accuracy_meters INT NULL
        COMMENT 'GPS accuracy in meters (null = unknown)';

-- ============================================================================
-- STEP 2: Add delivery pricing fields to cars table (Turo-style)
-- ============================================================================

ALTER TABLE cars
    ADD COLUMN delivery_radius_km DECIMAL(5, 2) DEFAULT 0.00 NULL
        COMMENT 'Free delivery radius in km (0 = no delivery)',
    ADD COLUMN delivery_fee_per_km DECIMAL(10, 2) NULL
        COMMENT 'Fee per km beyond free radius (RSD)';

-- ============================================================================
-- STEP 3: Add generated POINT column for SPATIAL INDEX (MySQL 8.0+)
-- 
-- NOTE: MySQL SPATIAL INDEX requires a geometry column, not separate lat/lon.
-- We use a STORED generated column to derive POINT from lat/lon automatically.
-- This keeps entity mapping simple (just DECIMAL fields) while enabling SPATIAL.
-- ============================================================================

ALTER TABLE cars
    ADD COLUMN location_point POINT 
        GENERATED ALWAYS AS (
            CASE 
                WHEN location_latitude IS NOT NULL AND location_longitude IS NOT NULL 
                THEN ST_PointFromText(
                    CONCAT('POINT(', location_longitude, ' ', location_latitude, ')'),
                    4326  -- WGS84 SRID
                )
                ELSE NULL 
            END
        ) STORED NULL
        COMMENT 'Generated POINT for SPATIAL INDEX (WGS84)';

-- ============================================================================
-- STEP 4: Create SPATIAL INDEX on cars.location_point
-- 
-- CRITICAL: SPATIAL INDEX enables sub-100ms queries like:
-- SELECT * FROM cars WHERE ST_Distance_Sphere(location_point, ST_PointFromText(...)) <= 5000
-- ============================================================================

CREATE SPATIAL INDEX idx_car_location_point ON cars (location_point);

-- Composite index for city + availability filtering (fallback for string search)
CREATE INDEX idx_car_location_city_available ON cars (location_city, available);

-- ============================================================================
-- STEP 5: Add pickup location columns to bookings table
-- 
-- These capture the AGREED pickup location at booking time (immutable).
-- Different from car_latitude/car_longitude which are check-in artifacts.
-- ============================================================================

ALTER TABLE bookings
    ADD COLUMN pickup_latitude DECIMAL(10, 8) NULL
        COMMENT 'Agreed pickup latitude at booking time (IMMUTABLE)',
    ADD COLUMN pickup_longitude DECIMAL(11, 8) NULL
        COMMENT 'Agreed pickup longitude at booking time (IMMUTABLE)',
    ADD COLUMN pickup_address VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT 'Agreed pickup address',
    ADD COLUMN pickup_city VARCHAR(50) NULL
        COMMENT 'Pickup city for reporting',
    ADD COLUMN pickup_zip_code VARCHAR(10) NULL
        COMMENT 'Pickup postal code',
    ADD COLUMN pickup_accuracy_meters INT NULL
        COMMENT 'GPS accuracy at booking time';

-- ============================================================================
-- STEP 6: Add pickup location variance tracking (audit/disputes)
-- ============================================================================

ALTER TABLE bookings
    ADD COLUMN pickup_location_variance_meters INT NULL
        COMMENT 'Distance car moved from booked location (at check-in)',
    ADD COLUMN execution_location_updated_by BIGINT NULL
        COMMENT 'Host who refined pickup location at check-in',
    ADD COLUMN execution_location_updated_at DATETIME NULL
        COMMENT 'When host refined the pickup location';

-- FK constraint for audit trail
ALTER TABLE bookings
    ADD CONSTRAINT fk_booking_execution_location_updated_by
    FOREIGN KEY (execution_location_updated_by) 
    REFERENCES users(id) ON DELETE SET NULL;

-- ============================================================================
-- STEP 7: Add delivery fee tracking to bookings
-- ============================================================================

ALTER TABLE bookings
    ADD COLUMN delivery_distance_km DECIMAL(8, 2) NULL
        COMMENT 'Calculated driving distance for delivery (OSRM)',
    ADD COLUMN delivery_fee_calculated DECIMAL(10, 2) NULL
        COMMENT 'Final delivery fee charge (RSD)';

-- ============================================================================
-- STEP 8: Add generated POINT column for bookings pickup location
-- ============================================================================

ALTER TABLE bookings
    ADD COLUMN pickup_point POINT 
        GENERATED ALWAYS AS (
            CASE 
                WHEN pickup_latitude IS NOT NULL AND pickup_longitude IS NOT NULL 
                THEN ST_PointFromText(
                    CONCAT('POINT(', pickup_longitude, ' ', pickup_latitude, ')'),
                    4326
                )
                ELSE NULL 
            END
        ) STORED NULL
        COMMENT 'Generated POINT for pickup location queries';

-- Index for pickup location queries
CREATE INDEX idx_booking_pickup_city ON bookings (pickup_city);

-- ============================================================================
-- STEP 9: Add migration tracking column (temporary)
-- ============================================================================

ALTER TABLE cars
    ADD COLUMN _migration_status ENUM('PENDING', 'GEOCODED', 'VALIDATED', 'COMPLETED') 
        DEFAULT 'PENDING'
        COMMENT 'Tracks string→geospatial migration progress (temporary)';

-- ============================================================================
-- ROLLBACK SCRIPT (if needed):
-- 
-- ALTER TABLE cars DROP COLUMN location_point;
-- ALTER TABLE cars DROP COLUMN location_latitude;
-- ALTER TABLE cars DROP COLUMN location_longitude;
-- ALTER TABLE cars DROP COLUMN location_address;
-- ALTER TABLE cars DROP COLUMN location_city;
-- ALTER TABLE cars DROP COLUMN location_zip_code;
-- ALTER TABLE cars DROP COLUMN location_accuracy_meters;
-- ALTER TABLE cars DROP COLUMN delivery_radius_km;
-- ALTER TABLE cars DROP COLUMN delivery_fee_per_km;
-- ALTER TABLE cars DROP COLUMN _migration_status;
-- 
-- ALTER TABLE bookings DROP CONSTRAINT fk_booking_execution_location_updated_by;
-- ALTER TABLE bookings DROP COLUMN pickup_point;
-- ALTER TABLE bookings DROP COLUMN pickup_latitude;
-- ALTER TABLE bookings DROP COLUMN pickup_longitude;
-- ALTER TABLE bookings DROP COLUMN pickup_address;
-- ALTER TABLE bookings DROP COLUMN pickup_city;
-- ALTER TABLE bookings DROP COLUMN pickup_zip_code;
-- ALTER TABLE bookings DROP COLUMN pickup_accuracy_meters;
-- ALTER TABLE bookings DROP COLUMN pickup_location_variance_meters;
-- ALTER TABLE bookings DROP COLUMN execution_location_updated_by;
-- ALTER TABLE bookings DROP COLUMN execution_location_updated_at;
-- ALTER TABLE bookings DROP COLUMN delivery_distance_km;
-- ALTER TABLE bookings DROP COLUMN delivery_fee_calculated;
-- ============================================================================
