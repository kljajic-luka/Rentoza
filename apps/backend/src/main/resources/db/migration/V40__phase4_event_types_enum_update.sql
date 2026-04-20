-- ============================================================================
-- V40: Phase 4 Event Types ENUM Update
-- ============================================================================
-- Purpose: Add all Phase 4 and Enterprise Upgrade event types to the 
--          check_in_events.event_type ENUM that were missing from V39.
-- 
-- Problem: V39 only documented the new event types but didn't actually
--          ALTER the ENUM. This caused "Data truncated for column 'event_type'"
--          errors when inserting Phase 4 events.
--
-- Related: CheckInEventType.java (Java enum)
-- ============================================================================

-- MySQL requires listing ALL existing enum values when modifying ENUM columns.
-- We add the missing event types at the end.

ALTER TABLE check_in_events 
    MODIFY COLUMN event_type ENUM(
        -- ========== WINDOW LIFECYCLE ==========
        'CHECK_IN_OPENED',
        'CHECK_IN_REMINDER_SENT',
        
        -- ========== HOST ACTIONS (CHECK-IN) ==========
        'HOST_PHOTO_UPLOADED',
        'HOST_PHOTO_REJECTED',
        'HOST_ODOMETER_SUBMITTED',
        'HOST_FUEL_SUBMITTED',
        'HOST_LOCKBOX_SUBMITTED',
        'HOST_SECTION_COMPLETE',
        
        -- ========== GUEST ACTIONS (CHECK-IN) ==========
        'GUEST_ID_VERIFIED',
        'GUEST_ID_FAILED',
        'GUEST_CONDITION_ACKNOWLEDGED',
        'GUEST_HOTSPOT_MARKED',
        'GUEST_SECTION_COMPLETE',
        
        -- ========== HANDSHAKE ==========
        'HANDSHAKE_HOST_CONFIRMED',
        'HANDSHAKE_GUEST_CONFIRMED',
        'TRIP_STARTED',
        
        -- ========== GEOFENCE ==========
        'GEOFENCE_CHECK_PASSED',
        'GEOFENCE_CHECK_FAILED',
        
        -- ========== LOCATION VARIANCE (Phase 2.4) ==========
        'LOCATION_VARIANCE_WARNING',
        'LOCATION_VARIANCE_BLOCKING',
        
        -- ========== NO-SHOW FLOW ==========
        'NO_SHOW_HOST_TRIGGERED',
        'NO_SHOW_GUEST_TRIGGERED',
        
        -- ========== REMOTE HANDOFF ==========
        'LOCKBOX_CODE_REVEALED',
        
        -- ========== CAR LOCATION DERIVATION (Phase 2) ==========
        'CAR_LOCATION_DERIVED',
        'CAR_LOCATION_MISSING',
        
        -- ========== CHECKOUT ==========
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
        'EARLY_RETURN_INITIATED',
        
        -- ========== DUAL-PARTY PHOTO CAPTURE (Enterprise Phase 1) ==========
        'GUEST_CHECK_IN_PHOTO_UPLOADED',
        'GUEST_CHECK_IN_PHOTO_REJECTED',
        'GUEST_PHOTO_VALIDATION_FAILED',
        'GUEST_CHECK_IN_PHOTOS_COMPLETE',
        'HOST_CHECKOUT_PHOTO_UPLOADED',
        'HOST_CHECKOUT_PHOTO_REJECTED',
        'HOST_CHECKOUT_PHOTOS_COMPLETE',
        
        -- ========== PHOTO DISCREPANCY DETECTION ==========
        'PHOTO_DISCREPANCY_DETECTED',
        'PHOTO_DISCREPANCY_RESOLVED',
        'PHOTO_DISCREPANCY_BLOCKING',
        
        -- ========== OCR VERIFICATION ==========
        'ODOMETER_OCR_EXTRACTED',
        'ODOMETER_OCR_DISCREPANCY',
        
        -- ========== PHASE 4A: CHECK-IN TIMING ==========
        'CHECK_IN_BEGUN',
        'CHECK_IN_TIMING_VALIDATED',
        'EARLY_CHECK_IN_BLOCKED',
        
        -- ========== PHASE 4B: LICENSE VERIFICATION ==========
        'LICENSE_VERIFIED_IN_PERSON',
        'LICENSE_VERIFICATION_SKIPPED',
        
        -- ========== PHASE 4C: NO-SHOW HARDENING ==========
        'NO_SHOW_BLOCKED_NO_MESSAGE',
        'MESSAGE_ATTEMPT_LOGGED',
        
        -- ========== PHASE 4D: TIERED LATE FEES ==========
        'LATE_FEE_TIER_1_APPLIED',
        'LATE_FEE_TIER_2_APPLIED',
        'LATE_FEE_TIER_3_APPLIED',
        'VEHICLE_NOT_RETURNED_FLAG',
        
        -- ========== PHASE 4E: PHOTO TIMING WINDOWS ==========
        'PHOTO_UPLOAD_LATE',
        'PHOTO_EVIDENCE_WEIGHT_REDUCED',
        
        -- ========== PHASE 4F: IMPROPER RETURN ==========
        'IMPROPER_RETURN_FLAGGED',
        
        -- ========== PHASE 4I: BEGUN NOTIFICATIONS ==========
        'CHECK_IN_HOST_BEGUN',
        'CHECK_IN_GUEST_BEGUN',
        'CHECKOUT_GUEST_BEGUN'
    ) NOT NULL;

-- ============================================================================
-- VERIFICATION
-- ============================================================================
-- Run this to verify the enum was updated:
-- SHOW COLUMNS FROM check_in_events WHERE Field = 'event_type';
