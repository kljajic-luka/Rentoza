-- Phase 2.2: Add pickup time support to bookings table
-- This migration adds two columns:
-- 1. pickup_time_window: Predefined time windows (MORNING, AFTERNOON, EVENING, EXACT)
-- 2. pickup_time: Exact time (only used when pickup_time_window is 'EXACT')

ALTER TABLE bookings
    ADD COLUMN pickup_time_window VARCHAR(20) DEFAULT 'MORNING' NOT NULL COMMENT 'Pickup time window: MORNING (08:00-12:00), AFTERNOON (12:00-16:00), EVENING (16:00-20:00), EXACT',
    ADD COLUMN pickup_time TIME NULL COMMENT 'Exact pickup time (HH:mm), only used when pickup_time_window is EXACT';

-- Add index for faster queries filtering by pickup time window
CREATE INDEX idx_bookings_pickup_time_window ON bookings(pickup_time_window);

-- Add composite index for optimized availability checks (Phase 2.3: conflict detection)
CREATE INDEX idx_bookings_car_dates ON bookings(car_id, start_date, end_date);
