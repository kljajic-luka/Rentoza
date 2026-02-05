-- ============================================================================
-- V38: CarBookingSettings Columns (Phase 2 - Validation Alignment)
-- ============================================================================
--
-- GOAL: Add host-configurable booking settings to cars table.
--
-- FEATURES:
-- 1. Per-car minimum trip duration (hours)
-- 2. Per-car maximum trip duration (days)
-- 3. Per-car advance notice requirement (hours)
-- 4. Per-car buffer between trips (hours)
-- 5. Instant booking toggle
--
-- DEFAULTS: Match system-wide defaults from CarBookingSettings.java
--
-- ============================================================================

-- ============================================================================
-- CARS TABLE - Add booking settings columns
-- ============================================================================

ALTER TABLE cars
    ADD COLUMN booking_min_trip_hours INT DEFAULT 24 
        COMMENT 'Host-configured minimum trip duration (hours). Default: 24 (1 day)',
    
    ADD COLUMN booking_max_trip_days INT DEFAULT 30 
        COMMENT 'Host-configured maximum trip duration (days). Default: 30',
    
    ADD COLUMN booking_advance_notice_hours INT DEFAULT 1 
        COMMENT 'Required advance notice before booking (hours). Default: 1',
    
    ADD COLUMN booking_prep_buffer_hours INT DEFAULT 3 
        COMMENT 'Buffer time between trips (hours). Default: 3',
    
    ADD COLUMN booking_instant_book_enabled BOOLEAN DEFAULT FALSE 
        COMMENT 'If true, guests can book instantly without host approval';

-- ============================================================================
-- VALIDATION CONSTRAINTS
-- ============================================================================

-- Ensure min_trip_hours is within valid range (1-168 hours = 1 hour to 7 days)
ALTER TABLE cars
    ADD CONSTRAINT chk_booking_min_trip_hours 
    CHECK (booking_min_trip_hours IS NULL OR (booking_min_trip_hours >= 1 AND booking_min_trip_hours <= 168));

-- Ensure max_trip_days is within valid range (1-30 days)
ALTER TABLE cars
    ADD CONSTRAINT chk_booking_max_trip_days 
    CHECK (booking_max_trip_days IS NULL OR (booking_max_trip_days >= 1 AND booking_max_trip_days <= 30));

-- Ensure advance_notice_hours is within valid range (0-72 hours)
ALTER TABLE cars
    ADD CONSTRAINT chk_booking_advance_notice_hours 
    CHECK (booking_advance_notice_hours IS NULL OR (booking_advance_notice_hours >= 0 AND booking_advance_notice_hours <= 72));

-- Ensure prep_buffer_hours is within valid range (0-24 hours)
ALTER TABLE cars
    ADD CONSTRAINT chk_booking_prep_buffer_hours 
    CHECK (booking_prep_buffer_hours IS NULL OR (booking_prep_buffer_hours >= 0 AND booking_prep_buffer_hours <= 24));

-- ============================================================================
-- MIGRATION NOTES
-- ============================================================================
--
-- BACKWARD COMPATIBILITY:
-- - All columns have sensible defaults matching system-wide defaults
-- - Existing cars automatically get default booking settings
-- - No data migration required - defaults apply immediately
--
-- FRONTEND IMPACT:
-- - Add-Car wizard can now include booking settings section
-- - Car detail page can show booking rules
-- - Host dashboard can edit booking settings
--
-- BACKEND IMPACT:
-- - BookingService.createBooking() now uses car.getEffectiveBookingSettings()
-- - Validation messages include car-specific limits
--
-- ============================================================================
