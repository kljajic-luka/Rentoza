-- ============================================================================
-- Flyway Migration: V11__cancellation_policy_migration.sql
-- Description: Turo-style platform-standard cancellation support
-- Phase: 1 - Database & Domain Modeling
-- Date: 2024-01
-- ============================================================================

-- ============================================================================
-- SECTION 1: Add new columns to bookings table
-- ============================================================================

-- 1.1: Snapshot daily rate at booking creation (for penalty calculation)
ALTER TABLE bookings
    ADD COLUMN snapshot_daily_rate DECIMAL(19, 2) NULL
    COMMENT 'Daily rate locked at booking creation time for penalty calculation';

-- 1.2: Quick-access cancellation fields (denormalized for performance)
ALTER TABLE bookings
    ADD COLUMN cancelled_by VARCHAR(20) NULL
    COMMENT 'Party that cancelled: GUEST, HOST, SYSTEM (denormalized from cancellation_records)';

ALTER TABLE bookings
    ADD COLUMN cancelled_at TIMESTAMP NULL
    COMMENT 'Timestamp when booking was cancelled (denormalized from cancellation_records)';

-- 1.3: Created timestamp for remorse window calculation
ALTER TABLE bookings
    ADD COLUMN created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
    COMMENT 'Booking creation timestamp for remorse window (1-hour impulse protection)';

-- Backfill created_at for existing bookings (use decision_deadline_at minus 48h as approximation)
UPDATE bookings
SET created_at = COALESCE(
    DATE_SUB(decision_deadline_at, INTERVAL 48 HOUR),
    DATE_SUB(start_date, INTERVAL 7 DAY)
)
WHERE created_at IS NULL;

-- 1.4: Add index for cancellation queries
CREATE INDEX idx_booking_cancelled_by ON bookings(cancelled_by);
CREATE INDEX idx_booking_cancelled_at ON bookings(cancelled_at);

-- ============================================================================
-- SECTION 2: Create cancellation_records table (Audit Ledger)
-- ============================================================================

CREATE TABLE cancellation_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Relationship (1:1 with bookings)
    booking_id BIGINT NOT NULL UNIQUE,
    
    -- Initiator & Reason
    cancelled_by VARCHAR(20) NOT NULL
        COMMENT 'GUEST, HOST, SYSTEM',
    reason VARCHAR(50) NOT NULL
        COMMENT 'Categorized reason: GUEST_CHANGE_OF_PLANS, HOST_VEHICLE_DAMAGE, etc.',
    
    -- Timing
    initiated_at TIMESTAMP NOT NULL
        COMMENT 'When cancellation was requested',
    processed_at TIMESTAMP NOT NULL
        COMMENT 'When cancellation processing completed',
    hours_before_trip_start BIGINT NOT NULL
        COMMENT 'Hours until trip start at cancellation time (negative = after start)',
    
    -- Financial Snapshot (all DECIMAL(19,2) for precision)
    original_total_price DECIMAL(19, 2) NOT NULL
        COMMENT 'Total booking price at cancellation time',
    penalty_amount DECIMAL(19, 2) NOT NULL
        COMMENT 'Amount retained as penalty',
    refund_to_guest DECIMAL(19, 2) NOT NULL
        COMMENT 'Amount refunded to guest',
    payout_to_host DECIMAL(19, 2) NOT NULL
        COMMENT 'Amount paid out to host (may include penalty)',
    
    -- Refund Processing
    refund_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, PROCESSING, COMPLETED, FAILED',
    
    -- Policy Tracking
    policy_version VARCHAR(50) NOT NULL
        COMMENT 'Version of cancellation policy applied (e.g., 2024-01-TURO-V1)',
    applied_rule VARCHAR(100) NULL
        COMMENT 'Human-readable rule name (e.g., 24H_FREE_CANCELLATION)',
    
    -- Timezone (for audit/dispute resolution)
    timezone VARCHAR(50) NOT NULL DEFAULT 'Europe/Belgrade'
        COMMENT 'Timezone used for 24-hour window calculation',
    
    -- Exception Handling (Host waiver requests)
    penalty_waiver_requested BOOLEAN NOT NULL DEFAULT FALSE,
    penalty_waiver_approved BOOLEAN NOT NULL DEFAULT FALSE,
    waiver_document_url VARCHAR(500) NULL
        COMMENT 'URL to uploaded evidence for waiver request',
    admin_notes TEXT NULL
        COMMENT 'Admin notes for waiver decision or special handling',
    
    -- Audit Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT fk_cancellation_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id)
        ON DELETE RESTRICT  -- Prevent deleting bookings with cancellation records
        ON UPDATE CASCADE,
    
    CONSTRAINT chk_cancelled_by 
        CHECK (cancelled_by IN ('GUEST', 'HOST', 'SYSTEM')),
    
    CONSTRAINT chk_refund_status
        CHECK (refund_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    
    CONSTRAINT chk_financial_non_negative
        CHECK (penalty_amount >= 0 AND refund_to_guest >= 0 AND payout_to_host >= 0)
        
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable audit ledger for booking cancellations (Turo-style policy)';

-- Indexes for common queries
CREATE INDEX idx_cancellation_records_booking ON cancellation_records(booking_id);
CREATE INDEX idx_cancellation_records_initiated ON cancellation_records(initiated_at);
CREATE INDEX idx_cancellation_records_cancelled_by ON cancellation_records(cancelled_by);
CREATE INDEX idx_cancellation_records_refund_status ON cancellation_records(refund_status);
CREATE INDEX idx_cancellation_records_waiver_pending ON cancellation_records(penalty_waiver_requested, penalty_waiver_approved);

-- ============================================================================
-- SECTION 3: Create host_cancellation_stats table (Penalty Tier Tracking)
-- ============================================================================

CREATE TABLE host_cancellation_stats (
    -- Primary key is host user ID (1:1 with users)
    host_id BIGINT PRIMARY KEY,
    
    -- Cancellation Counts
    cancellations_this_year INT NOT NULL DEFAULT 0
        COMMENT 'Cancellations in current calendar year (resets Jan 1)',
    cancellations_last_30_days INT NOT NULL DEFAULT 0
        COMMENT 'Rolling 30-day count for pattern detection',
    total_bookings INT NOT NULL DEFAULT 0
        COMMENT 'Total bookings received (for rate calculation)',
    
    -- Rate & Tier
    cancellation_rate DECIMAL(5, 2) NULL
        COMMENT 'Percentage: (cancellationsThisYear / totalBookings) * 100',
    penalty_tier INT NOT NULL DEFAULT 0
        COMMENT 'Current tier (0=clean, 1/2/3+ = penalty tiers)',
    
    -- Timing
    last_cancellation_at TIMESTAMP NULL
        COMMENT 'When host last cancelled a booking',
    suspension_ends_at TIMESTAMP NULL
        COMMENT 'When current suspension ends (NULL if not suspended)',
    
    -- Audit
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT fk_host_stats_user
        FOREIGN KEY (host_id) REFERENCES users(id)
        ON DELETE CASCADE  -- Remove stats if user is deleted
        ON UPDATE CASCADE,
    
    CONSTRAINT chk_counts_non_negative
        CHECK (cancellations_this_year >= 0 AND cancellations_last_30_days >= 0 AND total_bookings >= 0),
    
    CONSTRAINT chk_tier_range
        CHECK (penalty_tier >= 0 AND penalty_tier <= 3)
        
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Host cancellation behavior tracking for penalty tier escalation';

-- Indexes for suspension and pattern detection
CREATE INDEX idx_host_stats_suspension ON host_cancellation_stats(suspension_ends_at)
    COMMENT 'Find currently suspended hosts';
CREATE INDEX idx_host_stats_30day ON host_cancellation_stats(cancellations_last_30_days)
    COMMENT 'Pattern detection queries';
CREATE INDEX idx_host_stats_rate ON host_cancellation_stats(cancellation_rate)
    COMMENT 'Find hosts with high cancellation rate';

-- ============================================================================
-- SECTION 4: Deprecate Car.cancellation_policy (make nullable)
-- ============================================================================

-- Make column nullable (already nullable based on entity, but ensure it)
-- Note: We're keeping the data for historical reference, not dropping the column
ALTER TABLE cars
    MODIFY COLUMN cancellation_policy VARCHAR(20) NULL
    COMMENT 'DEPRECATED: Legacy owner-selected policy. Use platform-standard rules instead.';

-- ============================================================================
-- SECTION 5: Migrate existing cancelled bookings (Historical Data)
-- ============================================================================

-- Create records for historically cancelled bookings
-- These are marked with policy_version='LEGACY-MIGRATED' for distinction
INSERT INTO cancellation_records (
    booking_id,
    cancelled_by,
    reason,
    initiated_at,
    processed_at,
    hours_before_trip_start,
    original_total_price,
    penalty_amount,
    refund_to_guest,
    payout_to_host,
    refund_status,
    policy_version,
    applied_rule,
    timezone
)
SELECT 
    b.id,
    'GUEST' AS cancelled_by,  -- Default assumption for legacy data
    'GUEST_CHANGE_OF_PLANS' AS reason,
    COALESCE(b.declined_at, b.created_at, NOW()) AS initiated_at,
    COALESCE(b.declined_at, b.created_at, NOW()) AS processed_at,
    0 AS hours_before_trip_start,  -- Unknown for legacy bookings
    b.total_price AS original_total_price,
    0 AS penalty_amount,  -- No penalty under old system
    b.total_price AS refund_to_guest,  -- Assume full refund
    0 AS payout_to_host,
    'COMPLETED' AS refund_status,  -- Mark as already processed
    'LEGACY-MIGRATED' AS policy_version,
    'LEGACY_FULL_REFUND' AS applied_rule,
    'Europe/Belgrade' AS timezone
FROM bookings b
WHERE b.status = 'CANCELLED'
  AND NOT EXISTS (
      SELECT 1 FROM cancellation_records cr WHERE cr.booking_id = b.id
  );

-- Update denormalized fields on existing cancelled bookings
UPDATE bookings b
SET 
    b.cancelled_by = 'GUEST',
    b.cancelled_at = COALESCE(b.declined_at, b.created_at, NOW())
WHERE b.status = 'CANCELLED'
  AND b.cancelled_by IS NULL;

-- ============================================================================
-- SECTION 6: Add helpful comments to legacy fields
-- ============================================================================

-- Add deprecation comment to legacy enum values documentation
-- (Actual enum deprecation is handled in Java code)

-- ============================================================================
-- VERIFICATION QUERIES (Run manually to validate migration)
-- ============================================================================

-- SELECT 'Bookings with new columns' AS check_name, 
--        COUNT(*) AS total,
--        SUM(CASE WHEN snapshot_daily_rate IS NOT NULL THEN 1 ELSE 0 END) AS with_snapshot,
--        SUM(CASE WHEN created_at IS NOT NULL THEN 1 ELSE 0 END) AS with_created_at
-- FROM bookings;

-- SELECT 'Cancellation records created' AS check_name, COUNT(*) AS total 
-- FROM cancellation_records;

-- SELECT 'Host stats records' AS check_name, COUNT(*) AS total 
-- FROM host_cancellation_stats;

-- SELECT 'Cars with cancellation_policy' AS check_name, 
--        cancellation_policy, COUNT(*) AS count
-- FROM cars
-- GROUP BY cancellation_policy;
