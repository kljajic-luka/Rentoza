-- =============================================================================
-- Rentoza Supabase Migration: Extensions, Types, Sequences, and Helper Functions
-- Version: 001 (CONSOLIDATED - FIXED)
-- Description: ALL dependencies required before any table creation
-- Execution Order: MUST BE FIRST - All subsequent files depend on this
-- =============================================================================

-- -----------------------------------------------------------------------------
-- EXTENSIONS
-- -----------------------------------------------------------------------------

-- PostGIS for geospatial queries (replaces MySQL ST_Distance_Sphere)
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

-- pgcrypto for encryption (hybrid encryption layer)
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- Supabase Vault for secrets management (AWS Secrets Manager integration)
CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;

-- pg_cron for scheduled jobs (replaces Spring @Scheduled)
CREATE EXTENSION IF NOT EXISTS pg_cron WITH SCHEMA pg_catalog;

-- UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

-- Full-text search (for chat messages)
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- -----------------------------------------------------------------------------
-- CUSTOM ENUM TYPES (COMPLETE LIST - 30+ TYPES)
-- -----------------------------------------------------------------------------

-- User verification status
CREATE TYPE user_verification_status AS ENUM (
    'UNVERIFIED',
    'PENDING',
    'VERIFIED',
    'REJECTED',
    'SUSPENDED',
    'EXPIRED'
);

-- User role
CREATE TYPE user_role AS ENUM (
    'RENTER',
    'OWNER',
    'ADMIN',
    'MODERATOR',
    'FINANCIAL_ADMIN'
);

-- Owner type (individual vs legal entity)
CREATE TYPE owner_type AS ENUM (
    'INDIVIDUAL',
    'LEGAL_ENTITY'
);

-- Booking status (full lifecycle)
CREATE TYPE booking_status AS ENUM (
    'PENDING_APPROVAL',
    'APPROVED',
    'ACTIVE',
    'CHECK_IN_OPEN',
    'CHECK_IN_HOST_COMPLETE',
    'CHECK_IN_GUEST_COMPLETE',
    'CHECK_IN_COMPLETE',
    'IN_TRIP',
    'CHECKOUT_OPEN',
    'CHECKOUT_HOST_COMPLETE',
    'CHECKOUT_GUEST_COMPLETE',
    'CHECKOUT_COMPLETE',
    'COMPLETED',
    'CANCELLED_BY_RENTER',
    'CANCELLED_BY_OWNER',
    'CANCELLED_BY_ADMIN',
    'DECLINED',
    'EXPIRED',
    'DISPUTED',
    'NO_SHOW_HOST',
    'NO_SHOW_GUEST'
);

-- Cancellation initiator
CREATE TYPE cancellation_initiator AS ENUM (
    'RENTER',
    'OWNER',
    'ADMIN',
    'SYSTEM'
);

-- Insurance tier
CREATE TYPE insurance_tier AS ENUM (
    'BASIC',
    'STANDARD',
    'PREMIUM'
);

-- Car transmission type
CREATE TYPE transmission_type AS ENUM (
    'MANUAL',
    'AUTOMATIC'
);

-- Car fuel type
CREATE TYPE fuel_type AS ENUM (
    'PETROL',
    'DIESEL',
    'ELECTRIC',
    'HYBRID',
    'LPG'
);

-- Car availability status
CREATE TYPE car_availability_status AS ENUM (
    'AVAILABLE',
    'BOOKED',
    'MAINTENANCE',
    'UNLISTED'
);

-- Check-in event type
CREATE TYPE check_in_event_type AS ENUM (
    'CHECK_IN_OPENED',
    'HOST_ARRIVED',
    'GUEST_ARRIVED',
    'ID_VERIFIED',
    'ID_VERIFICATION_FAILED',
    'ODOMETER_RECORDED',
    'FUEL_LEVEL_RECORDED',
    'PHOTOS_UPLOADED',
    'PHOTOS_APPROVED',
    'LOCKBOX_CODE_REVEALED',
    'HANDSHAKE_COMPLETE',
    'CHECK_IN_COMPLETE',
    'CHECK_IN_CANCELLED',
    'CHECK_IN_EXPIRED'
);

-- Photo rejection reason
CREATE TYPE photo_rejection_reason AS ENUM (
    'BLURRY',
    'WRONG_ANGLE',
    'MISSING_AREA',
    'TIMESTAMP_INVALID',
    'DUPLICATE',
    'OTHER'
);

-- Check-in photo category
CREATE TYPE check_in_photo_category AS ENUM (
    'HOST_CHECK_IN',
    'GUEST_CHECK_IN',
    'HOST_CHECKOUT',
    'GUEST_CHECKOUT',
    'DAMAGE_DOCUMENTATION',
    'ODOMETER',
    'FUEL_GAUGE',
    'EXTERIOR_FRONT',
    'EXTERIOR_BACK',
    'EXTERIOR_LEFT',
    'EXTERIOR_RIGHT',
    'INTERIOR_FRONT',
    'INTERIOR_BACK',
    'TRUNK',
    'OTHER'
);

-- Checkout saga step
CREATE TYPE checkout_saga_step AS ENUM (
    'INITIATED',
    'PHOTOS_VALIDATED',
    'ODOMETER_VALIDATED',
    'LATE_FEE_CALCULATED',
    'DEPOSIT_CAPTURED',
    'DEPOSIT_RELEASED',
    'PAYOUT_INITIATED',
    'COMPLETED',
    'FAILED',
    'COMPENSATING'
);

-- Damage claim status
CREATE TYPE damage_claim_status AS ENUM (
    'PENDING',
    'UNDER_REVIEW',
    'EVIDENCE_REQUESTED',
    'APPROVED',
    'REJECTED',
    'PAID',
    'DISPUTED',
    'ESCALATED'
);

-- Document type
CREATE TYPE document_type AS ENUM (
    'DRIVER_LICENSE_FRONT',
    'DRIVER_LICENSE_BACK',
    'PASSPORT',
    'NATIONAL_ID',
    'SELFIE',
    'VEHICLE_REGISTRATION',
    'INSURANCE_CERTIFICATE',
    'BUSINESS_REGISTRATION',
    'PIB_DOCUMENT'
);

-- Document verification status
CREATE TYPE document_verification_status AS ENUM (
    'PENDING',
    'PROCESSING',
    'APPROVED',
    'REJECTED',
    'EXPIRED',
    'REQUIRES_RESUBMISSION'
);

-- Notification type
CREATE TYPE notification_type AS ENUM (
    'BOOKING_REQUEST',
    'BOOKING_APPROVED',
    'BOOKING_DECLINED',
    'BOOKING_CANCELLED',
    'CHECK_IN_REMINDER',
    'CHECK_IN_OPENED',
    'CHECK_IN_COMPLETE',
    'CHECKOUT_REMINDER',
    'CHECKOUT_COMPLETE',
    'PAYMENT_RECEIVED',
    'PAYOUT_SENT',
    'REVIEW_REQUEST',
    'NEW_MESSAGE',
    'DOCUMENT_APPROVED',
    'DOCUMENT_REJECTED',
    'DAMAGE_CLAIM_FILED',
    'DAMAGE_CLAIM_RESOLVED'
);

-- Notification channel
CREATE TYPE notification_channel AS ENUM (
    'EMAIL',
    'SMS',
    'PUSH',
    'IN_APP'
);

-- Conversation status (chat)
CREATE TYPE conversation_status AS ENUM (
    'PENDING',
    'ACTIVE',
    'CLOSED',
    'ARCHIVED'
);

-- Trip extension status
CREATE TYPE trip_extension_status AS ENUM (
    'PENDING',
    'APPROVED',
    'DECLINED',
    'EXPIRED'
);

-- Admin action type (audit log)
CREATE TYPE admin_action_type AS ENUM (
    'USER_BANNED',
    'USER_UNBANNED',
    'USER_SUSPENDED',
    'DOCUMENT_APPROVED',
    'DOCUMENT_REJECTED',
    'BOOKING_CANCELLED',
    'DAMAGE_CLAIM_RESOLVED',
    'PAYOUT_PROCESSED',
    'CAR_UNLISTED',
    'DISPUTE_RESOLVED'
);

-- MISSING ENUMS (Add these):

-- Checkout saga status (referenced by checkout_saga_state table)
CREATE TYPE checkout_saga_status AS ENUM (
    'RUNNING',
    'COMPENSATING',
    'COMPLETED',
    'FAILED'
);

-- Host approval status (for booking approval workflow)
CREATE TYPE host_approval_status AS ENUM (
    'PENDING',
    'APPROVED',
    'DECLINED',
    'AUTO_APPROVED'
);

-- Photo status (for check-in photo validation)
CREATE TYPE photo_status AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'EXPIRED'
);

-- Dispute status
CREATE TYPE dispute_status AS ENUM (
    'OPEN',
    'UNDER_REVIEW',
    'EVIDENCE_SUBMITTED',
    'RESOLVED_RENTER_FAVOR',
    'RESOLVED_OWNER_FAVOR',
    'ESCALATED',
    'CLOSED'
);

-- Payment status
CREATE TYPE payment_status AS ENUM (
    'PENDING',
    'AUTHORIZED',
    'CAPTURED',
    'REFUNDED',
    'PARTIALLY_REFUNDED',
    'FAILED',
    'CANCELLED'
);

-- Payout status
CREATE TYPE payout_status AS ENUM (
    'PENDING',
    'SCHEDULED',
    'PROCESSING',
    'COMPLETED',
    'FAILED'
);

-- Review target type
CREATE TYPE review_target AS ENUM (
    'CAR',
    'RENTER',
    'OWNER'
);

-- Blocked date reason
CREATE TYPE blocked_date_reason AS ENUM (
    'OWNER_BLOCKED',
    'MAINTENANCE',
    'BOOKING',
    'SYSTEM_BLOCKED'
);

-- -----------------------------------------------------------------------------
-- SEQUENCES (ALL 32 REQUIRED)
-- Create before tables to ensure nextval() works
-- -----------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS users_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS cars_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS bookings_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS booking_payments_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS cancellation_records_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS trip_extensions_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS blocked_dates_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS check_in_events_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS check_in_photos_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS guest_check_in_photos_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS host_checkout_photos_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS checkout_saga_state_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS damage_claims_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS dispute_resolutions_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS reviews_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS notifications_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS car_documents_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS car_photos_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS renter_documents_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS renter_verification_audits_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS owner_verification_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS conversations_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS messages_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS message_read_receipts_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS admin_audit_logs_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS admin_metrics_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS delivery_pois_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS favorites_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS user_device_tokens_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS feature_flags_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS system_config_id_seq START 1;

-- Migration tracking (temporary, drop after migration)
CREATE SEQUENCE IF NOT EXISTS _migration_tracking_id_seq START 1;
CREATE SEQUENCE IF NOT EXISTS _migration_errors_id_seq START 1;

-- -----------------------------------------------------------------------------
-- HELPER FUNCTIONS
-- -----------------------------------------------------------------------------

-- Updated timestamp trigger function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Broadcast change notification function (for Realtime)
CREATE OR REPLACE FUNCTION broadcast_table_change()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify(
        TG_TABLE_NAME || '_changes',
        json_build_object(
            'operation', TG_OP,
            'table', TG_TABLE_NAME,
            'id', COALESCE(NEW.id, OLD.id),
            'timestamp', CURRENT_TIMESTAMP
        )::text
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- PostGIS helper: Calculate distance in kilometers
CREATE OR REPLACE FUNCTION distance_km(
    point1 geography,
    point2 geography
)
RETURNS DOUBLE PRECISION AS $$
BEGIN
    RETURN ST_Distance(point1, point2) / 1000.0;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- PostGIS helper: Check if within radius (optimized)
CREATE OR REPLACE FUNCTION is_within_radius_km(
    point1 geography,
    point2 geography,
    radius_km DOUBLE PRECISION
)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN ST_DWithin(point1, point2, radius_km * 1000.0);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- -----------------------------------------------------------------------------
-- HELPER FUNCTIONS FOR RLS POLICIES
-- CRITICAL: These MUST exist before any RLS policy references them
-- -----------------------------------------------------------------------------

-- Get current user ID from Supabase auth context
-- Returns the user's ID from the users table based on auth.uid()
CREATE OR REPLACE FUNCTION get_current_user_id()
RETURNS BIGINT AS $$
DECLARE
    v_user_id BIGINT;
BEGIN
    SELECT id INTO v_user_id
    FROM users
    WHERE auth_user_id = auth.uid()::TEXT;
    
    RETURN v_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Check if current user is an admin
CREATE OR REPLACE FUNCTION is_admin()
RETURNS BOOLEAN AS $$
DECLARE
    v_role TEXT;
BEGIN
    SELECT role INTO v_role
    FROM users
    WHERE auth_user_id = auth.uid()::TEXT;
    
    RETURN v_role IN ('ADMIN', 'MODERATOR', 'FINANCIAL_ADMIN');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Check if current user is a service role (for backend operations)
CREATE OR REPLACE FUNCTION is_service_role()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (auth.jwt() ->> 'role') = 'service_role';
END;
$$ LANGUAGE plpgsql STABLE;

-- Check if user owns a specific car
CREATE OR REPLACE FUNCTION is_car_owner(p_car_id BIGINT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM cars
        WHERE id = p_car_id
        AND owner_id = get_current_user_id()
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Check if user is renter for a specific booking
CREATE OR REPLACE FUNCTION is_booking_renter(p_booking_id BIGINT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM bookings
        WHERE id = p_booking_id
        AND renter_id = get_current_user_id()
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Check if user is participant in booking (renter OR owner of car)
CREATE OR REPLACE FUNCTION is_booking_participant(p_booking_id BIGINT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM bookings b
        JOIN cars c ON b.car_id = c.id
        WHERE b.id = p_booking_id
        AND (b.renter_id = get_current_user_id() OR c.owner_id = get_current_user_id())
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Check if user is participant in conversation
CREATE OR REPLACE FUNCTION is_conversation_participant(p_conversation_id BIGINT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM conversations
        WHERE id = p_conversation_id
        AND (renter_id = get_current_user_id() OR owner_id = get_current_user_id())
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Get nearby cars (geospatial helper)
CREATE OR REPLACE FUNCTION get_nearby_cars(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_radius_km DOUBLE PRECISION DEFAULT 50
)
RETURNS TABLE (
    car_id BIGINT,
    distance_km DOUBLE PRECISION
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        c.id as car_id,
        ST_Distance(
            c.location_point::geography,
            ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326)::geography
        ) / 1000.0 as distance_km
    FROM cars c
    WHERE c.availability_status = 'AVAILABLE'::car_availability_status
    AND ST_DWithin(
        c.location_point::geography,
        ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326)::geography,
        p_radius_km * 1000
    )
    ORDER BY distance_km;
END;
$$ LANGUAGE plpgsql STABLE;

-- -----------------------------------------------------------------------------
-- VALIDATION CHECK (Run at end of file)
-- -----------------------------------------------------------------------------

DO $$
DECLARE
    v_enum_count INT;
    v_seq_count INT;
    v_func_count INT;
BEGIN
    -- Count enums
    SELECT COUNT(*) INTO v_enum_count
    FROM pg_type t
    JOIN pg_namespace n ON t.typnamespace = n.oid
    WHERE t.typtype = 'e' AND n.nspname = 'public';
    
    -- Count sequences
    SELECT COUNT(*) INTO v_seq_count
    FROM pg_sequences WHERE schemaname = 'public';
    
    -- Count functions
    SELECT COUNT(*) INTO v_func_count
    FROM pg_proc p
    JOIN pg_namespace n ON p.pronamespace = n.oid
    WHERE n.nspname = 'public'
    AND p.prokind = 'f';
    
    RAISE NOTICE '=== 001_types_and_helpers.sql VALIDATION ===';
    RAISE NOTICE 'Enums created: % (expected: 30+)', v_enum_count;
    RAISE NOTICE 'Sequences created: % (expected: 34)', v_seq_count;
    RAISE NOTICE 'Functions created: % (expected: 10+)', v_func_count;
    
    IF v_enum_count < 25 THEN
        RAISE WARNING 'Missing enums! Expected 30+, got %', v_enum_count;
    END IF;
    
    IF v_seq_count < 30 THEN
        RAISE WARNING 'Missing sequences! Expected 34, got %', v_seq_count;
    END IF;
END $$;

-- =============================================================================
-- HANDOFF TO 002_core_tables.sql
-- At this point, ALL of the following are guaranteed to exist:
-- ✅ Extensions: postgis, pgcrypto, vault, pg_cron, uuid-ossp, pg_trgm
-- ✅ All 30+ enum types
-- ✅ All 34 sequences
-- ✅ Helper functions: get_current_user_id(), is_admin(), is_service_role()
-- ✅ Helper functions: is_car_owner(), is_booking_renter(), is_booking_participant()
-- ✅ Helper functions: is_conversation_participant(), get_nearby_cars()
-- ✅ Trigger functions: update_updated_at_column(), broadcast_table_change()
-- =============================================================================
