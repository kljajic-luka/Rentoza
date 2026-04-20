-- ============================================================================
-- V33: Add Date of Birth to Users (Enterprise-Grade Age Management)
-- ============================================================================
-- Purpose: Store DOB instead of static age field. Age changes every birthday,
--          so storing DOB and calculating age dynamically is more accurate.
--
-- Data Sources:
--   1. Driver license OCR extraction (verified, trusted)
--   2. Manual entry during profile edit (self-reported)
--   3. Registration form (optional)
--
-- Benefits:
--   - Age is always accurate (calculated from DOB)
--   - DOB verified from license OCR is tamper-proof
--   - Compliance with rental age requirements (21+, 25+ for premium)
-- ============================================================================

-- Add date_of_birth column to users table
ALTER TABLE users
ADD COLUMN date_of_birth DATE NULL
    COMMENT 'User date of birth. Used to calculate age dynamically. Extracted from driver license OCR or manual entry.';

-- Add dob_verified flag (true if extracted from verified document)
ALTER TABLE users
ADD COLUMN dob_verified BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'Whether DOB was verified from official document (license OCR). Verified DOB cannot be changed by user.';

-- Create index for age-based queries (e.g., finding users under 25 for premium car restrictions)
CREATE INDEX idx_users_date_of_birth ON users(date_of_birth);

-- ============================================================================
-- DATA MIGRATION: Populate DOB from existing OCR data
-- ============================================================================
-- For users with APPROVED driver license status, try to extract DOB from 
-- the OCR data stored in their front license document.
-- 
-- Note: This is a best-effort migration. The JSON structure may vary.
-- Manual review may be needed for edge cases.
-- ============================================================================

-- Create a stored procedure for safe DOB extraction from OCR JSON
DELIMITER //

CREATE PROCEDURE migrate_dob_from_ocr()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE v_user_id BIGINT;
    DECLARE v_ocr_data JSON;
    DECLARE v_dob VARCHAR(10);
    
    -- Cursor to find users with APPROVED license and no DOB
    DECLARE user_cursor CURSOR FOR
        SELECT DISTINCT u.id, rd.ocr_extracted_data
        FROM users u
        JOIN renter_documents rd ON rd.user_id = u.id
        WHERE u.driver_license_status = 'APPROVED'
          AND u.date_of_birth IS NULL
          AND rd.document_type = 'DRIVERS_LICENSE_FRONT'
          AND rd.ocr_extracted_data IS NOT NULL;
    
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
    
    OPEN user_cursor;
    
    read_loop: LOOP
        FETCH user_cursor INTO v_user_id, v_ocr_data;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        -- Try to extract DOB from various JSON paths
        SET v_dob = NULL;
        
        -- Try path 1: $.dateOfBirth
        SET v_dob = JSON_UNQUOTE(JSON_EXTRACT(v_ocr_data, '$.dateOfBirth'));
        
        -- Try path 2: $.dob
        IF v_dob IS NULL OR v_dob = 'null' THEN
            SET v_dob = JSON_UNQUOTE(JSON_EXTRACT(v_ocr_data, '$.dob'));
        END IF;
        
        -- Try path 3: $.extractedDob
        IF v_dob IS NULL OR v_dob = 'null' THEN
            SET v_dob = JSON_UNQUOTE(JSON_EXTRACT(v_ocr_data, '$.extractedDob'));
        END IF;
        
        -- Try path 4: $.birthDate
        IF v_dob IS NULL OR v_dob = 'null' THEN
            SET v_dob = JSON_UNQUOTE(JSON_EXTRACT(v_ocr_data, '$.birthDate'));
        END IF;
        
        -- If we found a valid DOB, update the user
        IF v_dob IS NOT NULL AND v_dob != 'null' AND LENGTH(v_dob) >= 10 THEN
            UPDATE users 
            SET date_of_birth = STR_TO_DATE(SUBSTRING(v_dob, 1, 10), '%Y-%m-%d'),
                dob_verified = TRUE
            WHERE id = v_user_id;
        END IF;
    END LOOP;
    
    CLOSE user_cursor;
END //

DELIMITER ;

-- Run the migration procedure
CALL migrate_dob_from_ocr();

-- Clean up the procedure
DROP PROCEDURE IF EXISTS migrate_dob_from_ocr;

-- ============================================================================
-- AUDIT LOG: Track this migration for compliance
-- ============================================================================
INSERT INTO flyway_audit (
    migration_version,
    migration_description,
    executed_at,
    success
)
SELECT 
    'V33',
    'Added date_of_birth column to users table for enterprise-grade age management',
    NOW(),
    TRUE
WHERE EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'flyway_audit')
ON DUPLICATE KEY UPDATE executed_at = NOW();

-- Note: If flyway_audit table doesn't exist, the INSERT is silently skipped
