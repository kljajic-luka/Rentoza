-- ============================================================================
-- V17: Trip Extension Requests Schema
-- ============================================================================
-- 
-- Implements database support for trip extension requests:
-- - Guest can request extension during IN_TRIP status
-- - Host has 24 hours to approve/decline
-- - Auto-expire if no response
--
-- Author: System Architect
-- Date: 2025-12-02
-- ============================================================================

CREATE TABLE trip_extensions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    
    -- Request details
    original_end_date DATE NOT NULL,
    requested_end_date DATE NOT NULL,
    additional_days INT NOT NULL,
    reason VARCHAR(500) NULL,
    
    -- Pricing
    daily_rate DECIMAL(19, 2) NOT NULL,
    additional_cost DECIMAL(19, 2) NOT NULL,
    
    -- Status
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_deadline TIMESTAMP NULL,
    host_response VARCHAR(500) NULL,
    responded_at TIMESTAMP NULL,
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_trip_extension_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
    
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Trip extension requests from guests';

-- Indexes
CREATE INDEX idx_trip_extension_booking ON trip_extensions (booking_id);
CREATE INDEX idx_trip_extension_status ON trip_extensions (status);
CREATE INDEX idx_trip_extension_deadline ON trip_extensions (response_deadline)
    COMMENT 'For scheduler to find expired pending requests';


