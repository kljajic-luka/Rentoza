-- V102: Dodaje kolonu effective_deposit_snapshot u tabelu bookings.
-- Cuva iznos depozita prilagodjen po tier-u zastite u trenutku kreiranja rezervacije.
-- BASIC = 100% depozita, STANDARD = 50%, PREMIUM = 0%.

ALTER TABLE bookings
    ADD COLUMN effective_deposit_snapshot DECIMAL(19, 2);

COMMENT ON COLUMN bookings.effective_deposit_snapshot
    IS 'Iznos depozita prilagodjen prema tieru zastite (Zastita Rentoza) u trenutku kreiranja rezervacije';
