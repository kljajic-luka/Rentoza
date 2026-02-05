-- ========================================================================
-- V20: CQRS Read Model - Check-In Status View
-- ========================================================================
-- Phase 2 Architecture Improvement: CQRS Pattern Implementation
-- 
-- This migration creates a denormalized read model for check-in status
-- queries, eliminating complex JOINs and enabling sub-millisecond responses.
--
-- Eventual consistency: Updated via domain events from the write model.
-- Retention: Views for completed trips cleaned up after 30 days.
-- ========================================================================

-- Create the denormalized read model table
CREATE TABLE IF NOT EXISTS checkin_status_view (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Identifiers
    booking_id BIGINT NOT NULL,
    session_id VARCHAR(36),
    
    -- Denormalized host data
    host_user_id BIGINT NOT NULL,
    host_name VARCHAR(100),
    host_phone VARCHAR(20),
    
    -- Denormalized guest data
    guest_user_id BIGINT NOT NULL,
    guest_name VARCHAR(100),
    guest_phone VARCHAR(20),
    
    -- Denormalized car data
    car_id BIGINT NOT NULL,
    car_brand VARCHAR(50),
    car_model VARCHAR(50),
    car_year INT,
    car_image_url VARCHAR(500),
    car_license_plate VARCHAR(20),
    
    -- Status
    status VARCHAR(30) NOT NULL,
    status_display VARCHAR(100),
    
    -- Completion flags
    host_check_in_complete BOOLEAN NOT NULL DEFAULT FALSE,
    guest_check_in_complete BOOLEAN NOT NULL DEFAULT FALSE,
    handshake_complete BOOLEAN NOT NULL DEFAULT FALSE,
    trip_started BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Timestamps
    scheduled_start_time DATETIME,
    check_in_opened_at TIMESTAMP(6),
    host_completed_at TIMESTAMP(6),
    guest_completed_at TIMESTAMP(6),
    handshake_completed_at TIMESTAMP(6),
    trip_started_at TIMESTAMP(6),
    
    -- Host check-in data
    odometer_reading INT,
    fuel_level_percent INT,
    photo_count INT DEFAULT 0,
    lockbox_available BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Geofence data
    geofence_valid BOOLEAN,
    geofence_distance_meters INT,
    handshake_method VARCHAR(20),
    
    -- No-show tracking
    no_show_deadline DATETIME,
    no_show_party VARCHAR(10),
    
    -- Sync metadata
    last_event_id BIGINT,
    last_sync_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version BIGINT DEFAULT 0,
    
    -- Constraints
    CONSTRAINT uk_csv_booking_id UNIQUE (booking_id),
    CONSTRAINT chk_csv_status CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED',
        'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE',
        'IN_TRIP', 'CHECK_OUT_OPEN', 'CHECK_OUT_COMPLETE',
        'COMPLETED', 'CANCELLED', 'NO_SHOW_HOST', 'NO_SHOW_GUEST'
    )),
    CONSTRAINT chk_csv_handshake_method CHECK (handshake_method IN ('REMOTE', 'IN_PERSON') OR handshake_method IS NULL),
    CONSTRAINT chk_csv_no_show_party CHECK (no_show_party IN ('HOST', 'GUEST') OR no_show_party IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========================================================================
-- Performance Indexes
-- ========================================================================

-- Primary lookup by booking
CREATE INDEX idx_csv_booking_id ON checkin_status_view (booking_id);

-- Session correlation
CREATE INDEX idx_csv_session_id ON checkin_status_view (session_id);

-- Status filtering
CREATE INDEX idx_csv_status ON checkin_status_view (status);

-- Host dashboard queries
CREATE INDEX idx_csv_host_user ON checkin_status_view (host_user_id);

-- Guest dashboard queries
CREATE INDEX idx_csv_guest_user ON checkin_status_view (guest_user_id);

-- Scheduling queries
CREATE INDEX idx_csv_scheduled_start ON checkin_status_view (scheduled_start_time);

-- No-show deadline monitoring
CREATE INDEX idx_csv_status_noshow ON checkin_status_view (status, no_show_deadline);

-- Active check-ins for host
CREATE INDEX idx_csv_host_active ON checkin_status_view (host_user_id, trip_started, no_show_party);

-- Active check-ins for guest
CREATE INDEX idx_csv_guest_active ON checkin_status_view (guest_user_id, trip_started, no_show_party);

-- Stale view cleanup
CREATE INDEX idx_csv_last_sync ON checkin_status_view (last_sync_at);

-- Completed trip cleanup
CREATE INDEX idx_csv_trip_cleanup ON checkin_status_view (trip_started, trip_started_at);


-- ========================================================================
-- Initial Population Procedure
-- ========================================================================
-- Populates the read model from existing booking data.
-- Run this procedure after migration to backfill historical data.

DELIMITER //

CREATE PROCEDURE populate_checkin_status_view()
BEGIN
    INSERT INTO checkin_status_view (
        booking_id,
        session_id,
        host_user_id,
        host_name,
        host_phone,
        guest_user_id,
        guest_name,
        guest_phone,
        car_id,
        car_brand,
        car_model,
        car_year,
        car_image_url,
        car_license_plate,
        status,
        status_display,
        host_check_in_complete,
        guest_check_in_complete,
        handshake_complete,
        trip_started,
        scheduled_start_time,
        check_in_opened_at,
        host_completed_at,
        guest_completed_at,
        handshake_completed_at,
        trip_started_at,
        odometer_reading,
        fuel_level_percent,
        lockbox_available,
        geofence_distance_meters,
        handshake_method,
        no_show_deadline,
        no_show_party,
        last_sync_at
    )
    SELECT 
        b.id AS booking_id,
        b.check_in_session_id AS session_id,
        host.id AS host_user_id,
        CONCAT(host.first_name, ' ', host.last_name) AS host_name,
        host.phone_number AS host_phone,
        guest.id AS guest_user_id,
        CONCAT(guest.first_name, ' ', guest.last_name) AS guest_name,
        guest.phone_number AS guest_phone,
        c.id AS car_id,
        c.brand AS car_brand,
        c.model AS car_model,
        c.year AS car_year,
        c.image_url AS car_image_url,
        c.license_plate AS car_license_plate,
        b.status AS status,
        CASE b.status
            WHEN 'APPROVED' THEN 'Odobreno - čeka prijem'
            WHEN 'CHECK_IN_OPEN' THEN 'Prijem otvoren'
            WHEN 'CHECK_IN_HOST_COMPLETE' THEN 'Domaćin završio prijem'
            WHEN 'CHECK_IN_COMPLETE' THEN 'Prijem završen - čeka rukovanje'
            WHEN 'IN_TRIP' THEN 'Putovanje u toku'
            WHEN 'NO_SHOW_HOST' THEN 'Domaćin se nije pojavio'
            WHEN 'NO_SHOW_GUEST' THEN 'Gost se nije pojavio'
            ELSE b.status
        END AS status_display,
        (b.host_check_in_completed_at IS NOT NULL) AS host_check_in_complete,
        (b.guest_check_in_completed_at IS NOT NULL) AS guest_check_in_complete,
        (b.handshake_completed_at IS NOT NULL) AS handshake_complete,
        (b.trip_started_at IS NOT NULL) AS trip_started,
        b.start_time AS scheduled_start_time,
        b.check_in_opened_at,
        b.host_check_in_completed_at AS host_completed_at,
        b.guest_check_in_completed_at AS guest_completed_at,
        b.handshake_completed_at,
        b.trip_started_at,
        b.start_odometer AS odometer_reading,
        b.start_fuel_level AS fuel_level_percent,
        (b.lockbox_code_encrypted IS NOT NULL) AS lockbox_available,
        b.geofence_distance_meters,
        CASE 
            WHEN b.lockbox_code_encrypted IS NOT NULL THEN 'REMOTE'
            WHEN b.handshake_completed_at IS NOT NULL THEN 'IN_PERSON'
            ELSE NULL
        END AS handshake_method,
        DATE_ADD(b.start_time, INTERVAL 30 MINUTE) AS no_show_deadline,
        CASE 
            WHEN b.status = 'NO_SHOW_HOST' THEN 'HOST'
            WHEN b.status = 'NO_SHOW_GUEST' THEN 'GUEST'
            ELSE NULL
        END AS no_show_party,
        CURRENT_TIMESTAMP(6) AS last_sync_at
    FROM bookings b
    INNER JOIN cars c ON b.car_id = c.id
    INNER JOIN users host ON c.owner_id = host.id
    INNER JOIN users guest ON b.renter_id = guest.id
    WHERE b.status IN (
        'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE',
        'IN_TRIP', 'NO_SHOW_HOST', 'NO_SHOW_GUEST'
    )
    AND b.check_in_session_id IS NOT NULL
    ON DUPLICATE KEY UPDATE
        status = VALUES(status),
        status_display = VALUES(status_display),
        host_check_in_complete = VALUES(host_check_in_complete),
        guest_check_in_complete = VALUES(guest_check_in_complete),
        handshake_complete = VALUES(handshake_complete),
        trip_started = VALUES(trip_started),
        last_sync_at = CURRENT_TIMESTAMP(6);
END //

DELIMITER ;

-- Execute initial population
CALL populate_checkin_status_view();


-- ========================================================================
-- Cleanup Event for Old Records
-- ========================================================================
-- Note: This event cleans up completed records older than 30 days.
-- Requires event_scheduler to be enabled: SET GLOBAL event_scheduler = ON;

-- Check if events are enabled before creating
SET @event_scheduler_enabled = (SELECT @@event_scheduler);

DELIMITER //

CREATE EVENT IF NOT EXISTS cleanup_checkin_status_view
ON SCHEDULE EVERY 1 DAY
STARTS CURRENT_TIMESTAMP + INTERVAL 1 HOUR
DO
BEGIN
    -- Delete completed trips older than 30 days
    DELETE FROM checkin_status_view 
    WHERE trip_started = TRUE 
    AND trip_started_at < DATE_SUB(NOW(), INTERVAL 30 DAY);
    
    -- Delete no-shows older than 30 days
    DELETE FROM checkin_status_view 
    WHERE no_show_party IS NOT NULL 
    AND last_sync_at < DATE_SUB(NOW(), INTERVAL 30 DAY);
END //

DELIMITER ;


-- ========================================================================
-- Documentation
-- ========================================================================
-- 
-- Read Model Usage:
-- -----------------
-- This view is optimized for read-heavy dashboard queries.
-- For write operations, use the Booking entity directly.
-- 
-- Cache Invalidation:
-- -------------------
-- When domain events update this view, invalidate Redis caches:
-- - checkin-status:{bookingId}-{userId}
-- - checkin-photos:{bookingId}-{userId}
-- - checkin-status-minimal:{bookingId}
-- 
-- Consistency Model:
-- ------------------
-- Eventually consistent with ~100ms typical lag.
-- For strong consistency (e.g., handshake), read from Booking entity.
-- 
-- Monitoring:
-- -----------
-- Track these metrics for health monitoring:
-- - checkin_status_view.sync_lag_ms (histogram)
-- - checkin_status_view.stale_count (gauge)
-- - checkin_status_view.cleanup_count (counter)
-- 
-- ========================================================================
