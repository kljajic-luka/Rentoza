-- ============================================================================
-- Flyway Migration: V12__cancellation_records_additional_fields.sql
-- Description: Add additional audit fields to cancellation_records table
-- Phase: 2 - Rules Engine Implementation
-- Date: 2024-01
-- ============================================================================

-- Add user notes field
ALTER TABLE cancellation_records
    ADD COLUMN notes TEXT NULL
    AFTER reason
    COMMENT 'Optional free-text notes from user explaining cancellation';

-- Add daily rate snapshot
ALTER TABLE cancellation_records
    ADD COLUMN daily_rate_snapshot DECIMAL(19, 2) NULL
    AFTER original_total_price
    COMMENT 'Daily rate used for penalty calculations';

-- Add host penalty amount (separate from guest penalty)
ALTER TABLE cancellation_records
    ADD COLUMN host_penalty_amount DECIMAL(19, 2) NULL
    AFTER payout_to_host
    COMMENT 'Tier-based penalty charged to host (RSD 5,500 / 11,000 / 16,500)';

-- Add trip date snapshots for audit
ALTER TABLE cancellation_records
    ADD COLUMN trip_start_date DATE NULL
    AFTER timezone
    COMMENT 'Trip start date snapshot for audit';

ALTER TABLE cancellation_records
    ADD COLUMN trip_end_date DATE NULL
    AFTER trip_start_date
    COMMENT 'Trip end date snapshot for audit';
