-- =====================================================================
-- V32: Renter Driver License Verification (Serbian Compliance)
-- =====================================================================
-- Implements pre-booking driver license verification:
-- - Renter verification status tracking (profile-level)
-- - Driver license data storage (encrypted)
-- - Renter document uploads (mirroring car_documents pattern)
-- - Audit trail for verification decisions
-- 
-- This matches enterprise standards (Turo/Getaround) for P2P car rental
-- =====================================================================

-- ===============================
-- ALTER USERS TABLE - RENTER VERIFICATION FIELDS
-- ===============================

-- Driver license verification status (NOT_STARTED, PENDING_REVIEW, APPROVED, REJECTED, EXPIRED, SUSPENDED)
ALTER TABLE users ADD COLUMN driver_license_status VARCHAR(20) DEFAULT 'NOT_STARTED' NOT NULL;

-- Driver license number (encrypted, like JMBG/PIB pattern)
ALTER TABLE users ADD COLUMN driver_license_number_encrypted VARCHAR(255) NULL;

-- Hash of driver license for uniqueness checks (SHA-256)
ALTER TABLE users ADD COLUMN driver_license_number_hash VARCHAR(64) UNIQUE NULL;

-- Driver license expiry date (required for booking eligibility)
ALTER TABLE users ADD COLUMN driver_license_expiry_date DATE NULL;

-- Country that issued the driver license (ISO 3166-1 alpha-3, e.g., 'SRB')
ALTER TABLE users ADD COLUMN driver_license_country VARCHAR(3) NULL;

-- How long user has held a valid license (in months, for risk scoring)
ALTER TABLE users ADD COLUMN driver_license_tenure_months INT NULL;

-- When driver license was verified by admin/system
ALTER TABLE users ADD COLUMN driver_license_verified_at TIMESTAMP NULL;

-- Admin/system who verified the driver license
ALTER TABLE users ADD COLUMN driver_license_verified_by BIGINT NULL;

-- Risk level for verification requirements (LOW, MEDIUM, HIGH)
ALTER TABLE users ADD COLUMN risk_level VARCHAR(20) DEFAULT 'MEDIUM' NOT NULL;

-- When risk level was last evaluated
ALTER TABLE users ADD COLUMN last_risk_evaluation_at TIMESTAMP NULL;

-- When renter submitted driver license for verification
ALTER TABLE users ADD COLUMN renter_verification_submitted_at TIMESTAMP NULL;

-- Category of driver license (e.g., 'B', 'C', 'B+E')
ALTER TABLE users ADD COLUMN driver_license_categories VARCHAR(50) NULL;

-- Foreign key for driver_license_verified_by
ALTER TABLE users ADD CONSTRAINT fk_users_driver_license_verified_by 
    FOREIGN KEY (driver_license_verified_by) REFERENCES users(id) ON DELETE SET NULL;

-- Indexes for renter verification queries
CREATE INDEX idx_users_driver_license_status ON users(driver_license_status);
CREATE INDEX idx_users_risk_level ON users(risk_level);
CREATE INDEX idx_users_renter_verification_submitted ON users(renter_verification_submitted_at);
CREATE INDEX idx_users_driver_license_expiry ON users(driver_license_expiry_date);

-- ===============================
-- CREATE RENTER_DOCUMENTS TABLE
-- ===============================
-- Mirrors car_documents structure for consistency

CREATE TABLE IF NOT EXISTS renter_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    
    -- Foreign key to users (the renter uploading the document)
    user_id BIGINT NOT NULL,
    
    -- Document type (DRIVERS_LICENSE_FRONT, DRIVERS_LICENSE_BACK, SELFIE, ID_CARD_FRONT, etc.)
    document_type VARCHAR(50) NOT NULL,
    
    -- Local storage path or S3/Cloudinary URL
    document_url VARCHAR(500) NOT NULL,
    
    -- Original filename for display
    original_filename VARCHAR(255) NOT NULL,
    
    -- SHA256 hash for integrity verification & duplicate detection
    document_hash VARCHAR(64) NOT NULL,
    
    -- File size in bytes
    file_size BIGINT NOT NULL,
    
    -- MIME type (application/pdf, image/jpeg, etc.)
    mime_type VARCHAR(100) NOT NULL,
    
    -- When user uploaded document
    upload_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- When document expires (for licenses)
    expiry_date DATE NULL,
    
    -- Verification status (PENDING, VERIFIED, REJECTED, EXPIRED_AUTO)
    verification_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    
    -- Admin who verified/rejected
    verified_by BIGINT NULL,
    
    -- When verified/rejected
    verified_at TIMESTAMP NULL,
    
    -- Rejection reason (if rejected)
    rejection_reason VARCHAR(500) NULL,
    
    -- OCR extracted data (JSON) - name, number, expiry from document
    ocr_extracted_data JSON NULL,
    
    -- OCR confidence score (0.0-1.0)
    ocr_confidence DECIMAL(3, 2) NULL,
    
    -- Liveness check passed (for selfie documents)
    liveness_passed BOOLEAN NULL,
    
    -- Face match score (selfie vs ID photo, 0.0-1.0)
    face_match_score DECIMAL(3, 2) NULL,
    
    -- Name match score (OCR name vs profile name, 0.0-1.0)
    name_match_score DECIMAL(3, 2) NULL,
    
    -- Processing status for async verification (PENDING, PROCESSING, COMPLETED, FAILED)
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    
    -- Error message if processing failed
    processing_error VARCHAR(500) NULL,
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_renter_documents_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_renter_documents_verified_by FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL
);

-- Indexes for renter_documents
CREATE INDEX idx_renter_documents_user_id ON renter_documents(user_id);
CREATE INDEX idx_renter_documents_type ON renter_documents(document_type);
CREATE INDEX idx_renter_documents_status ON renter_documents(verification_status);
CREATE INDEX idx_renter_documents_expiry ON renter_documents(expiry_date);
CREATE INDEX idx_renter_documents_processing ON renter_documents(processing_status);
CREATE INDEX idx_renter_documents_created ON renter_documents(created_at);

-- Composite index for admin review queue (pending + newest first)
CREATE INDEX idx_renter_documents_pending_queue ON renter_documents(verification_status, created_at DESC);

-- ===============================
-- CREATE RENTER_VERIFICATION_AUDITS TABLE
-- ===============================
-- Compliance audit trail for all verification decisions

CREATE TABLE IF NOT EXISTS renter_verification_audits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    
    -- User whose verification changed
    user_id BIGINT NOT NULL,
    
    -- Document involved (if applicable)
    document_id BIGINT NULL,
    
    -- Actor who made the decision (admin/system)
    actor_id BIGINT NULL,
    
    -- Action taken (SUBMITTED, AUTO_APPROVED, AUTO_REJECTED, MANUAL_APPROVED, MANUAL_REJECTED, EXPIRED, SUSPENDED)
    action VARCHAR(50) NOT NULL,
    
    -- Previous status before action
    previous_status VARCHAR(50) NULL,
    
    -- New status after action
    new_status VARCHAR(50) NOT NULL,
    
    -- Reason/notes for the action
    reason VARCHAR(1000) NULL,
    
    -- Metadata (JSON) - OCR results, risk scores, etc.
    metadata JSON NULL,
    
    -- IP address of actor (for fraud detection)
    ip_address VARCHAR(45) NULL,
    
    -- User agent of actor
    user_agent VARCHAR(500) NULL,
    
    -- Timestamp
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_verification_audits_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_audits_document FOREIGN KEY (document_id) REFERENCES renter_documents(id) ON DELETE SET NULL,
    CONSTRAINT fk_verification_audits_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
);

-- Indexes for audit queries
CREATE INDEX idx_verification_audits_user ON renter_verification_audits(user_id);
CREATE INDEX idx_verification_audits_action ON renter_verification_audits(action);
CREATE INDEX idx_verification_audits_actor ON renter_verification_audits(actor_id);
CREATE INDEX idx_verification_audits_created ON renter_verification_audits(created_at);

-- Composite index for user verification history
CREATE INDEX idx_verification_audits_user_history ON renter_verification_audits(user_id, created_at DESC);
