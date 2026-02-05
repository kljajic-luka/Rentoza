-- ============================================================================
-- V24: Create Delivery POIs (Points of Interest) table
-- ============================================================================
-- This migration creates the delivery_pois table for special delivery fee rules
-- at specific locations (airports, train stations, city centers, etc.)
--
-- Part of: Geospatial Location Migration (Phase 2.4)
-- Author: Principal Software Architect
-- Date: 2024
--
-- BUSINESS CONTEXT:
-- POIs define fixed or minimum delivery fees for high-value pickup locations.
-- Example: Belgrade Airport has a fixed €25 delivery fee regardless of distance.
--
-- SEED DATA INCLUDES:
-- - Serbian airports (Belgrade, Niš)
-- - Major train stations
-- - Key tourist areas
-- ============================================================================

-- Create delivery_pois table
CREATE TABLE IF NOT EXISTS delivery_pois (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Identification
    name VARCHAR(100) NOT NULL,
    code VARCHAR(20) NOT NULL UNIQUE,
    
    -- Geospatial location (POI center)
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    
    -- Generated POINT for potential future SPATIAL INDEX
    location_point POINT GENERATED ALWAYS AS (
        ST_PointFromText(CONCAT('POINT(', longitude, ' ', latitude, ')'), 4326)
    ) STORED,
    
    -- Radius in kilometers
    radius_km DOUBLE NOT NULL DEFAULT 2.0,
    
    -- POI type for categorization
    poi_type VARCHAR(30) NOT NULL,
    
    -- Fee rules (nullable - null means use calculated fee)
    fixed_fee DECIMAL(10, 2) DEFAULT NULL COMMENT 'Fixed fee overrides per-km calculation',
    minimum_fee DECIMAL(10, 2) DEFAULT NULL COMMENT 'Minimum fee if calculated is lower',
    surcharge DECIMAL(10, 2) DEFAULT NULL COMMENT 'Additional charge on top of calculated',
    
    -- Priority for overlapping POIs (higher = wins)
    priority INT NOT NULL DEFAULT 0,
    
    -- Status
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(500) DEFAULT NULL,
    
    -- Timestamps
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    
    -- Indexes
    INDEX idx_delivery_poi_active (active),
    INDEX idx_delivery_poi_type (poi_type),
    INDEX idx_delivery_poi_code (code),
    
    -- SPATIAL INDEX for efficient proximity queries
    SPATIAL INDEX idx_delivery_poi_location (location_point)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================================
-- SEED DATA: Serbian POIs
-- ============================================================================

-- Airports
INSERT INTO delivery_pois (name, code, latitude, longitude, radius_km, poi_type, fixed_fee, priority, notes)
VALUES 
    ('Belgrade Nikola Tesla Airport', 'BEG', 44.8184, 20.3091, 3.0, 'AIRPORT', 25.00, 100, 'International airport - fixed fee for all pickups'),
    ('Niš Constantine the Great Airport', 'INI', 43.3372, 21.8536, 2.0, 'AIRPORT', 15.00, 100, 'Regional airport'),
    ('Morava Airport Kraljevo', 'KVO', 43.8183, 20.5872, 2.0, 'AIRPORT', 12.00, 100, 'Small regional airport');

-- Train Stations
INSERT INTO delivery_pois (name, code, latitude, longitude, radius_km, poi_type, minimum_fee, surcharge, priority, notes)
VALUES 
    ('Belgrade Central Railway Station', 'BG-RAIL', 44.8048, 20.4651, 1.0, 'TRAIN_STATION', 8.00, 3.00, 80, 'Main train station Belgrade'),
    ('Novi Sad Railway Station', 'NS-RAIL', 45.2551, 19.8428, 1.0, 'TRAIN_STATION', 6.00, 2.00, 80, 'Novi Sad main station'),
    ('Niš Railway Station', 'NI-RAIL', 43.3173, 21.8958, 1.0, 'TRAIN_STATION', 5.00, 2.00, 80, 'Niš main station');

-- Bus Stations
INSERT INTO delivery_pois (name, code, latitude, longitude, radius_km, poi_type, minimum_fee, priority, notes)
VALUES 
    ('Belgrade Bus Station', 'BG-BUS', 44.8014, 20.4600, 0.8, 'BUS_STATION', 6.00, 70, 'Main intercity bus station'),
    ('Novi Sad Bus Station', 'NS-BUS', 45.2526, 19.8380, 0.8, 'BUS_STATION', 5.00, 70, 'Novi Sad bus terminal');

-- City Centers (surcharge only, no fixed/minimum fee)
INSERT INTO delivery_pois (name, code, latitude, longitude, radius_km, poi_type, surcharge, priority, notes)
VALUES 
    ('Belgrade City Center - Republic Square', 'BG-CENTER', 44.8167, 20.4594, 1.5, 'CITY_CENTER', 4.00, 50, 'Historic center, limited parking - surcharge for access'),
    ('Novi Sad City Center', 'NS-CENTER', 45.2551, 19.8425, 1.0, 'CITY_CENTER', 3.00, 50, 'Downtown area surcharge'),
    ('Niš City Center', 'NI-CENTER', 43.3209, 21.8958, 1.0, 'CITY_CENTER', 2.50, 50, 'Downtown Niš');

-- Hotel Zones
INSERT INTO delivery_pois (name, code, latitude, longitude, radius_km, poi_type, surcharge, priority, notes)
VALUES 
    ('Belgrade Waterfront', 'BG-WATERFRONT', 44.8099, 20.4463, 1.0, 'HOTEL_ZONE', 3.00, 60, 'Premium hotel zone - easy access'),
    ('Zlatibor Resort Area', 'ZLATIBOR', 43.7300, 19.7028, 5.0, 'HOTEL_ZONE', 5.00, 60, 'Mountain resort area');

-- Tourist Attractions (informational, no special fees)
INSERT INTO delivery_pois (name, code, latitude, longitude, radius_km, poi_type, priority, active, notes)
VALUES 
    ('Kalemegdan Fortress', 'KALEMEGDAN', 44.8233, 20.4528, 0.5, 'TOURIST_ATTRACTION', 30, TRUE, 'Historic fortress - walkable area only'),
    ('Exit Festival Petrovaradin', 'EXIT-FEST', 45.2521, 19.8608, 2.0, 'TOURIST_ATTRACTION', 30, FALSE, 'Enable during festival season');

-- ============================================================================
-- COMMENTS
-- ============================================================================
-- Note: More POIs can be added via admin interface once LocationAdminController
-- is implemented. This seed data covers primary use cases for Serbia.
--
-- POI priority guidelines:
-- 100 - Airports (highest - fixed fees critical for business model)
--  80 - Train Stations (major transit hubs)
--  70 - Bus Stations
--  60 - Hotel Zones (premium areas)
--  50 - City Centers (general surcharge)
--  30 - Tourist Attractions (informational)
-- ============================================================================
