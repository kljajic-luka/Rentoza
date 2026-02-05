-- V49__add_performance_indexes.sql
-- Performance optimization indexes for high-frequency queries
-- Date: 2026-02-04

-- ============================================================================
-- USER TABLE INDEXES
-- ============================================================================

-- Email lookup for authentication (high frequency)
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Supabase Auth UID lookup (high frequency during auth)
CREATE INDEX IF NOT EXISTS idx_users_auth_uid ON users(auth_uid);

-- ============================================================================
-- BOOKING TABLE INDEXES (Additional)
-- ============================================================================

-- Booking status filtering (admin dashboard, scheduler queries)
CREATE INDEX IF NOT EXISTS idx_bookings_status ON bookings(status);

-- Renter bookings lookup (my-bookings page)
CREATE INDEX IF NOT EXISTS idx_bookings_renter_id ON bookings(renter_id);

-- ============================================================================
-- CAR TABLE INDEXES (Additional)
-- ============================================================================

-- Owner's cars lookup (owner dashboard)
CREATE INDEX IF NOT EXISTS idx_cars_owner_id ON cars(owner_id);

-- Full-text search for car listings (optional - enable if needed)
-- CREATE INDEX IF NOT EXISTS idx_cars_fulltext 
--     ON cars USING gin(to_tsvector('english', COALESCE(brand, '') || ' ' || COALESCE(model, '') || ' ' || COALESCE(description, '')));

-- ============================================================================
-- FAVORITES TABLE INDEXES
-- ============================================================================

-- Composite index for favorite existence check
CREATE INDEX IF NOT EXISTS idx_favorites_user_car ON favorites(user_id, car_id);

-- ============================================================================
-- ANALYSIS COMMENT
-- ============================================================================
-- Run ANALYZE on tables after index creation:
-- ANALYZE users;
-- ANALYZE bookings;
-- ANALYZE cars;
-- ANALYZE favorites;
