-- ============================================================================
-- V36: Dual-Party Photos Enterprise Upgrade
-- ============================================================================
-- This migration adds enterprise-grade check-in/checkout photo infrastructure:
-- 1. Guest check-in photos (acceptance confirmation at pickup)
-- 2. Host checkout photos (damage verification at return)
-- 3. Photo discrepancy tracking
-- 4. OCR verification fields for odometer/fuel readings
-- ============================================================================

-- ============================================================================
-- TABLE 1: guest_check_in_photos
-- ============================================================================
-- Mirror of check_in_photos for guest's acceptance confirmation photos.
-- When guest arrives, they capture the SAME 12-point photos as host.
-- This creates bilateral evidence for dispute resolution.

CREATE TABLE IF NOT EXISTS guest_check_in_photos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Foreign key to booking
    booking_id BIGINT NOT NULL,
    
    -- Session correlation (same as host photos)
    check_in_session_id VARCHAR(36) NOT NULL,
    
    -- Photo classification
    photo_type VARCHAR(50) NOT NULL,
    
    -- Storage location
    storage_bucket VARCHAR(50) NOT NULL DEFAULT 'CHECKIN_STANDARD',
    storage_key VARCHAR(500) NOT NULL,
    
    -- File metadata
    original_filename VARCHAR(255),
    mime_type VARCHAR(50) NOT NULL,
    file_size_bytes INT NOT NULL,
    image_width INT,
    image_height INT,
    
    -- EXIF validation (fraud prevention)
    exif_timestamp TIMESTAMP(6) NULL DEFAULT NULL,
    exif_latitude DECIMAL(10, 8),
    exif_longitude DECIMAL(11, 8),
    exif_device_make VARCHAR(100),
    exif_device_model VARCHAR(100),
    exif_validation_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    exif_validation_message VARCHAR(500),
    exif_validated_at TIMESTAMP(6) NULL DEFAULT NULL,
    
    -- Upload tracking
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    client_uploaded_at TIMESTAMP(6) NULL DEFAULT NULL,
    
    -- Soft delete for legal retention
    deleted_at TIMESTAMP(6) NULL DEFAULT NULL,
    deleted_by BIGINT,
    deleted_reason VARCHAR(255),
    
    -- Constraints
    CONSTRAINT fk_guest_checkin_photo_booking 
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_guest_checkin_photo_uploader 
        FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_guest_checkin_photo_deleter 
        FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL,
    
    -- Indexes for query performance
    INDEX idx_guest_checkin_photo_booking (booking_id, photo_type),
    INDEX idx_guest_checkin_photo_session (check_in_session_id),
    INDEX idx_guest_checkin_photo_exif_status (exif_validation_status),
    INDEX idx_guest_checkin_photo_uploader (uploaded_by, uploaded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================================
-- TABLE 2: host_checkout_photos
-- ============================================================================
-- Mirror of checkout photos for host's damage verification.
-- When host receives vehicle, they capture the SAME 12-point photos as guest.
-- This enables side-by-side damage comparison.
CREATE TABLE IF NOT EXISTS host_checkout_photos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Foreign key to booking
    booking_id BIGINT NOT NULL,
    
    -- Session correlation
    checkout_session_id VARCHAR(36) NOT NULL,
    
    -- Photo classification
    photo_type VARCHAR(50) NOT NULL,
    
    -- Storage location
    storage_bucket VARCHAR(50) NOT NULL DEFAULT 'CHECKOUT_STANDARD',
    storage_key VARCHAR(500) NOT NULL,
    
    -- File metadata
    original_filename VARCHAR(255),
    mime_type VARCHAR(50) NOT NULL,
    file_size_bytes INT NOT NULL,
    image_width INT,
    image_height INT,
    
    -- EXIF validation (fraud prevention)
    exif_timestamp TIMESTAMP(6) NULL DEFAULT NULL,
    exif_latitude DECIMAL(10, 8),
    exif_longitude DECIMAL(11, 8),
    exif_device_make VARCHAR(100),
    exif_device_model VARCHAR(100),
    exif_validation_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    exif_validation_message VARCHAR(500),
    exif_validated_at TIMESTAMP(6) NULL DEFAULT NULL,
    
    -- Upload tracking
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    client_uploaded_at TIMESTAMP(6) NULL DEFAULT NULL,
    
    -- Soft delete for legal retention
    deleted_at TIMESTAMP(6) NULL DEFAULT NULL,
    deleted_by BIGINT,
    deleted_reason VARCHAR(255),
    
    -- Constraints
    CONSTRAINT fk_host_checkout_photo_booking 
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_host_checkout_photo_uploader 
        FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_host_checkout_photo_deleter 
        FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL,
    
    -- Indexes for query performance
    INDEX idx_host_checkout_photo_booking (booking_id, photo_type),
    INDEX idx_host_checkout_photo_session (checkout_session_id),
    INDEX idx_host_checkout_photo_exif_status (exif_validation_status),
    INDEX idx_host_checkout_photo_uploader (uploaded_by, uploaded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================================
-- TABLE 3: photo_discrepancies
-- ============================================================================
-- Tracks when host and guest photos show different vehicle conditions.
-- Flagged automatically by system, reviewed by admin if escalated.

CREATE TABLE IF NOT EXISTS photo_discrepancies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Context
    booking_id BIGINT NOT NULL,
    discrepancy_type VARCHAR(50) NOT NULL, -- CHECK_IN, CHECK_OUT
    
    -- Photo references (one or both may be null if one party didn't capture)
    host_photo_id BIGINT,
    guest_photo_id BIGINT,
    
    -- Discrepancy details
    photo_type VARCHAR(50) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'LOW', -- LOW, MEDIUM, HIGH, CRITICAL
    
    -- AI detection (future enhancement)
    ai_confidence_score DECIMAL(3, 2),
    ai_detection_details JSON,
    
    -- Resolution
    resolution_status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING, REVIEWED, DISMISSED, ESCALATED
    resolved_by BIGINT,
    resolved_at TIMESTAMP(6),
    resolution_notes VARCHAR(1000),
    
    -- Audit
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    
    -- Constraints
    CONSTRAINT fk_discrepancy_booking 
        FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_discrepancy_host_photo 
        FOREIGN KEY (host_photo_id) REFERENCES check_in_photos(id) ON DELETE SET NULL,
    CONSTRAINT fk_discrepancy_resolved_by 
        FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL,
    
    -- Indexes
    INDEX idx_discrepancy_booking (booking_id),
    INDEX idx_discrepancy_status (resolution_status),
    INDEX idx_discrepancy_severity (severity, resolution_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================================
-- ALTER bookings: Add OCR verification fields
-- ============================================================================
-- Store both OCR-extracted and user-confirmed values for audit trail

ALTER TABLE bookings
    -- Check-in odometer OCR fields
    ADD COLUMN start_odometer_ocr_value INT NULL COMMENT 'OCR-extracted odometer reading at check-in',
    ADD COLUMN start_odometer_ocr_confidence DECIMAL(4, 3) NULL COMMENT 'OCR confidence score (0.000-1.000)',
    ADD COLUMN start_odometer_photo_id BIGINT NULL COMMENT 'Reference to dashboard photo used for OCR',
    ADD COLUMN start_odometer_discrepancy_flagged BOOLEAN DEFAULT FALSE COMMENT 'True if OCR differs from user input by >10 units',
    
    -- Check-in fuel OCR fields
    ADD COLUMN start_fuel_photo_id BIGINT NULL COMMENT 'Reference to fuel gauge photo',
    
    -- Checkout odometer OCR fields
    ADD COLUMN end_odometer_ocr_value INT NULL COMMENT 'OCR-extracted odometer reading at checkout',
    ADD COLUMN end_odometer_ocr_confidence DECIMAL(4, 3) NULL COMMENT 'OCR confidence score (0.000-1.000)',
    ADD COLUMN end_odometer_photo_id BIGINT NULL COMMENT 'Reference to dashboard photo used for OCR',
    ADD COLUMN end_odometer_discrepancy_flagged BOOLEAN DEFAULT FALSE COMMENT 'True if OCR differs from user input by >10 units',
    
    -- Checkout fuel OCR fields
    ADD COLUMN end_fuel_photo_id BIGINT NULL COMMENT 'Reference to fuel gauge photo',
    
    -- Guest check-in photo completion tracking
    ADD COLUMN guest_checkin_photos_completed_at TIMESTAMP(6) NULL COMMENT 'When guest submitted check-in photos',
    ADD COLUMN guest_checkin_photo_count INT DEFAULT 0 COMMENT 'Number of guest check-in photos',
    
    -- Host checkout photo completion tracking
    ADD COLUMN host_checkout_photos_completed_at TIMESTAMP(6) NULL COMMENT 'When host submitted checkout photos',
    ADD COLUMN host_checkout_photo_count INT DEFAULT 0 COMMENT 'Number of host checkout photos',
    
    -- Discrepancy summary
    ADD COLUMN checkin_discrepancy_count INT DEFAULT 0 COMMENT 'Number of photo discrepancies at check-in',
    ADD COLUMN checkout_discrepancy_count INT DEFAULT 0 COMMENT 'Number of photo discrepancies at checkout';


-- ============================================================================
-- Add foreign key for odometer/fuel photo references
-- ============================================================================
ALTER TABLE bookings
    ADD CONSTRAINT fk_booking_start_odometer_photo 
        FOREIGN KEY (start_odometer_photo_id) REFERENCES check_in_photos(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_booking_start_fuel_photo 
        FOREIGN KEY (start_fuel_photo_id) REFERENCES check_in_photos(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_booking_end_odometer_photo 
        FOREIGN KEY (end_odometer_photo_id) REFERENCES check_in_photos(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_booking_end_fuel_photo 
        FOREIGN KEY (end_fuel_photo_id) REFERENCES check_in_photos(id) ON DELETE SET NULL;


-- ============================================================================
-- Add new indexes for the new columns
-- ============================================================================
CREATE INDEX idx_booking_guest_checkin_photos ON bookings(guest_checkin_photos_completed_at);
CREATE INDEX idx_booking_host_checkout_photos ON bookings(host_checkout_photos_completed_at);
CREATE INDEX idx_booking_discrepancies ON bookings(checkin_discrepancy_count, checkout_discrepancy_count);


-- ============================================================================
-- COMMENTS for documentation
-- ============================================================================
-- This migration establishes the foundation for enterprise-grade check-in/checkout:
--
-- DUAL-PARTY PHOTOS:
-- - guest_check_in_photos: Guest captures same angles as host at pickup
-- - host_checkout_photos: Host captures same angles as guest at return
-- - Creates bilateral evidence for dispute resolution
--
-- PHOTO DISCREPANCIES:
-- - Automatic detection when photos differ between parties
-- - Severity levels for prioritization
-- - Resolution workflow with admin escalation
--
-- OCR VERIFICATION:
-- - Store both machine-extracted and human-confirmed values
-- - Flag discrepancies for review (reduces fraud)
-- - Photo references for audit trail
--
-- MIGRATION STRATEGY:
-- - All new columns are NULLABLE (backward compatible)
-- - Existing bookings continue to work
-- - New features enabled via feature flags
