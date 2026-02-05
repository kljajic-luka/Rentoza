-- =============================================================================
-- P0-4: Add NOT NULL constraints for data integrity
-- =============================================================================
-- These constraints prevent invalid data states that could cause:
-- 1. NullPointerExceptions in business logic
-- 2. Orphaned records
-- 3. Financial calculation errors
-- 4. Data integrity violations
--
-- SAFETY: First check for null values, then add constraints
-- If nulls exist, migration will fail with a clear error message
-- =============================================================================

-- =============================================================================
-- STEP 1: Pre-flight checks for existing null values
-- =============================================================================
-- These will fail if nulls exist, preventing data corruption

DO $$
DECLARE
    null_count INTEGER;
BEGIN
    -- Check bookings.car_id
    SELECT COUNT(*) INTO null_count FROM bookings WHERE car_id IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'P0-4 BLOCKED: % bookings have NULL car_id. Fix data before applying migration.', null_count;
    END IF;
    
    -- Check bookings.renter_id
    SELECT COUNT(*) INTO null_count FROM bookings WHERE renter_id IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'P0-4 BLOCKED: % bookings have NULL renter_id. Fix data before applying migration.', null_count;
    END IF;
    
    -- Check reviews.car_id
    SELECT COUNT(*) INTO null_count FROM reviews WHERE car_id IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'P0-4 BLOCKED: % reviews have NULL car_id. Fix data before applying migration.', null_count;
    END IF;
    
    -- Check reviews.reviewer_id
    SELECT COUNT(*) INTO null_count FROM reviews WHERE reviewer_id IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'P0-4 BLOCKED: % reviews have NULL reviewer_id. Fix data before applying migration.', null_count;
    END IF;
    
    -- Check reviews.booking_id
    SELECT COUNT(*) INTO null_count FROM reviews WHERE booking_id IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'P0-4 BLOCKED: % reviews have NULL booking_id. Fix data before applying migration.', null_count;
    END IF;
    
    RAISE NOTICE 'P0-4: Pre-flight checks passed. No null values found in critical columns.';
END $$;

-- =============================================================================
-- STEP 2: Add NOT NULL constraints to bookings table
-- =============================================================================

-- Booking MUST reference a car
ALTER TABLE bookings 
    ALTER COLUMN car_id SET NOT NULL;

COMMENT ON COLUMN bookings.car_id IS 'FK to cars table. NOT NULL - every booking must reference a car.';

-- Booking MUST have a renter
ALTER TABLE bookings 
    ALTER COLUMN renter_id SET NOT NULL;

COMMENT ON COLUMN bookings.renter_id IS 'FK to users table. NOT NULL - every booking must have a renter (guest).';

-- =============================================================================
-- STEP 3: Add NOT NULL constraints to reviews table
-- =============================================================================

-- Review MUST reference a car
ALTER TABLE reviews 
    ALTER COLUMN car_id SET NOT NULL;

COMMENT ON COLUMN reviews.car_id IS 'FK to cars table. NOT NULL - every review must reference a car.';

-- Review MUST have a reviewer
ALTER TABLE reviews 
    ALTER COLUMN reviewer_id SET NOT NULL;

COMMENT ON COLUMN reviews.reviewer_id IS 'FK to users table. NOT NULL - every review must have a reviewer.';

-- Review MUST reference a booking (ensures reviews are tied to actual rentals)
ALTER TABLE reviews 
    ALTER COLUMN booking_id SET NOT NULL;

COMMENT ON COLUMN reviews.booking_id IS 'FK to bookings table. NOT NULL - every review must be tied to a completed booking.';

-- =============================================================================
-- STEP 4: Add additional data integrity constraints
-- =============================================================================

-- Booking status must always have a value
-- (already has default, but adding explicit NOT NULL for safety)
ALTER TABLE bookings 
    ALTER COLUMN status SET NOT NULL;

-- Booking total_price must always exist (can be 0, but not null)
ALTER TABLE bookings 
    ALTER COLUMN total_price SET NOT NULL;

-- =============================================================================
-- STEP 5: Add indexes for foreign key performance
-- =============================================================================
-- These indexes improve JOIN performance and ON DELETE/UPDATE constraint checks

CREATE INDEX IF NOT EXISTS idx_bookings_car_renter 
    ON bookings(car_id, renter_id);

CREATE INDEX IF NOT EXISTS idx_reviews_car_reviewer 
    ON reviews(car_id, reviewer_id);

CREATE INDEX IF NOT EXISTS idx_reviews_booking 
    ON reviews(booking_id);

-- =============================================================================
-- Migration complete
-- =============================================================================
-- Rollback: If needed, run:
-- ALTER TABLE bookings ALTER COLUMN car_id DROP NOT NULL;
-- ALTER TABLE bookings ALTER COLUMN renter_id DROP NOT NULL;
-- ALTER TABLE reviews ALTER COLUMN car_id DROP NOT NULL;
-- ALTER TABLE reviews ALTER COLUMN reviewer_id DROP NOT NULL;
-- ALTER TABLE reviews ALTER COLUMN booking_id DROP NOT NULL;
