-- ============================================================================
-- OAuth2 Authentication Support Migration
-- ============================================================================
-- Adds support for Google OAuth2 authentication alongside existing local auth
-- Backward compatible: existing users default to LOCAL provider
-- ============================================================================

-- Add authentication provider column (defaults to LOCAL for existing users)
ALTER TABLE users
ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL'
AFTER password;

-- Add Google ID column for OAuth2 users (nullable, unique)
ALTER TABLE users
ADD COLUMN google_id VARCHAR(100) NULL UNIQUE
AFTER auth_provider;

-- Add index on google_id for faster OAuth2 lookups
CREATE INDEX idx_user_google_id ON users(google_id);

-- ============================================================================
-- VALIDATION QUERIES (for verification after migration)
-- ============================================================================
-- Check that all existing users have LOCAL provider:
-- SELECT COUNT(*) FROM users WHERE auth_provider = 'LOCAL';

-- Verify new columns exist:
-- SHOW COLUMNS FROM users LIKE 'auth_provider';
-- SHOW COLUMNS FROM users LIKE 'google_id';

-- Verify index exists:
-- SHOW INDEX FROM users WHERE Key_name = 'idx_user_google_id';
