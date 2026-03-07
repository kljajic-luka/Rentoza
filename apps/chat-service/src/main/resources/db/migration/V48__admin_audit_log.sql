CREATE TABLE IF NOT EXISTS admin_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    admin_user_id BIGINT NOT NULL,
    action        VARCHAR(30) NOT NULL,
    target_type   VARCHAR(20) NOT NULL,
    target_id     VARCHAR(100) NOT NULL,
    metadata      TEXT,
    timestamp     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_timestamp
    ON admin_audit_log (timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_target
    ON admin_audit_log (target_type, target_id);