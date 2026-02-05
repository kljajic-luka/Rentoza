-- Migration: Add indexes for car search filtering and sorting
-- Version: V3
-- Date: 2025-11-04
-- Purpose: Optimize performance for car search/filter queries

-- Index for price filtering (commonly used for min/max price filters)
CREATE INDEX IF NOT EXISTS idx_car_price_per_day ON cars(price_per_day);

-- Index for year filtering (commonly used for year range filters)
CREATE INDEX IF NOT EXISTS idx_car_year ON cars(year);

-- Index for seats filtering (commonly used for minimum seats filter)
CREATE INDEX IF NOT EXISTS idx_car_seats ON cars(seats);

-- Index for transmission type filtering (commonly used for automatic/manual filter)
CREATE INDEX IF NOT EXISTS idx_car_transmission_type ON cars(transmission_type);

-- Index for brand filtering (commonly used for make filter)
CREATE INDEX IF NOT EXISTS idx_car_brand ON cars(brand);

-- Index for model filtering (commonly used for model filter)
CREATE INDEX IF NOT EXISTS idx_car_model ON cars(model);

-- Composite index for price + available (common filter combination)
CREATE INDEX IF NOT EXISTS idx_car_price_available ON cars(price_per_day, available);

-- Composite index for location + available (existing location index, enhance with available)
-- Note: idx_car_location already exists, so we create a composite one
CREATE INDEX IF NOT EXISTS idx_car_location_available ON cars(location, available);

-- Composite index for year + available (common for sorting by newest)
CREATE INDEX IF NOT EXISTS idx_car_year_available ON cars(year DESC, available);
