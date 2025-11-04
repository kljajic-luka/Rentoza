-- Migration: Add security fields to refresh_tokens table
-- Version: V2
-- Description: Adds fields for token reuse detection, IP/UserAgent fingerprinting
-- Date: 2025-11-04
-- Author: Rentoza Security Team

-- Add new columns for enhanced security
ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS used BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS used_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45) NULL,
    ADD COLUMN IF NOT EXISTS user_agent VARCHAR(500) NULL;

-- Add comment to document purpose
COMMENT ON COLUMN refresh_tokens.created_at IS 'Timestamp when token was created';
COMMENT ON COLUMN refresh_tokens.used IS 'Flag indicating if token has been used for rotation (reuse detection)';
COMMENT ON COLUMN refresh_tokens.used_at IS 'Timestamp when token was marked as used';
COMMENT ON COLUMN refresh_tokens.ip_address IS 'Optional IP address fingerprint (max 45 chars for IPv6)';
COMMENT ON COLUMN refresh_tokens.user_agent IS 'Optional User-Agent fingerprint';

-- Update existing tokens to have created_at set to current time (if they don't have it)
UPDATE refresh_tokens
SET created_at = CURRENT_TIMESTAMP
WHERE created_at IS NULL;

-- Create index on created_at for cleanup efficiency
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_created_at ON refresh_tokens(created_at);

-- Create index on used flag for reuse detection queries
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_used ON refresh_tokens(used);
