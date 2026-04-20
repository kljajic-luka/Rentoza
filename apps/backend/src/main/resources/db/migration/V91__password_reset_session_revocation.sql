ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ;

COMMENT ON COLUMN users.password_changed_at IS
    'Timestamp of the most recent password change. JWTs issued before this instant must be rejected.';

CREATE INDEX IF NOT EXISTS idx_users_password_changed_at
    ON users (password_changed_at)
    WHERE password_changed_at IS NOT NULL;