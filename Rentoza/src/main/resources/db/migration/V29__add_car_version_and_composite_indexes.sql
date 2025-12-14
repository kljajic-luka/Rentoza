-- V29: Add optimistic locking version column and composite indexes for car approval
-- Part of enterprise improvements for car approval workflow

-- Add version column for JPA optimistic locking (prevents concurrent modification)
ALTER TABLE cars ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

-- Composite index for pending car queries ordered by creation date
-- Optimizes: SELECT * FROM cars WHERE approval_status = 'PENDING' ORDER BY created_at DESC
CREATE INDEX idx_approval_status_created ON cars(approval_status, created_at DESC);

-- Composite index for approval history queries by admin
-- Optimizes: SELECT * FROM cars WHERE approved_by = ? ORDER BY approved_at DESC
CREATE INDEX idx_approved_by_created ON cars(approved_by, approved_at DESC);
