-- H-1: Add payout retry count tracking fields to bookings
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS payout_retry_count INTEGER DEFAULT 0;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS last_payout_retry_at TIMESTAMPTZ;
