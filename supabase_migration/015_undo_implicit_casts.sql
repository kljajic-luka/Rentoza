-- =============================================================================
-- UNDO: Remove all implicit casts created by 014_create_all_implicit_casts.sql
-- =============================================================================
-- Run this in Supabase SQL Editor to remove the VARCHAR->ENUM casts
-- =============================================================================

-- Drop casts and their functions for all enum types

-- Admin & User enums
DROP CAST IF EXISTS (varchar AS admin_action);
DROP FUNCTION IF EXISTS varchar_to_admin_action(varchar);

DROP CAST IF EXISTS (varchar AS auth_provider);
DROP FUNCTION IF EXISTS varchar_to_auth_provider(varchar);

DROP CAST IF EXISTS (varchar AS owner_type);
DROP FUNCTION IF EXISTS varchar_to_owner_type(varchar);

DROP CAST IF EXISTS (varchar AS registration_status);
DROP FUNCTION IF EXISTS varchar_to_registration_status(varchar);

DROP CAST IF EXISTS (varchar AS user_role);
DROP FUNCTION IF EXISTS varchar_to_user_role(varchar);

DROP CAST IF EXISTS (varchar AS risk_level);
DROP FUNCTION IF EXISTS varchar_to_risk_level(varchar);

DROP CAST IF EXISTS (varchar AS driver_license_status);
DROP FUNCTION IF EXISTS varchar_to_driver_license_status(varchar);

DROP CAST IF EXISTS (varchar AS user_verification_status);
DROP FUNCTION IF EXISTS varchar_to_user_verification_status(varchar);

-- Car enums
DROP CAST IF EXISTS (varchar AS approval_status);
DROP FUNCTION IF EXISTS varchar_to_approval_status(varchar);

DROP CAST IF EXISTS (varchar AS listing_status);
DROP FUNCTION IF EXISTS varchar_to_listing_status(varchar);

DROP CAST IF EXISTS (varchar AS fuel_type);
DROP FUNCTION IF EXISTS varchar_to_fuel_type(varchar);

DROP CAST IF EXISTS (varchar AS transmission_type);
DROP FUNCTION IF EXISTS varchar_to_transmission_type(varchar);

DROP CAST IF EXISTS (varchar AS car_feature);
DROP FUNCTION IF EXISTS varchar_to_car_feature(varchar);

DROP CAST IF EXISTS (varchar AS car_availability_status);
DROP FUNCTION IF EXISTS varchar_to_car_availability_status(varchar);

DROP CAST IF EXISTS (varchar AS cancellation_policy);
DROP FUNCTION IF EXISTS varchar_to_cancellation_policy(varchar);

DROP CAST IF EXISTS (varchar AS document_type);
DROP FUNCTION IF EXISTS varchar_to_document_type(varchar);

DROP CAST IF EXISTS (varchar AS document_verification_status);
DROP FUNCTION IF EXISTS varchar_to_document_verification_status(varchar);

-- Booking enums
DROP CAST IF EXISTS (varchar AS booking_status);
DROP FUNCTION IF EXISTS varchar_to_booking_status(varchar);

DROP CAST IF EXISTS (varchar AS cancelled_by);
DROP FUNCTION IF EXISTS varchar_to_cancelled_by(varchar);

DROP CAST IF EXISTS (varchar AS cancellation_reason);
DROP FUNCTION IF EXISTS varchar_to_cancellation_reason(varchar);

DROP CAST IF EXISTS (varchar AS refund_status);
DROP FUNCTION IF EXISTS varchar_to_refund_status(varchar);

DROP CAST IF EXISTS (varchar AS trip_extension_status);
DROP FUNCTION IF EXISTS varchar_to_trip_extension_status(varchar);

-- Check-in/Checkout enums
DROP CAST IF EXISTS (varchar AS check_in_actor_role);
DROP FUNCTION IF EXISTS varchar_to_check_in_actor_role(varchar);

DROP CAST IF EXISTS (varchar AS check_in_event_type);
DROP FUNCTION IF EXISTS varchar_to_check_in_event_type(varchar);

DROP CAST IF EXISTS (varchar AS check_in_photo_type);
DROP FUNCTION IF EXISTS varchar_to_check_in_photo_type(varchar);

DROP CAST IF EXISTS (varchar AS checkout_saga_status);
DROP FUNCTION IF EXISTS varchar_to_checkout_saga_status(varchar);

DROP CAST IF EXISTS (varchar AS checkout_saga_step);
DROP FUNCTION IF EXISTS varchar_to_checkout_saga_step(varchar);

DROP CAST IF EXISTS (varchar AS evidence_weight);
DROP FUNCTION IF EXISTS varchar_to_evidence_weight(varchar);

DROP CAST IF EXISTS (varchar AS exif_validation_status);
DROP FUNCTION IF EXISTS varchar_to_exif_validation_status(varchar);

DROP CAST IF EXISTS (varchar AS id_verification_status);
DROP FUNCTION IF EXISTS varchar_to_id_verification_status(varchar);

DROP CAST IF EXISTS (varchar AS photo_rejection_reason);
DROP FUNCTION IF EXISTS varchar_to_photo_rejection_reason(varchar);

-- Dispute & Damage enums
DROP CAST IF EXISTS (varchar AS damage_claim_status);
DROP FUNCTION IF EXISTS varchar_to_damage_claim_status(varchar);

DROP CAST IF EXISTS (varchar AS dispute_decision);
DROP FUNCTION IF EXISTS varchar_to_dispute_decision(varchar);

DROP CAST IF EXISTS (varchar AS dispute_severity);
DROP FUNCTION IF EXISTS varchar_to_dispute_severity(varchar);

-- Document enums
DROP CAST IF EXISTS (varchar AS renter_document_type);
DROP FUNCTION IF EXISTS varchar_to_renter_document_type(varchar);

DROP CAST IF EXISTS (varchar AS resource_type);
DROP FUNCTION IF EXISTS varchar_to_resource_type(varchar);

-- Review enums
DROP CAST IF EXISTS (varchar AS review_direction);
DROP FUNCTION IF EXISTS varchar_to_review_direction(varchar);

-- Notification enums
DROP CAST IF EXISTS (varchar AS notification_type);
DROP FUNCTION IF EXISTS varchar_to_notification_type(varchar);

DROP CAST IF EXISTS (varchar AS notification_channel);
DROP FUNCTION IF EXISTS varchar_to_notification_channel(varchar);

DROP CAST IF EXISTS (varchar AS message_type);
DROP FUNCTION IF EXISTS varchar_to_message_type(varchar);

DROP CAST IF EXISTS (varchar AS conversation_status);
DROP FUNCTION IF EXISTS varchar_to_conversation_status(varchar);

-- Delivery enums
DROP CAST IF EXISTS (varchar AS poi_type);
DROP FUNCTION IF EXISTS varchar_to_poi_type(varchar);

DROP CAST IF EXISTS (varchar AS insurance_tier);
DROP FUNCTION IF EXISTS varchar_to_insurance_tier(varchar);

-- OAuth enums
DROP CAST IF EXISTS (varchar AS oauth_authorization_status);
DROP FUNCTION IF EXISTS varchar_to_oauth_authorization_status(varchar);

DROP CAST IF EXISTS (varchar AS oauth_client_type);
DROP FUNCTION IF EXISTS varchar_to_oauth_client_type(varchar);

DROP CAST IF EXISTS (varchar AS oauth_registration_type);
DROP FUNCTION IF EXISTS varchar_to_oauth_registration_type(varchar);

DROP CAST IF EXISTS (varchar AS oauth_response_type);
DROP FUNCTION IF EXISTS varchar_to_oauth_response_type(varchar);

-- Drop the helper function
DROP FUNCTION IF EXISTS create_varchar_to_enum_cast(text);

-- Verify all casts are removed
SELECT 
    pg_catalog.format_type(castsource, NULL) AS source_type,
    pg_catalog.format_type(casttarget, NULL) AS target_type
FROM pg_cast
WHERE pg_catalog.format_type(castsource, NULL) = 'character varying'
  AND pg_catalog.format_type(casttarget, NULL) NOT IN ('text', 'name', 'character', '"char"');

-- Should return empty or only system casts
