-- V68: Store the renter's payment-method token on the booking so it can be
-- re-used at check-in time to authorize the security deposit (P0-3 fix).
-- The deposit is no longer authorized at booking creation; it is authorized
-- when the check-in window opens (T-Xh before trip start), so the hold is
-- within the card-authorization lifetime window.
--
-- Column is nullable:
--   * Existing rows (created before this migration) still carry their old
--     depositAuthorizationId which remains valid until it expires.
--   * New rows will have this populated at booking creation.
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS stored_payment_method_id VARCHAR(100);
