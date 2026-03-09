-- ============================================================================
-- V84: Durable check-in photo upload lifecycle and reconciliation metadata
-- ============================================================================

ALTER TABLE check_in_photos
    ADD COLUMN IF NOT EXISTS upload_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN IF NOT EXISTS upload_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_upload_attempt_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS standard_uploaded_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS upload_finalized_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS last_upload_error VARCHAR(1000) NULL,
    ADD COLUMN IF NOT EXISTS audit_upload_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS audit_uploaded_at TIMESTAMP WITH TIME ZONE NULL;

UPDATE check_in_photos
SET upload_status = 'COMPLETED',
    upload_attempts = CASE WHEN upload_attempts = 0 THEN 1 ELSE upload_attempts END,
    standard_uploaded_at = COALESCE(standard_uploaded_at, uploaded_at),
    upload_finalized_at = COALESCE(upload_finalized_at, uploaded_at),
    audit_upload_status = CASE
        WHEN audit_storage_key = 'AUDIT_UPLOAD_FAILED' THEN 'FAILED'
        WHEN audit_storage_key IS NOT NULL THEN 'COMPLETED'
        ELSE 'NOT_REQUIRED'
    END,
    audit_uploaded_at = CASE
        WHEN audit_storage_key IS NOT NULL AND audit_storage_key <> 'AUDIT_UPLOAD_FAILED'
            THEN COALESCE(audit_uploaded_at, uploaded_at)
        ELSE audit_uploaded_at
    END
WHERE upload_status = 'COMPLETED';

UPDATE check_in_photos
SET audit_storage_key = NULL
WHERE audit_storage_key = 'AUDIT_UPLOAD_FAILED';

CREATE INDEX IF NOT EXISTS idx_checkin_photo_upload_status
    ON check_in_photos (upload_status, last_upload_attempt_at)
    WHERE deleted_at IS NULL;