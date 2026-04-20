-- ============================================================================
-- V39: Phase 4 Safety Improvements - Turo-Style Check-In/Checkout Hardening
-- ============================================================================
-- Migration for safety improvement features:
--   Phase 4B: In-person license verification tracking
--   Phase 4D: Tiered late fee tracking  
--   Phase 4F: Improper return state tracking
--
-- Author: Safety Improvements Implementation
-- Date: 2024
-- ============================================================================

-- ============================================================================
-- PHASE 4B: IN-PERSON LICENSE VERIFICATION
-- ============================================================================
-- For in-person handshakes (no lockbox), hosts must confirm they have
-- visually verified the guest's driver's license before handshake completion.
-- This is critical for insurance compliance.

ALTER TABLE bookings
    ADD COLUMN license_verified_in_person_at DATETIME(6) NULL
        COMMENT 'Timestamp when host confirmed visual license verification',
    ADD COLUMN license_verified_by_user_id BIGINT NULL
        COMMENT 'User ID of host who performed license verification';

-- Index for audit queries: find bookings where license was verified
CREATE INDEX idx_bookings_license_verified_at 
    ON bookings(license_verified_in_person_at);

-- ============================================================================
-- PHASE 4D: TIERED LATE FEE TRACKING
-- ============================================================================
-- Support for 3-tier late fee structure:
--   Tier 1: First 2 hours (base rate)
--   Tier 2: 2-6 hours (1.5x rate)
--   Tier 3: 6+ hours (2x rate)
--
-- Vehicle not returned flag for 24+ hour overdue vehicles.

ALTER TABLE bookings
    ADD COLUMN late_fee_tier TINYINT NULL
        COMMENT 'Late fee tier applied: 1=first 2h, 2=2-6h, 3=6h+',
    ADD COLUMN vehicle_not_returned_flag TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'Flag for vehicles 24+ hours overdue',
    ADD COLUMN vehicle_not_returned_flagged_at DATETIME(6) NULL
        COMMENT 'When vehicle was flagged as not returned';

-- Constraint to ensure tier is valid
ALTER TABLE bookings
    ADD CONSTRAINT chk_late_fee_tier 
    CHECK (late_fee_tier IS NULL OR late_fee_tier BETWEEN 1 AND 3);

-- Index for escalation queries: find overdue vehicles
CREATE INDEX idx_bookings_vehicle_not_returned 
    ON bookings(vehicle_not_returned_flag, status);

-- ============================================================================
-- PHASE 4F: IMPROPER RETURN STATE
-- ============================================================================
-- Track vehicles returned in improper condition:
--   - LOW_FUEL: Fuel not refilled per agreement
--   - EXCESSIVE_MILEAGE: Miles exceeded estimate by >2x
--   - CLEANING_REQUIRED: Professional cleaning needed
--   - SMOKING_DETECTED: Evidence of smoking
--   - WRONG_LOCATION: Returned to different location

ALTER TABLE bookings
    ADD COLUMN improper_return_flag TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'Flag indicating improper return condition',
    ADD COLUMN improper_return_code VARCHAR(30) NULL
        COMMENT 'Code: LOW_FUEL, EXCESSIVE_MILEAGE, CLEANING_REQUIRED, SMOKING_DETECTED, WRONG_LOCATION',
    ADD COLUMN improper_return_notes TEXT NULL
        COMMENT 'Detailed notes about improper return condition';

-- Constraint to ensure code is valid
ALTER TABLE bookings
    ADD CONSTRAINT chk_improper_return_code
    CHECK (improper_return_code IS NULL OR improper_return_code IN (
        'LOW_FUEL', 'EXCESSIVE_MILEAGE', 'CLEANING_REQUIRED', 
        'SMOKING_DETECTED', 'WRONG_LOCATION'
    ));

-- Index for dispute resolution queries
CREATE INDEX idx_bookings_improper_return 
    ON bookings(improper_return_flag, improper_return_code);

-- ============================================================================
-- PHASE 4E: PHOTO EVIDENCE WEIGHT
-- ============================================================================
-- Track evidence weight for photos based on upload timing.
-- Photos uploaded late are marked as SECONDARY evidence for dispute resolution.

ALTER TABLE check_in_photos
    ADD COLUMN evidence_weight VARCHAR(20) NOT NULL DEFAULT 'PRIMARY'
        COMMENT 'Evidence weight: PRIMARY (on-time) or SECONDARY (late upload)',
    ADD COLUMN evidence_weight_downgraded_at DATETIME(6) NULL
        COMMENT 'When evidence weight was downgraded to SECONDARY',
    ADD COLUMN evidence_weight_downgrade_reason VARCHAR(255) NULL
        COMMENT 'Reason for evidence weight downgrade';

-- Constraint to ensure evidence weight is valid
ALTER TABLE check_in_photos
    ADD CONSTRAINT chk_evidence_weight
    CHECK (evidence_weight IN ('PRIMARY', 'SECONDARY'));

-- Index for dispute queries: find late photos
CREATE INDEX idx_checkin_photos_evidence_weight
    ON check_in_photos(evidence_weight, booking_id);

-- ============================================================================
-- AUDIT TRAIL: Add Phase 4 event types to check_in_events if not using enum
-- ============================================================================
-- Note: If check_in_event_type is stored as VARCHAR, no migration needed.
-- The CheckInEventType Java enum already contains the new event types.
-- This section is for documentation purposes.

-- Phase 4A Events (Check-in Timing):
--   CHECK_IN_BEGUN - Check-in process started
--   CHECK_IN_TIMING_VALIDATED - Timing validation passed
--   EARLY_CHECK_IN_BLOCKED - Blocked due to too-early timing

-- Phase 4B Events (License Verification):
--   LICENSE_VERIFIED_IN_PERSON - Host confirmed visual license check
--   LICENSE_VERIFICATION_SKIPPED - Skipped (lockbox handoff)

-- Phase 4C Events (No-Show Hardening):
--   NO_SHOW_BLOCKED_NO_MESSAGE - No-show blocked without message attempt
--   MESSAGE_ATTEMPT_LOGGED - Message attempt logged before no-show

-- Phase 4D Events (Late Fees):
--   LATE_FEE_TIER_1_APPLIED - First 2 hours late fee
--   LATE_FEE_TIER_2_APPLIED - 2-6 hours late fee
--   LATE_FEE_TIER_3_APPLIED - 6+ hours late fee
--   VEHICLE_NOT_RETURNED_FLAG - 24+ hours overdue flag

-- Phase 4E Events (Photo Deadlines):
--   PHOTO_UPLOAD_LATE - Photo uploaded after deadline
--   PHOTO_EVIDENCE_WEIGHT_REDUCED - Late photo marked as SECONDARY

-- Phase 4F Events (Improper Return):
--   IMPROPER_RETURN_FLAGGED - Vehicle returned improperly

-- Phase 4I Events (Begun Notifications):
--   CHECK_IN_HOST_BEGUN - Host started check-in
--   CHECK_IN_GUEST_BEGUN - Guest started check-in
--   CHECKOUT_GUEST_BEGUN - Guest started checkout

-- ============================================================================
-- DOCUMENTATION: Configuration properties for Phase 4
-- ============================================================================
-- Required application.properties (already added to application-dev.properties):
--
-- # Phase 4A: Check-in Timing
-- app.checkin.max-early-hours=1
-- app.checkin.timing.validation-enabled=true
--
-- # Phase 4B: License Verification
-- app.checkin.license-verification.required=true
-- app.checkin.license-verification.enabled=true
--
-- # Phase 4C: No-Show Hardening
-- app.checkin.noshow.require-message-attempt=true
-- app.checkin.noshow.short-trip-threshold-hours=24
-- app.checkin.noshow.short-trip-grace-minutes=15
-- app.checkin.noshow.long-trip-grace-minutes=30
--
-- # Phase 4D: Tiered Late Fees
-- app.checkout.late.tier1-max-hours=2
-- app.checkout.late.tier1-rate-rsd=500
-- app.checkout.late.tier2-max-hours=6
-- app.checkout.late.tier2-rate-rsd=750
-- app.checkout.late.tier3-rate-rsd=1000
-- app.checkout.vehicle-not-returned-threshold-hours=24
--
-- # Phase 4E: Photo Deadlines
-- app.checkin.photo-upload-deadline-hours=24
-- app.checkout.photo-upload-deadline-hours=24
--
-- # Phase 4I: Begun Notifications
-- app.checkin.begun-notifications.enabled=true
