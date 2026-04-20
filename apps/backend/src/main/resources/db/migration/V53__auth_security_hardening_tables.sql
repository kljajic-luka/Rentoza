-- =============================================================================
-- V53: Auth Security Hardening — New tables for Phase 3
-- =============================================================================
-- Creates tables required by Turo Standard pre-production checklist:
--   1. password_history       – Prevents reuse of last N passwords
--   2. password_reset_tokens  – One-time, expirable password-reset tokens
--   3. token_denylist         – JWT denylist for post-logout replay protection
--
-- All tables are append-heavy with scheduled cleanup (SecurityMaintenanceScheduler).
-- Indexes are chosen to support the hot read paths:
--   * password_history:      findLastNPasswords(userId)
--   * password_reset_tokens: findValidToken(tokenHash, now)
--   * token_denylist:        existsByTokenHash(hash)
-- =============================================================================

-- ============================================================================
-- 1. password_history
-- ============================================================================
CREATE TABLE IF NOT EXISTS password_history (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL
                                REFERENCES users(id) ON DELETE CASCADE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_password_history_user_id
    ON password_history (user_id);

CREATE INDEX IF NOT EXISTS idx_password_history_created_at
    ON password_history (created_at);

COMMENT ON TABLE  password_history IS 'BCrypt hashes of previous passwords for reuse prevention (Turo standard: last 3).';
COMMENT ON COLUMN password_history.password_hash IS 'BCrypt-encoded password (same algorithm as users.password).';

-- ============================================================================
-- 2. password_reset_tokens
-- ============================================================================
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL
                                 REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ  NOT NULL,
    used            BOOLEAN      NOT NULL DEFAULT FALSE,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    requested_ip    VARCHAR(45)
);

CREATE INDEX IF NOT EXISTS idx_reset_token_hash
    ON password_reset_tokens (token_hash);

CREATE INDEX IF NOT EXISTS idx_reset_token_user_id
    ON password_reset_tokens (user_id);

CREATE INDEX IF NOT EXISTS idx_reset_token_expires_at
    ON password_reset_tokens (expires_at);

-- Partial index for finding valid (un-used, un-expired) tokens quickly
CREATE INDEX IF NOT EXISTS idx_reset_token_valid
    ON password_reset_tokens (token_hash)
    WHERE used = FALSE;

COMMENT ON TABLE  password_reset_tokens IS 'One-time password reset tokens (SHA-256 hashed). 1-hour expiry.';
COMMENT ON COLUMN password_reset_tokens.token_hash IS 'SHA-256 hex digest of the raw token sent to user. Never stored in plain text.';
COMMENT ON COLUMN password_reset_tokens.requested_ip IS 'IP that requested the reset (audit trail, max 45 chars for IPv6).';

-- ============================================================================
-- 3. token_denylist
-- ============================================================================
CREATE TABLE IF NOT EXISTS token_denylist (
    id              BIGSERIAL    PRIMARY KEY,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ  NOT NULL,
    user_email      VARCHAR(255),
    denied_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_denylist_token_hash
    ON token_denylist (token_hash);

CREATE INDEX IF NOT EXISTS idx_denylist_expires_at
    ON token_denylist (expires_at);

COMMENT ON TABLE  token_denylist IS 'Denied (blacklisted) JWT access tokens. Entries auto-cleaned after original token expiry.';
COMMENT ON COLUMN token_denylist.token_hash IS 'SHA-256 hex digest of the JWT access token.';
COMMENT ON COLUMN token_denylist.user_email IS 'Email of the user who logged out (audit trail).';
