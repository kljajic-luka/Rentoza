-- V44__add_audit_storage_key_to_checkin_photos.sql
-- Purpose: Store original photos with EXIF for legal disputes (VAL-001)
-- Author: AI Code Agent
-- Date: 2026-01-31
-- Related Issue: VAL-001 - EXIF GPS Privacy Stripping
--
-- Background:
--   - Public check-in photos have EXIF stripped for privacy (no GPS exposure)
--   - Original photos with EXIF are stored in admin-only audit bucket
--   - This column stores the path to the original photo for dispute resolution
--
-- Access Control:
--   - Audit bucket (checkin-audit) is private, admin-only access
--   - Regular users cannot access audit photos
--   - Only dispute resolution admins can retrieve originals
-- ============================================================================

-- ============================================================================
-- Add audit storage key column
-- ============================================================================

-- Storage path to original photo with EXIF in audit bucket
-- NULL if audit backup is disabled or upload failed
ALTER TABLE check_in_photos
ADD COLUMN IF NOT EXISTS audit_storage_key TEXT;

-- ============================================================================
-- Index for audit queries
-- ============================================================================

-- Partial index for photos that have audit backups
-- Used by admin dispute resolution queries
CREATE INDEX IF NOT EXISTS idx_checkin_photos_audit_key 
ON check_in_photos(audit_storage_key) 
WHERE audit_storage_key IS NOT NULL;

-- ============================================================================
-- Documentation
-- ============================================================================

COMMENT ON COLUMN check_in_photos.audit_storage_key IS 
    'Storage path to original photo with EXIF metadata in admin-only audit bucket. Used for dispute resolution. (VAL-001)';

-- ============================================================================
-- Supabase Bucket Setup (Manual - Run in Supabase Dashboard)
-- ============================================================================
--
-- Step 1: Create audit bucket (private, admin-only)
-- INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
-- VALUES (
--     'checkin-audit', 
--     'checkin-audit', 
--     false,
--     10485760,  -- 10MB limit
--     ARRAY['image/jpeg', 'image/png', 'image/webp']
-- );
--
-- Step 2: Create admin-only read policy
-- CREATE POLICY "Admin read audit photos"
-- ON storage.objects FOR SELECT
-- USING (
--     bucket_id = 'checkin-audit' 
--     AND (
--         auth.jwt()->>'role' = 'ADMIN'
--         OR auth.jwt()->>'role' = 'SUPER_ADMIN'
--     )
-- );
--
-- Step 3: Create service role upload policy
-- CREATE POLICY "Service role upload audit photos"  
-- ON storage.objects FOR INSERT
-- WITH CHECK (
--     bucket_id = 'checkin-audit'
--     AND auth.role() = 'service_role'
-- );
-- ============================================================================

-- ============================================================================
-- Verification Queries
-- ============================================================================
--
-- 1. Verify column exists:
--    SELECT column_name, data_type 
--    FROM information_schema.columns 
--    WHERE table_name = 'check_in_photos' AND column_name = 'audit_storage_key';
--
-- 2. Verify index exists:
--    SELECT indexname FROM pg_indexes WHERE indexname = 'idx_checkin_photos_audit_key';
--
-- 3. Count photos with audit backups (after implementation):
--    SELECT COUNT(*) FROM check_in_photos WHERE audit_storage_key IS NOT NULL;
-- ============================================================================
