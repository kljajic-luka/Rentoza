-- ============================================================================
-- V16: Damage Claims and Dispute Resolution Schema
-- ============================================================================
-- 
-- Implements database support for post-trip damage claims:
-- - Damage claim entity with full lifecycle tracking
-- - Guest response and admin review support
-- - Payment tracking
--
-- Author: System Architect
-- Date: 2025-12-02
-- ============================================================================

CREATE TABLE damage_claims (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    host_id BIGINT NOT NULL,
    guest_id BIGINT NOT NULL,
    
    -- Claim details
    description TEXT NOT NULL,
    claimed_amount DECIMAL(19, 2) NOT NULL,
    approved_amount DECIMAL(19, 2) NULL,
    
    -- Photo references (JSON arrays of photo IDs)
    checkin_photo_ids TEXT NULL,
    checkout_photo_ids TEXT NULL,
    evidence_photo_ids TEXT NULL,
    
    -- Status
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    response_deadline TIMESTAMP NULL,
    
    -- Guest response
    guest_response TEXT NULL,
    guest_responded_at TIMESTAMP NULL,
    
    -- Admin review
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    admin_notes TEXT NULL,
    
    -- Payment
    payment_reference VARCHAR(100) NULL,
    paid_at TIMESTAMP NULL,
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_damage_claim_booking FOREIGN KEY (booking_id) REFERENCES bookings(id),
    CONSTRAINT fk_damage_claim_host FOREIGN KEY (host_id) REFERENCES users(id),
    CONSTRAINT fk_damage_claim_guest FOREIGN KEY (guest_id) REFERENCES users(id),
    CONSTRAINT fk_damage_claim_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
    
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Damage claims for post-trip dispute resolution';

-- Indexes
CREATE INDEX idx_damage_claim_booking ON damage_claims (booking_id);
CREATE INDEX idx_damage_claim_status ON damage_claims (status);
CREATE INDEX idx_damage_claim_host ON damage_claims (host_id);
CREATE INDEX idx_damage_claim_guest ON damage_claims (guest_id);
CREATE INDEX idx_damage_claim_deadline ON damage_claims (response_deadline)
    COMMENT 'For scheduler to find expired pending claims';

