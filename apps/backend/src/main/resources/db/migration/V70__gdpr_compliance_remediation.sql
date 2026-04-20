-- V70: GDPR Compliance Remediation
-- Remediates GAP-1 (deletion scheduler support) and GAP-5 (real audit log)
-- from the production readiness audit.

-- =============================================================================
-- GAP-5: Data Access Audit Log (replaces hardcoded stub)
-- =============================================================================
-- Stores factual records of who accessed user PII data and when.
-- GDPR Article 15 requires users can view who accessed their data.

CREATE TABLE IF NOT EXISTS data_access_logs (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    accessor_id     BIGINT,
    accessor_type   VARCHAR(20)     NOT NULL,
    action          VARCHAR(50)     NOT NULL,
    description     VARCHAR(500),
    source          VARCHAR(50),
    ip_address      VARCHAR(45),
    timestamp       TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_data_access_user_id
    ON data_access_logs(user_id);

CREATE INDEX IF NOT EXISTS idx_data_access_timestamp
    ON data_access_logs(timestamp);

CREATE INDEX IF NOT EXISTS idx_data_access_user_timestamp
    ON data_access_logs(user_id, timestamp);

-- =============================================================================
-- GAP-1: Index for GDPR deletion scheduler query performance
-- =============================================================================
-- The deletion scheduler queries: WHERE deletion_scheduled_at < NOW() AND is_deleted = false
-- A composite index makes this O(log n) instead of full table scan.

CREATE INDEX IF NOT EXISTS idx_user_deletion_pending
    ON users(deletion_scheduled_at)
    WHERE deletion_scheduled_at IS NOT NULL AND is_deleted = false;
