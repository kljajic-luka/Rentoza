-- ============================================================================
-- V17: Trip Extension Requests Schema
-- ============================================================================
-- 
-- Implements database support for trip extension requests:
-- - Guest can request extension during IN_TRIP status
-- - Host has 24 hours to approve/decline
-- - Auto-expire if no response
--
-- Author: System Architect
-- Date: 2025-12-02
-- ============================================================================

CREATE TABLE IF NOT EXISTS trip_extensions (
  id BIGSERIAL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0,
  booking_id BIGINT NOT NULL,

  -- Request details
  original_end_date DATE NOT NULL,
  requested_end_date DATE NOT NULL,
  additional_days INT NOT NULL,
  reason VARCHAR(500) NULL,

  -- Pricing
  daily_rate DECIMAL(19, 2) NOT NULL,
  additional_cost DECIMAL(19, 2) NOT NULL DEFAULT 0,

  -- Status
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  response_deadline TIMESTAMP WITH TIME ZONE NULL,
  host_response VARCHAR(500) NULL,
  responded_at TIMESTAMP WITH TIME ZONE NULL,

  -- Timestamps
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

  -- Foreign keys
  CONSTRAINT fk_trip_extension_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);

COMMENT ON TABLE trip_extensions IS 'Trip extension requests from guests';

CREATE INDEX IF NOT EXISTS idx_trip_extension_booking ON trip_extensions (booking_id);
CREATE INDEX IF NOT EXISTS idx_trip_extension_status ON trip_extensions (status);
CREATE INDEX IF NOT EXISTS idx_trip_extension_deadline ON trip_extensions (response_deadline);

CREATE OR REPLACE FUNCTION set_trip_extensions_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_trigger
    WHERE tgname = 'trg_trip_extensions_updated_at'
  ) THEN
    CREATE TRIGGER trg_trip_extensions_updated_at
      BEFORE UPDATE ON trip_extensions
      FOR EACH ROW
      EXECUTE FUNCTION set_trip_extensions_updated_at();
  END IF;
END $$;


