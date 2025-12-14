-- =====================================================================
-- V30: Owner Verification & Car Document Tracking
-- =====================================================================
-- Implements Serbian market compliance requirements:
-- - Owner type (INDIVIDUAL vs LEGAL_ENTITY)
-- - PIB (Tax ID) and JMBG (Personal ID) tracking (encrypted)
-- - Document upload and verification workflow
-- - 6-month technical inspection validation
-- =====================================================================

-- ===============================
-- ALTER USERS TABLE
-- ===============================

-- Owner type: INDIVIDUAL (requires JMBG) or LEGAL_ENTITY (requires PIB)
ALTER TABLE users ADD COLUMN owner_type VARCHAR(20) DEFAULT 'INDIVIDUAL' NOT NULL;

-- PIB (Tax ID) - 9 digits, encrypted, for legal entities
ALTER TABLE users ADD COLUMN pib_encrypted VARCHAR(255) UNIQUE NULL;

-- JMBG (Personal ID) - 13 digits, encrypted, for individuals
ALTER TABLE users ADD COLUMN jmbg_encrypted VARCHAR(255) UNIQUE NULL;

-- Admin verification flag
ALTER TABLE users ADD COLUMN is_identity_verified BOOLEAN DEFAULT FALSE NOT NULL;

-- When identity was verified
ALTER TABLE users ADD COLUMN identity_verified_at TIMESTAMP NULL;

-- Which admin verified
ALTER TABLE users ADD COLUMN identity_verified_by BIGINT NULL;

-- Bank account for payouts (encrypted)
ALTER TABLE users ADD COLUMN bank_account_number_encrypted VARCHAR(255) NULL;

-- Foreign key for identity_verified_by
ALTER TABLE users ADD CONSTRAINT fk_users_identity_verified_by 
    FOREIGN KEY (identity_verified_by) REFERENCES users(id) ON DELETE SET NULL;

-- Indexes
CREATE INDEX idx_users_owner_type ON users(owner_type);
CREATE INDEX idx_users_is_identity_verified ON users(is_identity_verified);

-- ===============================
-- ALTER CARS TABLE
-- ===============================

-- Document expiry tracking
ALTER TABLE cars ADD COLUMN registration_expiry_date DATE NULL;
ALTER TABLE cars ADD COLUMN technical_inspection_date DATE NULL;
ALTER TABLE cars ADD COLUMN technical_inspection_expiry_date DATE NULL;
ALTER TABLE cars ADD COLUMN insurance_expiry_date DATE NULL;

-- Document verification tracking
ALTER TABLE cars ADD COLUMN documents_verified_at TIMESTAMP NULL;
ALTER TABLE cars ADD COLUMN documents_verified_by BIGINT NULL;

-- Add listing_status column (migrate from approval_status)
ALTER TABLE cars ADD COLUMN listing_status VARCHAR(50) DEFAULT 'PENDING_APPROVAL' NOT NULL;

-- Copy existing approval_status to listing_status with mapping
UPDATE cars SET listing_status = 
    CASE approval_status
        WHEN 'PENDING' THEN 'PENDING_APPROVAL'
        WHEN 'APPROVED' THEN 'APPROVED'
        WHEN 'REJECTED' THEN 'REJECTED'
        WHEN 'SUSPENDED' THEN 'SUSPENDED'
        ELSE 'PENDING_APPROVAL'
    END;

-- Foreign key for documents_verified_by
ALTER TABLE cars ADD CONSTRAINT fk_cars_documents_verified_by 
    FOREIGN KEY (documents_verified_by) REFERENCES users(id) ON DELETE SET NULL;

-- Indexes for expiry queries (for future cron job)
CREATE INDEX idx_cars_listing_status ON cars(listing_status);
CREATE INDEX idx_cars_tech_inspection_expiry ON cars(technical_inspection_expiry_date);
CREATE INDEX idx_cars_registration_expiry ON cars(registration_expiry_date);
CREATE INDEX idx_cars_insurance_expiry ON cars(insurance_expiry_date);

-- ===============================
-- CREATE CAR_DOCUMENTS TABLE
-- ===============================

CREATE TABLE IF NOT EXISTS car_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    
    -- Foreign key to cars
    car_id BIGINT NOT NULL,
    
    -- Document type (REGISTRATION, TECHNICAL_INSPECTION, LIABILITY_INSURANCE, AUTHORIZATION)
    document_type VARCHAR(50) NOT NULL,
    
    -- Local storage path or S3 URL
    document_url VARCHAR(500) NOT NULL,
    
    -- Original filename for display
    original_filename VARCHAR(255) NOT NULL,
    
    -- SHA256 hash for integrity verification
    document_hash VARCHAR(64) NOT NULL,
    
    -- File size in bytes
    file_size BIGINT NOT NULL,
    
    -- MIME type (application/pdf, image/jpeg, etc.)
    mime_type VARCHAR(100) NOT NULL,
    
    -- When owner uploaded
    upload_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- When document expires
    expiry_date DATE NULL,
    
    -- Verification status (PENDING, VERIFIED, REJECTED, EXPIRED_AUTO)
    verification_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    
    -- Admin who verified
    verified_by BIGINT NULL,
    
    -- When verified
    verified_at TIMESTAMP NULL,
    
    -- Rejection reason (if rejected)
    rejection_reason VARCHAR(500) NULL,
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_car_documents_car FOREIGN KEY (car_id) REFERENCES cars(id) ON DELETE CASCADE,
    CONSTRAINT fk_car_documents_verified_by FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL
);

-- Indexes for car_documents
CREATE INDEX idx_car_documents_car_id ON car_documents(car_id);
CREATE INDEX idx_car_documents_type ON car_documents(document_type);
CREATE INDEX idx_car_documents_status ON car_documents(verification_status);
CREATE INDEX idx_car_documents_expiry ON car_documents(expiry_date);
