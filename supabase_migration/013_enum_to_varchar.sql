-- =============================================================================
-- ENUM to VARCHAR Migration for Hibernate/JPA Compatibility
-- =============================================================================
-- PostgreSQL native ENUMs cause type mismatches with Hibernate prepared statements.
-- Converting to VARCHAR with CHECK constraints provides the same validation
-- while being fully compatible with ORMs and connection poolers (PgBouncer).
-- =============================================================================
-- Run this in Supabase SQL Editor
-- =============================================================================

-- =============================================================================
-- STEP 1: DROP RLS POLICIES THAT DEPEND ON ENUM COLUMNS
-- =============================================================================

-- Bookings policies
DROP POLICY IF EXISTS bookings_update_renter ON bookings;
DROP POLICY IF EXISTS bookings_update_owner ON bookings;
DROP POLICY IF EXISTS bookings_select_renter ON bookings;
DROP POLICY IF EXISTS bookings_select_owner ON bookings;
DROP POLICY IF EXISTS bookings_insert_renter ON bookings;
DROP POLICY IF EXISTS bookings_delete_admin ON bookings;

-- Users policies (if any depend on status)
DROP POLICY IF EXISTS users_select_own ON users;
DROP POLICY IF EXISTS users_update_own ON users;

-- Cars policies
DROP POLICY IF EXISTS cars_select_public ON cars;
DROP POLICY IF EXISTS cars_select_owner ON cars;
DROP POLICY IF EXISTS cars_update_owner ON cars;
DROP POLICY IF EXISTS cars_insert_owner ON cars;
DROP POLICY IF EXISTS cars_delete_owner ON cars;

-- Reviews policies
DROP POLICY IF EXISTS reviews_select_public ON reviews;
DROP POLICY IF EXISTS reviews_insert_author ON reviews;
DROP POLICY IF EXISTS reviews_update_author ON reviews;

-- Renter documents policies
DROP POLICY IF EXISTS renter_documents_select_own ON renter_documents;
DROP POLICY IF EXISTS renter_documents_insert_own ON renter_documents;
DROP POLICY IF EXISTS renter_documents_update_own ON renter_documents;

-- Damage claims policies
DROP POLICY IF EXISTS damage_claims_select_involved ON damage_claims;
DROP POLICY IF EXISTS damage_claims_insert_filer ON damage_claims;
DROP POLICY IF EXISTS damage_claims_update_admin ON damage_claims;

-- Checkin photos policies
DROP POLICY IF EXISTS check_in_photos_select_booking_party ON check_in_photos;
DROP POLICY IF EXISTS check_in_photos_insert_booking_party ON check_in_photos;

-- Saga states policies
DROP POLICY IF EXISTS checkout_saga_states_select ON checkout_saga_state;

-- =============================================================================
-- STEP 2: ALTER ENUM COLUMNS TO VARCHAR
-- =============================================================================

-- 1. BOOKING STATUS
ALTER TABLE bookings 
  ALTER COLUMN status TYPE VARCHAR(50) USING status::text;

-- Add CHECK constraint to maintain data integrity
ALTER TABLE bookings 
  ADD CONSTRAINT chk_booking_status CHECK (status IN (
    'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'CHECK_IN_OPEN',
    'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_GUEST_COMPLETE', 'CHECK_IN_COMPLETE',
    'IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_HOST_COMPLETE', 
    'CHECKOUT_GUEST_COMPLETE', 'CHECKOUT_COMPLETE', 'COMPLETED',
    'CANCELLED_BY_RENTER', 'CANCELLED_BY_OWNER', 'CANCELLED_BY_ADMIN',
    'DECLINED', 'EXPIRED', 'DISPUTED', 'NO_SHOW_HOST', 'NO_SHOW_GUEST',
    'PENDING_CHECKOUT', 'CANCELLED', 'EXPIRED_SYSTEM'
  ));

-- 2. USER STATUS  
ALTER TABLE users 
  ALTER COLUMN status TYPE VARCHAR(30) USING status::text;

ALTER TABLE users 
  ADD CONSTRAINT chk_user_status CHECK (status IN (
    'ACTIVE', 'INACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION', 'BANNED'
  ));

-- 3. USER TYPE
ALTER TABLE users 
  ALTER COLUMN user_type TYPE VARCHAR(20) USING user_type::text;

ALTER TABLE users 
  ADD CONSTRAINT chk_user_type CHECK (user_type IN (
    'INDIVIDUAL', 'LEGAL_ENTITY'
  ));

-- 4. VERIFICATION STATUS (users table)
ALTER TABLE users 
  ALTER COLUMN verification_status TYPE VARCHAR(30) USING verification_status::text;

ALTER TABLE users 
  ADD CONSTRAINT chk_verification_status CHECK (verification_status IN (
    'NOT_STARTED', 'DOCUMENTS_SUBMITTED', 'UNDER_REVIEW', 
    'APPROVED', 'REJECTED', 'EXPIRED'
  ));

-- 5. CAR STATUS
ALTER TABLE cars 
  ALTER COLUMN status TYPE VARCHAR(20) USING status::text;

ALTER TABLE cars 
  ADD CONSTRAINT chk_car_status CHECK (status IN (
    'DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'INACTIVE', 'SUSPENDED', 'DELETED'
  ));

-- 6. TRANSMISSION TYPE
ALTER TABLE cars 
  ALTER COLUMN transmission TYPE VARCHAR(20) USING transmission::text;

ALTER TABLE cars 
  ADD CONSTRAINT chk_transmission CHECK (transmission IN (
    'MANUAL', 'AUTOMATIC', 'SEMI_AUTOMATIC'
  ));

-- 7. FUEL TYPE
ALTER TABLE cars 
  ALTER COLUMN fuel_type TYPE VARCHAR(20) USING fuel_type::text;

ALTER TABLE cars 
  ADD CONSTRAINT chk_fuel_type CHECK (fuel_type IN (
    'PETROL', 'DIESEL', 'ELECTRIC', 'HYBRID', 'PLUG_IN_HYBRID', 'LPG', 'CNG'
  ));

-- 8. REVIEW STATUS
ALTER TABLE reviews 
  ALTER COLUMN status TYPE VARCHAR(20) USING status::text;

ALTER TABLE reviews 
  ADD CONSTRAINT chk_review_status CHECK (status IN (
    'PENDING', 'APPROVED', 'REJECTED', 'HIDDEN'
  ));

-- 9. DOCUMENT TYPE (renter_documents)
ALTER TABLE renter_documents 
  ALTER COLUMN document_type TYPE VARCHAR(30) USING document_type::text;

ALTER TABLE renter_documents 
  ADD CONSTRAINT chk_document_type CHECK (document_type IN (
    'DRIVER_LICENSE_FRONT', 'DRIVER_LICENSE_BACK', 'ID_CARD_FRONT', 
    'ID_CARD_BACK', 'PASSPORT', 'SELFIE_WITH_ID'
  ));

-- 10. DOCUMENT VERIFICATION STATUS
ALTER TABLE renter_documents 
  ALTER COLUMN verification_status TYPE VARCHAR(20) USING verification_status::text;

ALTER TABLE renter_documents 
  ADD CONSTRAINT chk_doc_verification_status CHECK (verification_status IN (
    'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'
  ));

-- 11. CHECK-IN/CHECKOUT PHOTO CATEGORIES
ALTER TABLE check_in_photos 
  ALTER COLUMN photo_category TYPE VARCHAR(30) USING photo_category::text;

-- 12. SAGA STATUS
ALTER TABLE checkout_saga_states 
  ALTER COLUMN status TYPE VARCHAR(30) USING status::text;

ALTER TABLE checkout_saga_states 
  ADD CONSTRAINT chk_saga_status CHECK (status IN (
    'INITIATED', 'RUNNING', 'COMPENSATING', 'COMPLETED', 'FAILED', 'ROLLED_BACK'
  ));

-- 13. DAMAGE CLAIM STATUS
ALTER TABLE damage_claims 
  ALTER COLUMN status TYPE VARCHAR(30) USING status::text;

ALTER TABLE damage_claims 
  ADD CONSTRAINT chk_damage_claim_status CHECK (status IN (
    'PENDING', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PAID', 'DISPUTED'
  ));

-- =============================================================================
-- VERIFY: Check all enum columns are now VARCHAR
-- =============================================================================
SELECT 
  table_name, 
  column_name, 
  data_type,
  character_maximum_length
FROM information_schema.columns 
WHERE table_schema = 'public' 
  AND data_type = 'USER-DEFINED'
ORDER BY table_name, column_name;

-- Should return empty if all enums converted successfully

-- =============================================================================
-- STEP 3: RECREATE RLS POLICIES (simplified - adjust based on your 008_rls_policies.sql)
-- =============================================================================

-- Bookings: Renters can view their own bookings
CREATE POLICY bookings_select_renter ON bookings
  FOR SELECT TO authenticated
  USING (renter_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email'));

-- Bookings: Owners can view bookings for their cars  
CREATE POLICY bookings_select_owner ON bookings
  FOR SELECT TO authenticated
  USING (car_id IN (SELECT id FROM cars WHERE owner_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email')));

-- Bookings: Renters can update their pending bookings
CREATE POLICY bookings_update_renter ON bookings
  FOR UPDATE TO authenticated
  USING (
    renter_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email')
    AND status IN ('PENDING_APPROVAL', 'ACTIVE')
  );

-- Bookings: Owners can update bookings for their cars
CREATE POLICY bookings_update_owner ON bookings
  FOR UPDATE TO authenticated
  USING (
    car_id IN (SELECT id FROM cars WHERE owner_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email'))
  );

-- Cars: Public can view active cars
CREATE POLICY cars_select_public ON cars
  FOR SELECT TO anon, authenticated
  USING (status = 'ACTIVE');

-- Cars: Owners can view all their cars
CREATE POLICY cars_select_owner ON cars
  FOR SELECT TO authenticated
  USING (owner_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email'));

-- Cars: Owners can update their cars
CREATE POLICY cars_update_owner ON cars
  FOR UPDATE TO authenticated
  USING (owner_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email'));

-- Reviews: Public can view approved reviews
CREATE POLICY reviews_select_public ON reviews
  FOR SELECT TO anon, authenticated
  USING (status = 'APPROVED');

-- Users: Can view own profile
CREATE POLICY users_select_own ON users
  FOR SELECT TO authenticated
  USING (email = current_setting('request.jwt.claims', true)::json->>'email');

-- Users: Can update own profile
CREATE POLICY users_update_own ON users
  FOR UPDATE TO authenticated
  USING (email = current_setting('request.jwt.claims', true)::json->>'email');

-- Renter documents: Users can view their own documents
CREATE POLICY renter_documents_select_own ON renter_documents
  FOR SELECT TO authenticated
  USING (user_id = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email'));

-- Damage claims: Involved parties can view
CREATE POLICY damage_claims_select_involved ON damage_claims
  FOR SELECT TO authenticated
  USING (
    filed_by = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email')
    OR against = (SELECT id FROM users WHERE email = current_setting('request.jwt.claims', true)::json->>'email')
  );

-- =============================================================================
-- DONE: Migration complete
-- =============================================================================