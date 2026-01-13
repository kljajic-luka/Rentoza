-- =============================================================================
-- COMPREHENSIVE: Create Implicit Casts for ALL PostgreSQL ENUM Types
-- =============================================================================
-- This script creates implicit casts from VARCHAR to all enum types
-- so Hibernate's VARCHAR parameters are auto-cast to native ENUMs
--
-- RUN THIS IN SUPABASE SQL EDITOR (Dashboard → SQL Editor → New Query)
-- =============================================================================

-- Helper function to create cast for any enum type
CREATE OR REPLACE FUNCTION create_varchar_to_enum_cast(enum_type_name text)
RETURNS void AS $$
BEGIN
    -- Drop existing cast if any
    EXECUTE format('DROP CAST IF EXISTS (varchar AS %I)', enum_type_name);
    
    -- Create cast function
    EXECUTE format('
        CREATE OR REPLACE FUNCTION varchar_to_%s(varchar) 
        RETURNS %I AS $func$
            SELECT $1::%I;
        $func$ LANGUAGE SQL IMMUTABLE STRICT;
    ', enum_type_name, enum_type_name, enum_type_name);
    
    -- Create implicit cast
    EXECUTE format('
        CREATE CAST (varchar AS %I) 
        WITH FUNCTION varchar_to_%s(varchar) AS IMPLICIT;
    ', enum_type_name, enum_type_name);
    
    RAISE NOTICE 'Created implicit cast for: %', enum_type_name;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Failed to create cast for %: %', enum_type_name, SQLERRM;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- Create casts for ALL application enum types
-- =============================================================================

-- Admin & User enums
SELECT create_varchar_to_enum_cast('admin_action');
SELECT create_varchar_to_enum_cast('auth_provider');
SELECT create_varchar_to_enum_cast('owner_type');
SELECT create_varchar_to_enum_cast('registration_status');
SELECT create_varchar_to_enum_cast('user_role');
SELECT create_varchar_to_enum_cast('risk_level');
SELECT create_varchar_to_enum_cast('driver_license_status');
SELECT create_varchar_to_enum_cast('user_verification_status');

-- Car enums
SELECT create_varchar_to_enum_cast('approval_status');
SELECT create_varchar_to_enum_cast('listing_status');
SELECT create_varchar_to_enum_cast('fuel_type');
SELECT create_varchar_to_enum_cast('transmission_type');
SELECT create_varchar_to_enum_cast('car_feature');
SELECT create_varchar_to_enum_cast('car_availability_status');
SELECT create_varchar_to_enum_cast('cancellation_policy');
SELECT create_varchar_to_enum_cast('document_type');
SELECT create_varchar_to_enum_cast('document_verification_status');

-- Booking enums
SELECT create_varchar_to_enum_cast('booking_status');
SELECT create_varchar_to_enum_cast('cancelled_by');
SELECT create_varchar_to_enum_cast('cancellation_reason');
SELECT create_varchar_to_enum_cast('refund_status');
SELECT create_varchar_to_enum_cast('trip_extension_status');

-- Check-in/Checkout enums
SELECT create_varchar_to_enum_cast('check_in_actor_role');
SELECT create_varchar_to_enum_cast('check_in_event_type');
SELECT create_varchar_to_enum_cast('check_in_photo_type');
SELECT create_varchar_to_enum_cast('checkout_saga_status');
SELECT create_varchar_to_enum_cast('checkout_saga_step');
SELECT create_varchar_to_enum_cast('evidence_weight');
SELECT create_varchar_to_enum_cast('exif_validation_status');
SELECT create_varchar_to_enum_cast('id_verification_status');
SELECT create_varchar_to_enum_cast('photo_rejection_reason');

-- Dispute & Damage enums
SELECT create_varchar_to_enum_cast('damage_claim_status');
SELECT create_varchar_to_enum_cast('dispute_decision');
SELECT create_varchar_to_enum_cast('dispute_severity');

-- Document enums
SELECT create_varchar_to_enum_cast('renter_document_type');
SELECT create_varchar_to_enum_cast('resource_type');

-- Review enums
SELECT create_varchar_to_enum_cast('review_direction');

-- Notification enums
SELECT create_varchar_to_enum_cast('notification_type');
SELECT create_varchar_to_enum_cast('notification_channel');
SELECT create_varchar_to_enum_cast('message_type');
SELECT create_varchar_to_enum_cast('conversation_status');

-- Delivery enums
SELECT create_varchar_to_enum_cast('poi_type');
SELECT create_varchar_to_enum_cast('insurance_tier');

-- OAuth enums (if used by app)
SELECT create_varchar_to_enum_cast('oauth_authorization_status');
SELECT create_varchar_to_enum_cast('oauth_client_type');
SELECT create_varchar_to_enum_cast('oauth_registration_type');
SELECT create_varchar_to_enum_cast('oauth_response_type');

-- =============================================================================
-- Verify casts were created
-- =============================================================================
SELECT 
    pg_catalog.format_type(castsource, NULL) AS source_type,
    pg_catalog.format_type(casttarget, NULL) AS target_type,
    CASE castcontext 
        WHEN 'i' THEN 'implicit'
        WHEN 'a' THEN 'assignment'
        WHEN 'e' THEN 'explicit'
    END AS cast_context
FROM pg_cast
WHERE pg_catalog.format_type(castsource, NULL) = 'character varying'
ORDER BY target_type;

-- =============================================================================
-- Clean up helper function (optional)
-- =============================================================================
-- DROP FUNCTION IF EXISTS create_varchar_to_enum_cast(text);

-- Done! Check the results above to verify casts were created.
