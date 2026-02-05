-- ============================================================================
-- V14: Check-in Handshake Protocol Schema
-- ============================================================================
-- 
-- Implements the database layer for Turo-style check-in workflow:
-- - Extends bookings table with check-in state fields
-- - Creates immutable audit trail (check_in_events)
-- - Creates photo storage with EXIF validation (check_in_photos)
-- - Creates PII-separated identity verification (check_in_id_verifications)
--
-- State Machine: ACTIVE → CHECK_IN_OPEN → CHECK_IN_HOST_COMPLETE → 
--                CHECK_IN_COMPLETE → IN_TRIP → COMPLETED
--
-- Regional Context: Serbia (Europe/Belgrade timezone, UTF-8 Serbian Latin)
--
-- Author: System Architect
-- Date: 2025-11-28
-- ============================================================================

-- ============================================================================
-- SECTION 1: Extend bookings table with check-in workflow fields
-- ============================================================================

ALTER TABLE bookings
    -- Check-in session tracking
    ADD COLUMN check_in_session_id VARCHAR(36) DEFAULT NULL 
        COMMENT 'UUID generated at T-24h, correlates all check-in events',
    ADD COLUMN check_in_opened_at TIMESTAMP NULL 
        COMMENT 'When check-in window was triggered by scheduler (T-24h)',
    ADD COLUMN host_check_in_completed_at TIMESTAMP NULL 
        COMMENT 'When host completed photo/odometer upload',
    ADD COLUMN guest_check_in_completed_at TIMESTAMP NULL 
        COMMENT 'When guest completed ID verification + condition ack',
    ADD COLUMN handshake_completed_at TIMESTAMP NULL 
        COMMENT 'When both parties confirmed trip start (mutual handshake)',
    
    -- Actual trip timing (may differ from scheduled dates)
    ADD COLUMN trip_started_at TIMESTAMP NULL 
        COMMENT 'Actual trip start timestamp (after handshake)',
    ADD COLUMN trip_ended_at TIMESTAMP NULL 
        COMMENT 'Actual trip end timestamp (for early returns)',
    
    -- Odometer snapshots (fraud prevention / insurance claims)
    ADD COLUMN start_odometer INT UNSIGNED NULL 
        COMMENT 'Odometer reading at trip start (from host photo)',
    ADD COLUMN end_odometer INT UNSIGNED NULL 
        COMMENT 'Odometer reading at trip end (for checkout)',
    
    -- Fuel level snapshots (prepaid refuel validation)
    ADD COLUMN start_fuel_level TINYINT UNSIGNED NULL 
        COMMENT 'Fuel level 0-100 percent at trip start',
    ADD COLUMN end_fuel_level TINYINT UNSIGNED NULL 
        COMMENT 'Fuel level 0-100 percent at trip end',
    
    -- Remote handoff support (lockbox code for keyless pickup)
    ADD COLUMN lockbox_code_encrypted VARBINARY(256) NULL 
        COMMENT 'AES-256-GCM encrypted lockbox code for remote handoff',
    ADD COLUMN lockbox_code_revealed_at TIMESTAMP NULL 
        COMMENT 'When code was decrypted for guest (audit trail)',
    
    -- Geofence validation coordinates
    ADD COLUMN car_latitude DECIMAL(10, 8) NULL 
        COMMENT 'Car location at check-in (from host photo EXIF or manual)',
    ADD COLUMN car_longitude DECIMAL(11, 8) NULL,
    ADD COLUMN host_check_in_latitude DECIMAL(10, 8) NULL 
        COMMENT 'Host device GPS at check-in submission',
    ADD COLUMN host_check_in_longitude DECIMAL(11, 8) NULL,
    ADD COLUMN guest_check_in_latitude DECIMAL(10, 8) NULL 
        COMMENT 'Guest device GPS at check-in (100m geofence check)',
    ADD COLUMN guest_check_in_longitude DECIMAL(11, 8) NULL,
    ADD COLUMN geofence_distance_meters INT NULL 
        COMMENT 'Calculated Haversine distance between guest and car at handshake';

-- Indexes for scheduler queries
CREATE INDEX idx_booking_checkin_window 
    ON bookings (status, start_date, check_in_opened_at)
    COMMENT 'Scheduler: Find ACTIVE bookings needing check-in window trigger';

CREATE INDEX idx_booking_noshow_check 
    ON bookings (status, start_date, host_check_in_completed_at, guest_check_in_completed_at)
    COMMENT 'Scheduler: Find potential no-show candidates post-handshake window';

CREATE INDEX idx_booking_checkin_session 
    ON bookings (check_in_session_id)
    COMMENT 'Lookup by check-in session UUID';


-- ============================================================================
-- SECTION 2: Check-in Event Audit Trail (Immutable Append-Only)
-- ============================================================================
-- This table is designed for insurance claims and dispute resolution.
-- Events are NEVER updated or deleted - enforced by triggers.
-- ============================================================================

CREATE TABLE check_in_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    check_in_session_id VARCHAR(36) NOT NULL,
    
    event_type ENUM(
        -- Window lifecycle
        'CHECK_IN_OPENED',
        'CHECK_IN_REMINDER_SENT',
        
        -- Host actions
        'HOST_PHOTO_UPLOADED',
        'HOST_ODOMETER_SUBMITTED',
        'HOST_FUEL_SUBMITTED',
        'HOST_LOCKBOX_SUBMITTED',
        'HOST_SECTION_COMPLETE',
        
        -- Guest actions
        'GUEST_ID_VERIFIED',
        'GUEST_ID_FAILED',
        'GUEST_CONDITION_ACKNOWLEDGED',
        'GUEST_HOTSPOT_MARKED',
        'GUEST_SECTION_COMPLETE',
        
        -- Handshake
        'HANDSHAKE_HOST_CONFIRMED',
        'HANDSHAKE_GUEST_CONFIRMED',
        'TRIP_STARTED',
        
        -- Geofence
        'GEOFENCE_CHECK_PASSED',
        'GEOFENCE_CHECK_FAILED',
        
        -- No-show flow
        'NO_SHOW_HOST_TRIGGERED',
        'NO_SHOW_GUEST_TRIGGERED',
        
        -- Remote handoff
        'LOCKBOX_CODE_REVEALED',
        
        -- Checkout (future Phase 2)
        'CHECKOUT_INITIATED',
        'CHECKOUT_COMPLETE'
    ) NOT NULL,
    
    actor_id BIGINT NOT NULL COMMENT 'User ID who triggered event (0 for SYSTEM)',
    actor_role ENUM('HOST', 'GUEST', 'SYSTEM') NOT NULL,
    
    -- FIX: Removed (3) from DEFAULT CURRENT_TIMESTAMP for compatibility
    event_timestamp TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP 
        COMMENT 'Server timestamp (Europe/Belgrade)',
        
    client_timestamp TIMESTAMP(3) NULL 
        COMMENT 'Device time for offline sync (may differ from server)',
    
    -- Event-specific metadata (JSON for flexibility)
    metadata JSON NULL COMMENT 'Event-specific data: photo IDs, GPS coords, scores, etc.',
    
    -- Audit fields
    ip_address VARCHAR(45) NULL COMMENT 'IPv4 or IPv6',
    user_agent VARCHAR(500) NULL,
    device_fingerprint VARCHAR(64) NULL COMMENT 'Optional device hash for fraud detection',
    
    -- Foreign key constraint prevents orphan events
    CONSTRAINT fk_checkin_event_booking FOREIGN KEY (booking_id) 
        REFERENCES bookings(id) ON DELETE RESTRICT
    
) ENGINE=InnoDB 
  ROW_FORMAT=COMPRESSED
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable audit log for check-in workflow - insurance/dispute resolution';

-- Add indexes separately to prevent table creation failure if index fails
CREATE INDEX idx_checkin_event_session ON check_in_events (check_in_session_id, event_timestamp);
CREATE INDEX idx_checkin_event_booking ON check_in_events (booking_id, event_type);
CREATE INDEX idx_checkin_event_actor ON check_in_events (actor_id, event_type);

-- Trigger to prevent UPDATE (immutability enforcement)
DELIMITER //
CREATE TRIGGER trg_checkin_events_immutable
BEFORE UPDATE ON check_in_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'IMMUTABLE_VIOLATION: check_in_events rows cannot be updated. This table is append-only.';
END //
DELIMITER ;

-- Trigger to prevent DELETE (immutability enforcement)
DELIMITER //
CREATE TRIGGER trg_checkin_events_nodelete
BEFORE DELETE ON check_in_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'IMMUTABLE_VIOLATION: check_in_events rows cannot be deleted. Contact DBA for compliance-approved purge.';
END //
DELIMITER ;


-- ============================================================================
-- SECTION 3: Check-in Photos (EXIF Validation for Fraud Prevention)
-- ============================================================================
-- Each photo is validated for:
-- - EXIF timestamp (must be within last 30 minutes)
-- - EXIF GPS (must be within 1km of car/pickup location)
-- - File integrity (no camera roll uploads)
-- ============================================================================

CREATE TABLE check_in_photos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    check_in_session_id VARCHAR(36) NOT NULL,
    
    photo_type ENUM(
        -- Required host photos (8 minimum)
        'HOST_EXTERIOR_FRONT',
        'HOST_EXTERIOR_REAR',
        'HOST_EXTERIOR_LEFT',
        'HOST_EXTERIOR_RIGHT',
        'HOST_INTERIOR_DASHBOARD',
        'HOST_INTERIOR_REAR',
        'HOST_ODOMETER',
        'HOST_FUEL_GAUGE',
        
        -- Optional host photos
        'HOST_DAMAGE_PREEXISTING',
        'HOST_CUSTOM',
        
        -- Guest photos
        'GUEST_DAMAGE_NOTED',
        'GUEST_HOTSPOT',
        
        -- Checkout photos (future Phase 2)
        'CHECKOUT_EXTERIOR_FRONT',
        'CHECKOUT_EXTERIOR_REAR',
        'CHECKOUT_EXTERIOR_LEFT',
        'CHECKOUT_EXTERIOR_RIGHT',
        'CHECKOUT_ODOMETER',
        'CHECKOUT_FUEL_GAUGE',
        'CHECKOUT_DAMAGE_NEW'
    ) NOT NULL,
    
    -- Storage configuration
    storage_bucket ENUM('CHECKIN_STANDARD', 'CHECKIN_PII') NOT NULL DEFAULT 'CHECKIN_STANDARD'
        COMMENT 'CHECKIN_PII for ID photos - restricted access, separate encryption',
    storage_key VARCHAR(500) NOT NULL 
        COMMENT 'S3/GCS path: checkin/{sessionId}/{photoType}_{timestamp}.jpg',
    
    -- File metadata
    original_filename VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
    mime_type VARCHAR(50) NOT NULL,
    file_size_bytes INT UNSIGNED NOT NULL,
    image_width INT UNSIGNED NULL,
    image_height INT UNSIGNED NULL,
    
    -- EXIF validation data (fraud prevention)
    exif_timestamp TIMESTAMP NULL COMMENT 'DateTimeOriginal from EXIF',
    exif_latitude DECIMAL(10, 8) NULL COMMENT 'GPS latitude from EXIF',
    exif_longitude DECIMAL(11, 8) NULL COMMENT 'GPS longitude from EXIF',
    exif_device_make VARCHAR(100) NULL,
    exif_device_model VARCHAR(100) NULL,
    
    exif_validation_status ENUM(
        'PENDING',
        'VALID',
        'REJECTED_TOO_OLD',
        'REJECTED_NO_EXIF',
        'REJECTED_LOCATION_MISMATCH',
        'REJECTED_FUTURE_TIMESTAMP',
        'OVERRIDE_APPROVED'
    ) NOT NULL DEFAULT 'PENDING',
    exif_validation_message VARCHAR(500) NULL COMMENT 'Human-readable validation result',
    exif_validated_at TIMESTAMP NULL,
    
    -- Upload metadata
    uploaded_by BIGINT NOT NULL COMMENT 'User ID who uploaded',
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_uploaded_at TIMESTAMP NULL COMMENT 'Device time for offline uploads',
    
    -- Soft delete for compliance (never hard delete photos - legal retention)
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    deleted_reason VARCHAR(255) NULL,
    
    -- Constraints
    CONSTRAINT fk_checkin_photo_booking FOREIGN KEY (booking_id) 
        REFERENCES bookings(id) ON DELETE RESTRICT
    
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Check-in photos with EXIF validation for fraud prevention';

CREATE INDEX idx_checkin_photo_session ON check_in_photos (check_in_session_id, photo_type);
CREATE INDEX idx_checkin_photo_booking ON check_in_photos (booking_id, photo_type);
CREATE INDEX idx_checkin_photo_exif_status ON check_in_photos (exif_validation_status);
CREATE INDEX idx_checkin_photo_uploader ON check_in_photos (uploaded_by, uploaded_at);


-- ============================================================================
-- SECTION 4: Guest Identity Verification (PII-Separated Table)
-- ============================================================================
-- This table contains sensitive PII data:
-- - Biometric liveness scores
-- - Document details (ID type, expiry)
-- - Extracted OCR text from documents
--
-- Access restricted: Only check-in service, not general application.
-- Serbian character handling: Normalized names for Đ↔Dj, Ž↔Z, Č/Ć↔C, Š↔S
-- ============================================================================

CREATE TABLE check_in_id_verifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    check_in_session_id VARCHAR(36) NOT NULL,
    guest_id BIGINT NOT NULL,
    
    -- Liveness check (anti-spoofing)
    liveness_passed BOOLEAN NOT NULL DEFAULT FALSE,
    liveness_score DECIMAL(5, 4) NULL COMMENT 'Confidence 0.0000 to 1.0000',
    liveness_provider VARCHAR(50) NULL COMMENT 'e.g., AWS Rekognition, Onfido, iProov',
    liveness_checked_at TIMESTAMP NULL,
    liveness_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    
    -- Document verification
    document_type ENUM('DRIVERS_LICENSE', 'PASSPORT', 'NATIONAL_ID') NULL,
    document_country VARCHAR(3) NULL COMMENT 'ISO 3166-1 alpha-3 (e.g., SRB)',
    document_expiry DATE NULL,
    document_expiry_valid BOOLEAN NULL COMMENT 'Calculated: expiry > trip end_date',
    
    -- Name matching (Serbian-aware normalization)
    -- Handles: Đorđević ↔ Djordjevic, Živković ↔ Zivkovic, etc.
    extracted_first_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT 'Raw OCR output from document',
    extracted_last_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    extracted_name_normalized VARCHAR(200) NULL 
        COMMENT 'ASCII-normalized for matching (Đorđević→DJORDJEVIC)',
    profile_name_normalized VARCHAR(200) NULL 
        COMMENT 'User profile name normalized for comparison',
    name_match_score DECIMAL(5, 4) NULL 
        COMMENT 'Jaro-Winkler similarity on normalized names',
    name_match_passed BOOLEAN NULL COMMENT 'Calculated: score >= 0.80',
    
    -- Overall verification status
    verification_status ENUM(
        'PENDING',
        'PASSED',
        'FAILED_LIVENESS',
        'FAILED_DOCUMENT_EXPIRED',
        'FAILED_NAME_MISMATCH',
        'FAILED_DOCUMENT_UNREADABLE',
        'FAILED_DOCUMENT_COUNTRY',
        'MANUAL_REVIEW',
        'OVERRIDE_APPROVED'
    ) NOT NULL DEFAULT 'PENDING',
    verification_message VARCHAR(500) NULL COMMENT 'Human-readable status message',
    
    -- PII photo storage (encrypted, separate bucket with restricted access)
    id_photo_front_storage_key VARCHAR(500) NULL COMMENT 'Front of ID document',
    id_photo_back_storage_key VARCHAR(500) NULL COMMENT 'Back of ID document',
    selfie_storage_key VARCHAR(500) NULL COMMENT 'Liveness selfie',
    
    -- Manual review fields
    reviewed_by BIGINT NULL COMMENT 'Admin user ID if manually reviewed',
    reviewed_at TIMESTAMP NULL,
    review_notes TEXT NULL,
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMP NULL,
    
    -- Constraints
    CONSTRAINT fk_id_verification_booking FOREIGN KEY (booking_id) 
        REFERENCES bookings(id) ON DELETE RESTRICT,
    CONSTRAINT uq_id_verification_booking UNIQUE (booking_id) 
        COMMENT 'One verification per booking'
    
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Guest ID verification with biometric matching - PII data (restricted access)';

CREATE INDEX idx_id_verification_guest ON check_in_id_verifications (guest_id);
CREATE INDEX idx_id_verification_status ON check_in_id_verifications (verification_status);
CREATE INDEX idx_id_verification_session ON check_in_id_verifications (check_in_session_id);


-- ============================================================================
-- SECTION 5: Check-in Configuration (Feature Flags & Thresholds)
-- ============================================================================
-- Stores configurable parameters for check-in workflow.
-- Allows runtime adjustment without code deployment.
-- ============================================================================

CREATE TABLE check_in_config (
    config_key VARCHAR(100) PRIMARY KEY,
    config_value VARCHAR(500) NOT NULL,
    config_type ENUM('STRING', 'INTEGER', 'BOOLEAN', 'DECIMAL', 'JSON') NOT NULL DEFAULT 'STRING',
    description VARCHAR(500) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(100) NULL
) ENGINE=InnoDB 
  COMMENT='Runtime configuration for check-in workflow';

-- Insert default configuration values
INSERT INTO check_in_config (config_key, config_value, config_type, description) VALUES
    -- Timing thresholds
    ('checkin.window.hours_before_start', '24', 'INTEGER', 'Hours before trip start to open check-in window'),
    ('checkin.noshow.grace_minutes', '30', 'INTEGER', 'Minutes after scheduled start before no-show is triggered'),
    ('checkin.reminder.hours_before', '12', 'INTEGER', 'Hours before trip to send check-in reminder'),
    
    -- Photo requirements
    ('checkin.photos.host_minimum', '8', 'INTEGER', 'Minimum photos required from host'),
    ('checkin.photos.exif_max_age_minutes', '30', 'INTEGER', 'Max age of EXIF timestamp (fraud prevention)'),
    ('checkin.photos.max_size_mb', '10', 'INTEGER', 'Maximum file size per photo'),
    
    -- Geofence settings
    ('checkin.geofence.radius_meters', '100', 'INTEGER', 'Radius for guest proximity check'),
    ('checkin.geofence.warning_radius_meters', '500', 'INTEGER', 'Show warning if guest is outside this radius'),
    
    -- ID verification thresholds
    ('checkin.id.liveness_threshold', '0.85', 'DECIMAL', 'Minimum liveness score to pass'),
    ('checkin.id.name_match_threshold', '0.80', 'DECIMAL', 'Minimum Jaro-Winkler score for name match'),
    ('checkin.id.max_attempts', '3', 'INTEGER', 'Max liveness check attempts before manual review'),
    
    -- Feature flags
    ('checkin.lockbox.enabled', 'false', 'BOOLEAN', 'Enable remote lockbox handoff feature'),
    ('checkin.id_verification.enabled', 'true', 'BOOLEAN', 'Require ID verification for guests'),
    ('checkin.geofence.strict', 'false', 'BOOLEAN', 'Block check-in if geofence fails (vs warning only)'),
    
    -- Serbia-specific
    ('checkin.timezone', 'Europe/Belgrade', 'STRING', 'Timezone for all check-in calculations'),
    ('checkin.offline.sync_timeout_seconds', '120', 'INTEGER', 'Serbia rural network: extended timeout'),
    ('checkin.offline.queue_ttl_hours', '72', 'INTEGER', 'Hours to retain offline queue (rural sync delays)');
-- ============================================================================
-- VERIFICATION QUERIES (Uncomment to test)
-- ============================================================================
-- 
-- -- Check new columns on bookings
-- DESCRIBE bookings;
-- 
-- -- Check new tables
-- SHOW TABLES LIKE 'check_in%';
-- 
-- -- Verify triggers exist
-- SHOW TRIGGERS WHERE `Table` = 'check_in_events';
-- 
-- -- Verify indexes
-- SHOW INDEX FROM bookings WHERE Key_name LIKE 'idx_booking_checkin%';
-- SHOW INDEX FROM check_in_events;
-- SHOW INDEX FROM check_in_photos;
-- SHOW INDEX FROM check_in_id_verifications;
-- 
-- -- Check configuration
-- SELECT * FROM check_in_config;

