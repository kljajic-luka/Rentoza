-- ============================================================================
-- V15: Check-out Workflow Schema
-- ============================================================================
-- 
-- Implements the database layer for Turo-style check-out workflow:
-- - Extends bookings table with check-out state fields
-- - Adds checkout-specific event types
-- - Adds damage assessment support
--
-- State Machine Extension: 
--   IN_TRIP → CHECKOUT_OPEN → CHECKOUT_GUEST_COMPLETE → 
--   CHECKOUT_HOST_COMPLETE → COMPLETED
--
-- Regional Context: Serbia (Europe/Belgrade timezone, UTF-8 Serbian Latin)
--
-- Author: System Architect
-- Date: 2025-12-02
-- ============================================================================

-- ============================================================================
-- SECTION 1: Extend bookings table with check-out workflow fields
-- ============================================================================

ALTER TABLE bookings
    -- Check-out session tracking (separate from check-in session)
    ADD COLUMN checkout_session_id VARCHAR(36) DEFAULT NULL 
        COMMENT 'UUID generated at checkout initiation, correlates all checkout events',
    ADD COLUMN checkout_opened_at TIMESTAMP NULL 
        COMMENT 'When checkout window was triggered (trip end time or early return)',
    ADD COLUMN guest_checkout_completed_at TIMESTAMP NULL 
        COMMENT 'When guest completed return photos/odometer upload',
    ADD COLUMN host_checkout_completed_at TIMESTAMP NULL 
        COMMENT 'When host confirmed vehicle return and condition',
    ADD COLUMN checkout_completed_at TIMESTAMP NULL 
        COMMENT 'When checkout process fully completed',
    
    -- Checkout location tracking
    ADD COLUMN guest_checkout_latitude DECIMAL(10, 8) NULL 
        COMMENT 'Guest device GPS at checkout submission',
    ADD COLUMN guest_checkout_longitude DECIMAL(11, 8) NULL,
    ADD COLUMN host_checkout_latitude DECIMAL(10, 8) NULL 
        COMMENT 'Host device GPS at checkout confirmation',
    ADD COLUMN host_checkout_longitude DECIMAL(11, 8) NULL,
    
    -- Damage assessment
    ADD COLUMN new_damage_reported BOOLEAN DEFAULT FALSE 
        COMMENT 'Flag if host reported new damage at checkout',
    ADD COLUMN damage_assessment_notes TEXT NULL 
        COMMENT 'Host notes about new damage found at return',
    ADD COLUMN damage_claim_amount DECIMAL(19, 2) NULL 
        COMMENT 'Estimated damage cost in RSD',
    ADD COLUMN damage_claim_status VARCHAR(20) DEFAULT NULL 
        COMMENT 'PENDING, APPROVED, REJECTED, PAID',
    
    -- Late return tracking
    ADD COLUMN scheduled_return_time TIMESTAMP NULL 
        COMMENT 'Expected return time based on booking end date',
    ADD COLUMN actual_return_time TIMESTAMP NULL 
        COMMENT 'Actual time guest returned vehicle',
    ADD COLUMN late_return_minutes INT NULL 
        COMMENT 'Minutes past scheduled return (negative if early)',
    ADD COLUMN late_fee_amount DECIMAL(19, 2) NULL 
        COMMENT 'Late return fee in RSD';

-- Index for checkout queries
CREATE INDEX idx_booking_checkout_session 
    ON bookings (checkout_session_id)
    COMMENT 'Lookup by checkout session UUID';

CREATE INDEX idx_booking_checkout_status 
    ON bookings (status, checkout_opened_at, guest_checkout_completed_at)
    COMMENT 'Find bookings in checkout phase';


-- ============================================================================
-- SECTION 2: Add checkout event types to check_in_events table
-- ============================================================================
-- Note: MySQL doesn't support ALTER ENUM easily, so we need to recreate
-- For this migration, we'll add new event types that work with existing enum

-- First, let's modify the event_type column to include checkout events
ALTER TABLE check_in_events 
    MODIFY COLUMN event_type ENUM(
        -- Window lifecycle
        'CHECK_IN_OPENED',
        'CHECK_IN_REMINDER_SENT',
        
        -- Host actions (check-in)
        'HOST_PHOTO_UPLOADED',
        'HOST_ODOMETER_SUBMITTED',
        'HOST_FUEL_SUBMITTED',
        'HOST_LOCKBOX_SUBMITTED',
        'HOST_SECTION_COMPLETE',
        
        -- Guest actions (check-in)
        'GUEST_ID_VERIFIED',
        'GUEST_ID_FAILED',
        'GUEST_CONDITION_ACKNOWLEDGED',
        'GUEST_HOTSPOT_MARKED',
        'GUEST_SECTION_COMPLETE',
        
        -- Handshake
        'HANDSHAKE_HOST_CONFIRMED',
        'HANDSHAKE_GUEST_CONFIRMED',
        'TRIP_STARTED',
        
        -- Geofence
        'GEOFENCE_CHECK_PASSED',
        'GEOFENCE_CHECK_FAILED',
        
        -- No-show flow
        'NO_SHOW_HOST_TRIGGERED',
        'NO_SHOW_GUEST_TRIGGERED',
        
        -- Remote handoff
        'LOCKBOX_CODE_REVEALED',
        
        -- Checkout events (NEW)
        'CHECKOUT_INITIATED',
        'CHECKOUT_GUEST_PHOTO_UPLOADED',
        'CHECKOUT_GUEST_ODOMETER_SUBMITTED',
        'CHECKOUT_GUEST_FUEL_SUBMITTED',
        'CHECKOUT_GUEST_SECTION_COMPLETE',
        'CHECKOUT_HOST_CONFIRMED',
        'CHECKOUT_HOST_DAMAGE_REPORTED',
        'CHECKOUT_DISPUTE_OPENED',
        'CHECKOUT_COMPLETE',
        'LATE_RETURN_DETECTED',
        'EARLY_RETURN_INITIATED'
    ) NOT NULL;


-- ============================================================================
-- SECTION 3: Add checkout photo types to check_in_photos table
-- ============================================================================

ALTER TABLE check_in_photos 
    MODIFY COLUMN photo_type ENUM(
        -- Required host photos (8 minimum) - CHECK-IN
        'HOST_EXTERIOR_FRONT',
        'HOST_EXTERIOR_REAR',
        'HOST_EXTERIOR_LEFT',
        'HOST_EXTERIOR_RIGHT',
        'HOST_INTERIOR_DASHBOARD',
        'HOST_INTERIOR_REAR',
        'HOST_ODOMETER',
        'HOST_FUEL_GAUGE',
        
        -- Optional host photos - CHECK-IN
        'HOST_DAMAGE_PREEXISTING',
        'HOST_CUSTOM',
        
        -- Guest photos - CHECK-IN
        'GUEST_DAMAGE_NOTED',
        'GUEST_HOTSPOT',
        
        -- Guest checkout photos (NEW)
        'CHECKOUT_EXTERIOR_FRONT',
        'CHECKOUT_EXTERIOR_REAR',
        'CHECKOUT_EXTERIOR_LEFT',
        'CHECKOUT_EXTERIOR_RIGHT',
        'CHECKOUT_INTERIOR_DASHBOARD',
        'CHECKOUT_INTERIOR_REAR',
        'CHECKOUT_ODOMETER',
        'CHECKOUT_FUEL_GAUGE',
        'CHECKOUT_DAMAGE_NEW',
        'CHECKOUT_CUSTOM',
        
        -- Host checkout confirmation photos (NEW)
        'HOST_CHECKOUT_CONFIRMATION',
        'HOST_CHECKOUT_DAMAGE_EVIDENCE'
    ) NOT NULL;


-- ============================================================================
-- SECTION 4: Add checkout configuration values
-- ============================================================================

INSERT INTO check_in_config (config_key, config_value, config_type, description) VALUES
    -- Checkout timing
    ('checkout.window.hours_before_end', '2', 'INTEGER', 'Hours before trip end to enable early checkout'),
    ('checkout.late.grace_minutes', '15', 'INTEGER', 'Grace period before late fees apply'),
    ('checkout.late.fee_per_hour_rsd', '500', 'INTEGER', 'Late fee per hour in RSD'),
    ('checkout.late.max_hours', '24', 'INTEGER', 'Maximum late hours before escalation'),
    
    -- Checkout photo requirements
    ('checkout.photos.guest_minimum', '6', 'INTEGER', 'Minimum photos required from guest at return'),
    
    -- Damage assessment
    ('checkout.damage.auto_compare', 'true', 'BOOLEAN', 'Auto-compare check-in vs checkout photos'),
    ('checkout.damage.notification_threshold_rsd', '5000', 'INTEGER', 'RSD threshold for damage notification');


