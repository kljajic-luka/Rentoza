-- V73: Add per-listing security deposit amount to cars table.
--
-- Hosts can configure a custom security deposit per vehicle listing.
-- NULL means "use the platform default" (app.payment.deposit.amount-rsd).
-- The value is snapshotted into bookings.security_deposit at booking creation.

ALTER TABLE cars
    ADD COLUMN security_deposit_rsd DECIMAL(19, 2) DEFAULT NULL;
