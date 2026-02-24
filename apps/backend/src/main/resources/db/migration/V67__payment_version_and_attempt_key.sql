-- ============================================================================
-- V67__payment_version_and_attempt_key.sql
--
-- P0-1: Add optimistic locking version columns that @Version-annotated JPA entities
--       require but V66 omitted from the CREATE TABLE statements.
--
-- P0-4: Add current_attempt_key to payout_ledger so a stable provider idempotency
--       key survives crash-recovery re-runs without rotating to a new key.
-- ============================================================================

-- ── payment_transactions ──────────────────────────────────────────────────────
-- @Version Long version in PaymentTransaction entity — needs DB column.
ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN payment_transactions.version IS
    'JPA optimistic-locking version counter. Incremented by Hibernate on every UPDATE.';

-- ── payout_ledger ─────────────────────────────────────────────────────────────
-- @Version Long version in PayoutLedger entity — needs DB column.
-- current_attempt_key: stable provider idempotency key for the in-flight payout
--   attempt. Set before calling provider; reused on crash-recovery replay.
--   Cleared after terminal resolution (COMPLETED or MANUAL_REVIEW).
ALTER TABLE payout_ledger
    ADD COLUMN IF NOT EXISTS version             BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_attempt_key VARCHAR(64);

COMMENT ON COLUMN payout_ledger.version IS
    'JPA optimistic-locking version counter.';
COMMENT ON COLUMN payout_ledger.current_attempt_key IS
    'Stable provider idempotency key set when a payout attempt begins. '
    'Reused on crash-recovery so the same provider slot is retried rather '
    'than issuing a duplicate transfer with a new key. Cleared on terminal resolution.';

-- Index to quickly find ledgers with an in-flight attempt key (recovery queries)
CREATE INDEX IF NOT EXISTS idx_pl_current_attempt_key
    ON payout_ledger (current_attempt_key)
    WHERE current_attempt_key IS NOT NULL;
