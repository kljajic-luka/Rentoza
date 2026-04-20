-- ============================================================================
-- V63: Fix GDPR Anonymization Path for Reviews
-- ============================================================================
--
-- P1 FIX: V47 added NOT NULL on reviewer_id, but GDPR anonymization
-- (AdminUserService.anonymizeUserReviews) needs to SET reviewer_id = NULL.
-- The V62 immutability trigger already allows this specific mutation.
--
-- This migration drops the NOT NULL constraint on reviewer_id to allow
-- GDPR-compliant anonymization while keeping the V62 trigger to prevent
-- all other mutations on review content.
--
-- Safety: reviewer_id is still enforced as NOT NULL at the application layer
-- (ReviewService validates reviewer before saving). Only the admin GDPR
-- deletion flow sets it to NULL.
-- ============================================================================

-- Drop NOT NULL constraint on reviewer_id to allow GDPR anonymization
ALTER TABLE reviews
    ALTER COLUMN reviewer_id DROP NOT NULL;

COMMENT ON COLUMN reviews.reviewer_id IS
    'FK to users table. Nullable only for GDPR anonymization (user deletion). '
    'Application layer enforces NOT NULL on creation. V62 trigger prevents arbitrary mutations.';

-- ============================================================================
-- Summary:
-- 1. reviewer_id is now nullable (was NOT NULL since V47)
-- 2. V62 trigger still prevents all UPDATEs except GDPR anonymization
-- 3. Application code still validates reviewer_id on review creation
-- 4. AdminUserService.anonymizeUserReviews() can now set reviewer_id = NULL
-- ============================================================================
