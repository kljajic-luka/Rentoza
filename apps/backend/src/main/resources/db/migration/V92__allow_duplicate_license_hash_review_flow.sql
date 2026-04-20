ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_driver_license_number_hash_key;

DROP INDEX IF EXISTS users_driver_license_number_hash_key;

CREATE INDEX IF NOT EXISTS idx_users_driver_license_number_hash
    ON users (driver_license_number_hash)
    WHERE driver_license_number_hash IS NOT NULL;