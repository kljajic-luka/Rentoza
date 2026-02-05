-- V26__rejected_photo_handling_enums.sql
-- 
-- Adds missing enum values for rejected photo handling infrastructure.
-- Risk: LOW (additive only, no data modification)
-- 
-- Changes:
-- 1. Add VALID_NO_GPS, VALID_WITH_WARNINGS, REJECTED_NO_GPS to exif_validation_status
-- 2. Add HOST_PHOTO_REJECTED to event_type enum
-- 
-- Author: System
-- Date: 2025-12-06
-- ============================================================================

-- ============================================================================
-- SECTION 1: Add missing ExifValidationStatus values to check_in_photos
-- ============================================================================
-- New statuses added for Phase 1: Rejected Photo Infrastructure
-- - VALID_NO_GPS: Valid photo but GPS not present (acceptable when GPS not required)
-- - VALID_WITH_WARNINGS: Valid but has minor warnings (e.g., old device)
-- - REJECTED_NO_GPS: GPS is required but missing from EXIF

ALTER TABLE check_in_photos 
    MODIFY COLUMN exif_validation_status ENUM(
        'PENDING',
        'VALID',
        'VALID_NO_GPS',
        'VALID_WITH_WARNINGS',
        'REJECTED_TOO_OLD',
        'REJECTED_NO_EXIF',
        'REJECTED_LOCATION_MISMATCH',
        'REJECTED_NO_GPS',
        'REJECTED_FUTURE_TIMESTAMP',
        'OVERRIDE_APPROVED'
    ) NOT NULL DEFAULT 'PENDING';


-- ============================================================================
-- SECTION 2: Add HOST_PHOTO_REJECTED to check_in_events event_type
-- ============================================================================
-- This event type is used for audit trail when photos are rejected.
-- Zero-storage policy: rejected photos are NOT stored, but events ARE logged.

ALTER TABLE check_in_events 
    MODIFY COLUMN event_type ENUM(
        -- Window lifecycle
        'CHECK_IN_OPENED',
        'CHECK_IN_REMINDER_SENT',
        
        -- Host actions (check-in)
        'HOST_PHOTO_UPLOADED',
        'HOST_PHOTO_REJECTED',
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
        
        -- Checkout events
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