-- V104: Polja za verifikaciju telefona
-- Dodaje phone_verified_at, pending_phone i pending_phone_updated_at na users tabelu.
-- phone ostaje kanonski broj; pending_phone je privremeni zamenjivac dok se OTP ne potvrdi.

ALTER TABLE users
    ADD COLUMN phone_verified_at TIMESTAMP NULL,
    ADD COLUMN pending_phone VARCHAR(20) NULL,
    ADD COLUMN pending_phone_updated_at TIMESTAMP NULL;

-- Indeks za proveru jedinstvenosti pending telefona
CREATE INDEX idx_user_pending_phone ON users (pending_phone) WHERE pending_phone IS NOT NULL;
