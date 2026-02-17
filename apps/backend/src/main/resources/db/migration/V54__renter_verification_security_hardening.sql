-- =============================================================================
-- V54: Renter Verification Security Hardening
-- =============================================================================
-- Addresses audit findings for Turo-standard driver license verification:
--   1. Unique constraint on document_hash (prevents cross-user duplicate fraud)
--   2. Quality flag columns for OCR confidence tracking
--   3. Face match passed tracking column
-- =============================================================================

-- ============================================================================
-- 1. UNIQUE INDEX ON DOCUMENT HASH (fraud prevention)
-- ============================================================================
-- Prevents the same document image from being submitted by different users.
-- Application already checks via existsByDocumentHashForDifferentUser(),
-- but a DB constraint provides defense-in-depth.
-- Partial index: only enforces uniqueness where hash is non-null.
CREATE UNIQUE INDEX IF NOT EXISTS idx_renter_documents_hash_unique
    ON renter_documents (document_hash)
    WHERE document_hash IS NOT NULL;

COMMENT ON INDEX idx_renter_documents_hash_unique IS
    'Prevents duplicate document fraud — same image hash cannot exist for multiple users';

-- ============================================================================
-- 2. QUALITY FLAG COLUMNS (low OCR confidence / blurry photo tracking)
-- ============================================================================
ALTER TABLE renter_documents ADD COLUMN IF NOT EXISTS quality_flag VARCHAR(50) NULL;
ALTER TABLE renter_documents ADD COLUMN IF NOT EXISTS quality_flag_reason VARCHAR(500) NULL;

CREATE INDEX IF NOT EXISTS idx_renter_documents_quality_flag
    ON renter_documents (quality_flag)
    WHERE quality_flag IS NOT NULL;

COMMENT ON COLUMN renter_documents.quality_flag IS
    'LOW_CONFIDENCE, BLURRY, EDITED, etc. — set during OCR processing';
COMMENT ON COLUMN renter_documents.quality_flag_reason IS
    'Human-readable explanation for quality flag';

-- ============================================================================
-- 3. FACE MATCH PASSED TRACKING
-- ============================================================================
ALTER TABLE renter_documents ADD COLUMN IF NOT EXISTS face_match_passed BOOLEAN NULL;

COMMENT ON COLUMN renter_documents.face_match_passed IS
    'Whether face match between selfie and license photo met threshold (0.95)';
