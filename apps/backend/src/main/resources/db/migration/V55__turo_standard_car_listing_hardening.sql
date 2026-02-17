-- ============================================================================
-- V55: Turo-Standard Car Listing Hardening (Feature 3)
-- ============================================================================
-- Adds:
--   1. daily_mileage_limit_km column (host-configurable, default 200)
--   2. current_mileage_km column (odometer reading at listing time)
--   3. UNIQUE constraint on license_plate (duplicate listing prevention)
--   4. CHECK constraint on price_per_day (10 <= price <= 50000 RSD)
--   5. CHECK constraint on daily_mileage_limit_km (50-1000)
--   6. CHECK constraint on current_mileage_km (0-300000)
-- ============================================================================

-- 1. Add daily mileage limit column (host-configurable)
ALTER TABLE cars ADD COLUMN IF NOT EXISTS daily_mileage_limit_km INTEGER DEFAULT 200;

-- 2. Add current mileage column (odometer at listing time)
ALTER TABLE cars ADD COLUMN IF NOT EXISTS current_mileage_km INTEGER;

-- 3. UNIQUE constraint on license_plate (P1: duplicate listing prevention)
-- Handle existing duplicates by keeping the latest one (highest ID)
-- First, clear duplicates (set NULL on older entries)
UPDATE cars c1
SET license_plate = NULL
WHERE license_plate IS NOT NULL
  AND id < (
    SELECT MAX(c2.id) FROM cars c2
    WHERE LOWER(c2.license_plate) = LOWER(c1.license_plate)
  );

-- Now add unique index (case-insensitive)
CREATE UNIQUE INDEX IF NOT EXISTS idx_cars_license_plate_unique
    ON cars (LOWER(license_plate))
    WHERE license_plate IS NOT NULL;

-- 4. CHECK constraint: price per day must be between 10 and 50,000 RSD
ALTER TABLE cars ADD CONSTRAINT chk_cars_price_range
    CHECK (price_per_day >= 10 AND price_per_day <= 50000);

-- 5. CHECK constraint: daily mileage limit between 50 and 1000 km
ALTER TABLE cars ADD CONSTRAINT chk_cars_daily_mileage_limit
    CHECK (daily_mileage_limit_km IS NULL OR (daily_mileage_limit_km >= 50 AND daily_mileage_limit_km <= 1000));

-- 6. CHECK constraint: current mileage between 0 and 300,000 km
ALTER TABLE cars ADD CONSTRAINT chk_cars_current_mileage
    CHECK (current_mileage_km IS NULL OR (current_mileage_km >= 0 AND current_mileage_km <= 300000));

-- 7. Add index for mileage limit lookups (used in checkout calculations)
CREATE INDEX IF NOT EXISTS idx_cars_daily_mileage_limit ON cars (daily_mileage_limit_km)
    WHERE daily_mileage_limit_km IS NOT NULL;
