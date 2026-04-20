-- V72: Add PDV (Serbian VAT 20%) column to payout_ledger
-- Platform fee is subject to Serbian PDV (Porez na dodatu vrednost).
-- Stored as a separate column so the gross fee, PDV component, and net
-- amounts are independently auditable and support future rate changes.

ALTER TABLE payout_ledger
    ADD COLUMN platform_fee_pdv NUMERIC(19, 2);

-- Backfill existing rows: PDV = platformFee × 0.20 (current Serbian standard rate)
UPDATE payout_ledger
   SET platform_fee_pdv = ROUND(platform_fee * 0.20, 2)
 WHERE platform_fee_pdv IS NULL;
