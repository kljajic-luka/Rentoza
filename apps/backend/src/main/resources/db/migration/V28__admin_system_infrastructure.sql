-- =====================================================================
-- V28: Admin System Infrastructure
-- 
-- Creates tables for admin audit logging, metrics, and car approval workflow.
-- Part of enterprise-grade admin system implementation.
-- =====================================================================

-- ===============================
-- ADMIN AUDIT LOGS (IMMUTABLE)
-- ===============================
-- Purpose: Record every admin action for compliance, forensics, and accountability
-- Properties: APPEND-ONLY, 7+ year retention, indexed for fast searches

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    
    -- Admin who performed the action
    admin_id BIGINT NOT NULL,
    
    -- What action was performed (e.g., USER_BANNED, CAR_APPROVED)
    action VARCHAR(50) NOT NULL,
    
    -- Type of resource affected (e.g., USER, CAR, BOOKING)
    resource_type VARCHAR(50) NOT NULL,
    
    -- ID of the affected resource (nullable for system-wide actions)
    resource_id BIGINT,
    
    -- Full JSON snapshot BEFORE the action (for rollback capability)
    before_state LONGTEXT,
    
    -- Full JSON snapshot AFTER the action (null if deleted)
    after_state LONGTEXT,
    
    -- Admin-provided reason for audit trail
    reason VARCHAR(500),
    
    -- Client IP address (IPv4 or IPv6)
    ip_address VARCHAR(45),
    
    -- User agent string for forensic analysis
    user_agent VARCHAR(500),
    
    -- Timestamp (auto-set, never updated)
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key to users table
    CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES users(id),
    
    -- Indexes for common query patterns
    INDEX idx_audit_admin_created (admin_id, created_at),
    INDEX idx_audit_resource (resource_type, resource_id),
    INDEX idx_audit_action_created (action, created_at),
    INDEX idx_audit_created_at (created_at)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ===============================
-- ADMIN METRICS (KPI SNAPSHOTS)
-- ===============================
-- Purpose: Store periodic snapshots for dashboard and trend analysis
-- Strategy: Hourly snapshots, 12-month retention

CREATE TABLE IF NOT EXISTS admin_metrics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    
    -- Real-time metrics
    active_trips_count INT NOT NULL DEFAULT 0,
    total_revenue_cents BIGINT NOT NULL DEFAULT 0,
    pending_approvals_count INT DEFAULT 0,
    open_disputes_count INT DEFAULT 0,
    suspended_users_count INT DEFAULT 0,
    
    -- Period metrics
    new_users_count INT DEFAULT 0,
    new_cars_count INT DEFAULT 0,
    completed_bookings_count INT DEFAULT 0,
    total_users_count INT DEFAULT 0,
    total_cars_count INT DEFAULT 0,
    
    -- Growth percentages
    revenue_growth_percent DOUBLE,
    user_growth_percent DOUBLE,
    booking_growth_percent DOUBLE,
    
    -- Timestamps
    snapshot_date TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Indexes
    INDEX idx_metrics_created_at (created_at),
    INDEX idx_metrics_snapshot_date (snapshot_date)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ===============================
-- CAR APPROVAL WORKFLOW
-- ===============================
-- Add approval status fields to cars table for admin moderation

-- Check if columns already exist before adding
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() 
    AND TABLE_NAME = 'cars' 
    AND COLUMN_NAME = 'approval_status');

SET @sql = IF(@col_exists = 0, 
    'ALTER TABLE cars 
        ADD COLUMN approval_status ENUM(''PENDING'', ''APPROVED'', ''REJECTED'', ''SUSPENDED'') DEFAULT ''APPROVED'' AFTER active,
        ADD COLUMN approved_by BIGINT NULL AFTER approval_status,
        ADD COLUMN approved_at TIMESTAMP NULL AFTER approved_by,
        ADD COLUMN rejection_reason VARCHAR(500) NULL AFTER approved_at,
        ADD CONSTRAINT fk_car_approved_by FOREIGN KEY (approved_by) REFERENCES users(id),
        ADD INDEX idx_car_approval_status (approval_status)',
    'SELECT ''Car approval columns already exist''');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ===============================
-- MIGRATION VERIFICATION
-- ===============================

-- Log successful migration (for audit purposes)
INSERT INTO admin_audit_logs (admin_id, action, resource_type, reason, created_at)
SELECT id, 'CONFIG_UPDATED', 'CONFIG', 'V28: Admin system infrastructure migration completed', NOW()
FROM users WHERE user_role = 'ADMIN' LIMIT 1;
