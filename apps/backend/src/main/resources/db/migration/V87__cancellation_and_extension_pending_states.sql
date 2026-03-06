-- V87: Explicit pending-financial booking/extension states
--
-- Adds data backfill for new explicit pending-settlement semantics.
-- Booking status remains VARCHAR so no enum DDL is required.

-- Widen booking workflow status columns before introducing longer literals.
ALTER TABLE bookings
    ALTER COLUMN status TYPE VARCHAR(50);

ALTER TABLE checkin_status_view
    ALTER COLUMN status TYPE VARCHAR(50);

-- Backfill bookings that were already CANCELLED but still have unresolved settlement.
UPDATE bookings b
SET status = 'CANCELLATION_PENDING_SETTLEMENT'
FROM cancellation_records c
WHERE c.booking_id = b.id
  AND b.status = 'CANCELLED'
  AND c.refund_status IN ('PENDING', 'PROCESSING', 'FAILED', 'MANUAL_REVIEW');
