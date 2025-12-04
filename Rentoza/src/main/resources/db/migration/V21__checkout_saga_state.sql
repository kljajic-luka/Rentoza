-- ========================================================================
-- V21: Checkout Saga State Persistence
-- ========================================================================
-- Phase 2 Architecture Improvement: Saga Pattern for Checkout
-- 
-- This migration creates the saga state table for tracking
-- multi-step checkout workflow with compensation support.
--
-- Features:
-- - Unique saga ID for correlation
-- - Step tracking for resumption
-- - Compensation tracking for rollback
-- - Charge calculation storage
-- - Payment transaction references
-- ========================================================================

CREATE TABLE IF NOT EXISTS checkout_saga_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Unique saga identifier
    saga_id VARCHAR(36) NOT NULL,
    
    -- Reference to booking
    booking_id BIGINT NOT NULL,
    
    -- Saga status
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    
    -- Current step being processed
    current_step VARCHAR(30),
    
    -- Last successfully completed step
    last_completed_step VARCHAR(30),
    
    -- Step where failure occurred
    failed_at_step VARCHAR(30),
    
    -- Error message if failed
    error_message VARCHAR(1000),
    
    -- ========== CALCULATED CHARGES ==========
    
    extra_mileage_km INT,
    extra_mileage_charge DECIMAL(10, 2),
    
    fuel_difference_percent INT,
    fuel_charge DECIMAL(10, 2),
    
    late_hours INT,
    late_fee DECIMAL(10, 2),
    
    total_charges DECIMAL(10, 2),
    
    -- ========== PAYMENT DATA ==========
    
    captured_amount DECIMAL(10, 2),
    capture_transaction_id VARCHAR(100),
    
    released_amount DECIMAL(10, 2),
    release_transaction_id VARCHAR(100),
    
    -- ========== TIMESTAMPS ==========
    
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    
    -- ========== RETRY TRACKING ==========
    
    retry_count INT DEFAULT 0,
    last_retry_at TIMESTAMP(6),
    
    -- Optimistic locking
    version BIGINT DEFAULT 0,
    
    -- ========== CONSTRAINTS ==========
    
    CONSTRAINT uk_saga_id UNIQUE (saga_id),
    
    CONSTRAINT chk_saga_status CHECK (status IN (
        'PENDING', 'RUNNING', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED'
    )),
    
    CONSTRAINT chk_saga_step CHECK (current_step IS NULL OR current_step IN (
        'VALIDATE_RETURN', 'CALCULATE_CHARGES', 'CAPTURE_DEPOSIT', 
        'RELEASE_DEPOSIT', 'COMPLETE_BOOKING'
    )),
    
    CONSTRAINT chk_saga_completed_step CHECK (last_completed_step IS NULL OR last_completed_step IN (
        'VALIDATE_RETURN', 'CALCULATE_CHARGES', 'CAPTURE_DEPOSIT', 
        'RELEASE_DEPOSIT', 'COMPLETE_BOOKING'
    )),
    
    CONSTRAINT chk_saga_failed_step CHECK (failed_at_step IS NULL OR failed_at_step IN (
        'VALIDATE_RETURN', 'CALCULATE_CHARGES', 'CAPTURE_DEPOSIT', 
        'RELEASE_DEPOSIT', 'COMPLETE_BOOKING'
    ))
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ========================================================================
-- Performance Indexes
-- ========================================================================

-- Booking lookup
CREATE INDEX idx_saga_booking_id ON checkout_saga_state (booking_id);

-- Status filtering for monitoring
CREATE INDEX idx_saga_status ON checkout_saga_state (status);

-- Creation time for cleanup
CREATE INDEX idx_saga_created ON checkout_saga_state (created_at);

-- Active saga lookup (booking + status composite)
CREATE INDEX idx_saga_booking_status ON checkout_saga_state (booking_id, status);

-- Stuck saga detection
CREATE INDEX idx_saga_running_updated ON checkout_saga_state (status, updated_at);

-- Retry candidate lookup
CREATE INDEX idx_saga_failed_retry ON checkout_saga_state (status, retry_count);


-- ========================================================================
-- Saga Recovery Procedure
-- ========================================================================
-- Finds and processes stuck or failed sagas.
-- Should be called by scheduled job.

DELIMITER //

CREATE PROCEDURE recover_stuck_sagas(IN stuck_threshold_minutes INT)
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE saga_id_var VARCHAR(36);
    
    DECLARE stuck_cursor CURSOR FOR
        SELECT saga_id FROM checkout_saga_state
        WHERE status = 'RUNNING'
        AND updated_at < DATE_SUB(NOW(), INTERVAL stuck_threshold_minutes MINUTE);
    
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    
    -- Mark stuck sagas as failed
    OPEN stuck_cursor;
    
    read_loop: LOOP
        FETCH stuck_cursor INTO saga_id_var;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        UPDATE checkout_saga_state
        SET status = 'FAILED',
            error_message = CONCAT('Saga stuck for more than ', stuck_threshold_minutes, ' minutes'),
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE saga_id = saga_id_var;
        
    END LOOP;
    
    CLOSE stuck_cursor;
    
    -- Return count of stuck sagas found
    SELECT COUNT(*) as stuck_sagas_recovered
    FROM checkout_saga_state
    WHERE status = 'FAILED'
    AND error_message LIKE 'Saga stuck for more than%'
    AND updated_at > DATE_SUB(NOW(), INTERVAL 1 MINUTE);
    
END //

DELIMITER ;


-- ========================================================================
-- Saga Cleanup Event
-- ========================================================================
-- Removes completed sagas older than 90 days.

DELIMITER //

CREATE EVENT IF NOT EXISTS cleanup_checkout_sagas
ON SCHEDULE EVERY 1 DAY
STARTS CURRENT_TIMESTAMP + INTERVAL 2 HOUR
DO
BEGIN
    -- Delete completed sagas older than 90 days
    DELETE FROM checkout_saga_state 
    WHERE status IN ('COMPLETED', 'COMPENSATED')
    AND completed_at < DATE_SUB(NOW(), INTERVAL 90 DAY);
    
    -- Delete old failed sagas (keep for 180 days for audit)
    DELETE FROM checkout_saga_state 
    WHERE status = 'FAILED'
    AND updated_at < DATE_SUB(NOW(), INTERVAL 180 DAY);
    
END //

DELIMITER ;


-- ========================================================================
-- Monitoring View
-- ========================================================================
-- Provides aggregated saga statistics for dashboards.

CREATE OR REPLACE VIEW v_saga_statistics AS
SELECT 
    status,
    COUNT(*) as count,
    AVG(TIMESTAMPDIFF(SECOND, created_at, COALESCE(completed_at, updated_at))) as avg_duration_seconds,
    MAX(retry_count) as max_retries,
    SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) as retried_count,
    SUM(total_charges) as total_charges_sum,
    SUM(captured_amount) as total_captured_sum,
    SUM(released_amount) as total_released_sum
FROM checkout_saga_state
WHERE created_at > DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY status;


-- ========================================================================
-- Documentation
-- ========================================================================
-- 
-- Saga States:
-- ------------
-- PENDING      - Created, not yet started
-- RUNNING      - Executing steps
-- COMPLETED    - All steps successful
-- COMPENSATING - Rolling back due to failure
-- COMPENSATED  - Rollback complete
-- FAILED       - Failed (may be retryable)
-- 
-- Recovery:
-- ---------
-- 1. Scheduler calls recover_stuck_sagas(30) every 15 minutes
-- 2. Failed sagas with retry_count < 3 can be retried
-- 3. Compensated sagas indicate successful rollback
-- 
-- Monitoring Queries:
-- -------------------
-- Active sagas: SELECT * FROM checkout_saga_state WHERE status IN ('PENDING', 'RUNNING')
-- Failed today: SELECT * FROM checkout_saga_state WHERE status = 'FAILED' AND DATE(updated_at) = CURDATE()
-- Statistics:   SELECT * FROM v_saga_statistics
-- 
-- ========================================================================
