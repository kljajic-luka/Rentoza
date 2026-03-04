-- V83: Add unique index on lower(email) to prevent case-variant duplicate accounts
-- and ensure case-insensitive email lookups are efficient.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower ON users (LOWER(email));
