-- V43__add_account_lockout_fields.sql
-- Purpose: Implement progressive account lockout (VAL-038)
-- Author: AI Code Agent
-- Date: 2026-01-31
-- Related Issue: VAL-038 - No account lockout mechanism
--
-- Changes:
--   - Add failed_login_attempts counter (resets on successful login)
--   - Add locked_until timestamp (automatic unlock when expired)
--   - Add last_failed_login_at for audit trail
--   - Add last_failed_login_ip for security monitoring
--   - Add partial index for locked accounts query optimization
--
-- Migration is SAFE for existing users:
--   - failed_login_attempts defaults to 0
--   - locked_until defaults to NULL (not locked)
-- ============================================================================

-- ============================================================================
-- Add account lockout tracking fields to users table
-- ============================================================================

-- Counter for consecutive failed login attempts
-- Resets to 0 on successful login
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER DEFAULT 0 NOT NULL;

-- Timestamp until which the account is locked
-- NULL means account is not locked
-- Checked against current time: locked if locked_until > NOW()
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

-- Timestamp of the most recent failed login attempt
-- Used for audit trail and security analysis
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS last_failed_login_at TIMESTAMPTZ;

-- IP address of the most recent failed login attempt
-- VARCHAR(45) supports both IPv4 and IPv6 addresses
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS last_failed_login_ip VARCHAR(45);

-- ============================================================================
-- Indexes for performance
-- ============================================================================

-- Partial index for efficiently querying locked accounts
-- Only indexes rows where locked_until IS NOT NULL (actively locked accounts)
-- Used by admin dashboard to list locked accounts
CREATE INDEX IF NOT EXISTS idx_users_locked_until 
ON users(locked_until) 
WHERE locked_until IS NOT NULL;

-- ============================================================================
-- Documentation
-- ============================================================================

COMMENT ON COLUMN users.failed_login_attempts IS 
    'Counter for consecutive failed login attempts. Resets to 0 on successful login. (VAL-038)';

COMMENT ON COLUMN users.locked_until IS 
    'Account locked until this timestamp. NULL = not locked. Auto-unlock when time expires. (VAL-038)';

COMMENT ON COLUMN users.last_failed_login_at IS 
    'Timestamp of most recent failed login attempt. For audit trail and security analysis. (VAL-038)';

COMMENT ON COLUMN users.last_failed_login_ip IS 
    'IP address of most recent failed login. IPv6-compatible (45 chars). For security monitoring. (VAL-038)';

-- ============================================================================
-- Verification Queries (run manually after migration)
-- ============================================================================
-- 
-- 1. Verify columns exist:
--    SELECT column_name, data_type, is_nullable, column_default
--    FROM information_schema.columns 
--    WHERE table_name = 'users' 
--    AND column_name IN ('failed_login_attempts', 'locked_until', 'last_failed_login_at', 'last_failed_login_ip');
--
-- 2. Verify all existing users have 0 attempts:
--    SELECT COUNT(*) FROM users WHERE failed_login_attempts != 0;
--    (Should return 0)
--
-- 3. Verify no users are locked:
--    SELECT COUNT(*) FROM users WHERE locked_until IS NOT NULL;
--    (Should return 0)
--
-- 4. Verify partial index exists:
--    SELECT indexname FROM pg_indexes WHERE indexname = 'idx_users_locked_until';
-- ============================================================================
