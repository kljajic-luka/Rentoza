-- V6: Add host approval workflow support to bookings
-- This migration introduces PENDING_APPROVAL, DECLINED, and EXPIRED booking statuses
-- along with audit fields for approval/decline tracking and decision deadlines.

-- Step 1: Add version column for optimistic locking
ALTER TABLE bookings ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

-- Step 2: Add approval tracking columns
ALTER TABLE bookings ADD COLUMN approved_by BIGINT NULL;
ALTER TABLE bookings ADD COLUMN approved_at TIMESTAMP NULL;
ALTER TABLE bookings ADD COLUMN declined_by BIGINT NULL;
ALTER TABLE bookings ADD COLUMN declined_at TIMESTAMP NULL;
ALTER TABLE bookings ADD COLUMN decline_reason VARCHAR(500) NULL;
ALTER TABLE bookings ADD COLUMN decision_deadline_at TIMESTAMP NULL;

-- Step 3: Add payment simulation columns
ALTER TABLE bookings ADD COLUMN payment_verification_ref VARCHAR(100) NULL;
ALTER TABLE bookings ADD COLUMN payment_status VARCHAR(20) DEFAULT 'PENDING' NOT NULL;

-- Step 4: Add foreign key constraints
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_approved_by 
    FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE bookings ADD CONSTRAINT fk_bookings_declined_by 
    FOREIGN KEY (declined_by) REFERENCES users(id) ON DELETE SET NULL;

-- Step 5: Add indexes for performance
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_car_status ON bookings(car_id, status);
CREATE INDEX idx_bookings_renter_status ON bookings(renter_id, status);
CREATE INDEX idx_bookings_decision_deadline ON bookings(decision_deadline_at) WHERE status = 'PENDING_APPROVAL';

-- Step 6: Backfill existing ACTIVE bookings with approval metadata
-- Set approved_at to current timestamp for all existing ACTIVE bookings
UPDATE bookings 
SET approved_at = NOW(),
    payment_status = 'AUTHORIZED'
WHERE status = 'ACTIVE' AND approved_at IS NULL;

-- Step 7: Update status column to allow new enum values
-- Note: The enum is managed by JPA/Hibernate, but we ensure the column can store longer strings
ALTER TABLE bookings MODIFY COLUMN status VARCHAR(20) NOT NULL;
