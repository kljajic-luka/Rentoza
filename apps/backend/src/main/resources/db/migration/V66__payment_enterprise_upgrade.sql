-- ============================================================================
-- V66__payment_enterprise_upgrade.sql
-- Enterprise payment architecture upgrade for Monri/Mori integration readiness.
--
-- Changes:
--   1. bookings          — typed lifecycle status columns + auth expiry tracking
--   2. cancellation_records — refund retry tracking + MANUAL_REVIEW status
--   3. payment_transactions — NEW: full ledger of every provider call
--   4. payout_ledger        — NEW: marketplace host payout scheduling & execution
--   5. provider_events      — NEW: webhook deduplication store
-- ============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. bookings — typed payment lifecycle fields
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS charge_lifecycle_status  VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS deposit_lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS booking_auth_expires_at  TIMESTAMPTZ          DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS deposit_auth_expires_at  TIMESTAMPTZ          DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS capture_attempts         INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS deposit_capture_attempts INT         NOT NULL DEFAULT 0;

-- Backfill charge_lifecycle_status from legacy payment_status string
UPDATE bookings
SET charge_lifecycle_status = CASE payment_status
    WHEN 'AUTHORIZED'     THEN 'AUTHORIZED'
    WHEN 'CAPTURED'       THEN 'CAPTURED'
    WHEN 'RELEASED'       THEN 'RELEASED'
    WHEN 'REFUNDED'       THEN 'REFUNDED'
    WHEN 'CAPTURE_FAILED' THEN 'CAPTURE_FAILED'
    WHEN 'FAILED'         THEN 'CAPTURE_FAILED'
    ELSE 'PENDING'
END
WHERE charge_lifecycle_status = 'PENDING';

-- Indexes for scheduler queries on the new columns
CREATE INDEX IF NOT EXISTS idx_bookings_charge_lifecycle
    ON bookings (charge_lifecycle_status);

CREATE INDEX IF NOT EXISTS idx_bookings_deposit_lifecycle
    ON bookings (deposit_lifecycle_status);

CREATE INDEX IF NOT EXISTS idx_bookings_booking_auth_expires
    ON bookings (booking_auth_expires_at)
    WHERE booking_auth_expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_deposit_auth_expires
    ON bookings (deposit_auth_expires_at)
    WHERE deposit_auth_expires_at IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. cancellation_records — retry tracking + MANUAL_REVIEW refund status
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE cancellation_records
    ADD COLUMN IF NOT EXISTS retry_count  INT          NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_retries  INT          NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMPTZ          DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ          DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS last_error   VARCHAR(500)          DEFAULT NULL;

-- Widen the refund_status CHECK to include MANUAL_REVIEW.
-- Drop the original constraint by its well-known name (set in V11).
ALTER TABLE cancellation_records
    DROP CONSTRAINT IF EXISTS chk_refund_status;

ALTER TABLE cancellation_records
    ADD CONSTRAINT chk_refund_status
        CHECK (refund_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'));

-- Index for the retry-eligible query used by PaymentLifecycleScheduler
CREATE INDEX IF NOT EXISTS idx_cancellation_retry_eligible
    ON cancellation_records (refund_status, next_retry_at, retry_count)
    WHERE refund_status IN ('FAILED', 'PROCESSING');

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. payment_transactions — ledger of every provider call
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS payment_transactions (
    id                  BIGSERIAL       PRIMARY KEY,
    booking_id          BIGINT          NOT NULL,
    user_id             BIGINT          NOT NULL,

    -- What operation this row records
    operation           VARCHAR(30)     NOT NULL,   -- PaymentOperation enum
    status              VARCHAR(30)     NOT NULL,   -- PaymentTransactionStatus enum

    -- Idempotency: UNIQUE ensures no double-write for the same key
    idempotency_key     VARCHAR(64)     NOT NULL,

    -- Monetary values
    amount              NUMERIC(19, 2),
    currency            VARCHAR(10),

    -- Provider response fields
    provider_reference  VARCHAR(100),
    provider_auth_id    VARCHAR(100),
    raw_provider_status VARCHAR(100),
    redirect_url        VARCHAR(500),
    session_token       VARCHAR(200),
    error_code          VARCHAR(50),
    error_message       VARCHAR(500),

    -- Retry tracking
    attempt_count       INT             NOT NULL DEFAULT 1,
    max_attempts        INT             NOT NULL DEFAULT 3,
    next_retry_at       TIMESTAMPTZ,
    provider_expiry     TIMESTAMPTZ,

    -- Timestamps
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,

    CONSTRAINT uq_pt_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_pt_booking_id
    ON payment_transactions (booking_id);

CREATE INDEX IF NOT EXISTS idx_pt_status
    ON payment_transactions (status);

CREATE INDEX IF NOT EXISTS idx_pt_provider_ref
    ON payment_transactions (provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pt_created_at
    ON payment_transactions (created_at);

-- Composite index for retry scheduler: status=FAILED_RETRYABLE AND next_retry_at <= NOW()
CREATE INDEX IF NOT EXISTS idx_pt_retry_eligible
    ON payment_transactions (status, next_retry_at)
    WHERE status = 'FAILED_RETRYABLE';

-- Composite index for stale PROCESSING recovery
CREATE INDEX IF NOT EXISTS idx_pt_stale_processing
    ON payment_transactions (status, updated_at)
    WHERE status = 'PROCESSING';

COMMENT ON TABLE payment_transactions IS
    'Immutable ledger of every payment provider call. One row per idempotency key.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. payout_ledger — marketplace host payout scheduling and execution
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS payout_ledger (
    id                  BIGSERIAL       PRIMARY KEY,

    -- One payout per completed booking (enforced by UNIQUE)
    booking_id          BIGINT          NOT NULL,
    host_user_id        BIGINT          NOT NULL,

    -- Financial breakdown
    trip_amount         NUMERIC(19, 2)  NOT NULL,
    platform_fee_rate   NUMERIC(5,  4)  NOT NULL,   -- e.g. 0.1200 = 12%
    platform_fee        NUMERIC(19, 2)  NOT NULL,
    host_payout_amount  NUMERIC(19, 2)  NOT NULL,
    currency            VARCHAR(10)     NOT NULL,

    -- State machine
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',

    -- Idempotency for the payout provider call
    idempotency_key     VARCHAR(64)     NOT NULL,
    provider_reference  VARCHAR(100),

    -- Dispute hold
    on_hold             BOOLEAN         NOT NULL DEFAULT FALSE,
    hold_reason         VARCHAR(200),

    -- Retry tracking
    attempt_count       INT             NOT NULL DEFAULT 0,
    max_attempts        INT             NOT NULL DEFAULT 3,
    last_error          VARCHAR(500),
    next_retry_at       TIMESTAMPTZ,

    -- Timing
    eligible_at         TIMESTAMPTZ,    -- dispute window cutoff
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,

    CONSTRAINT uq_pl_booking_id      UNIQUE (booking_id),
    CONSTRAINT uq_pl_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_pl_host_user_id
    ON payout_ledger (host_user_id);

CREATE INDEX IF NOT EXISTS idx_pl_status
    ON payout_ledger (status);

CREATE INDEX IF NOT EXISTS idx_pl_eligible_at
    ON payout_ledger (eligible_at)
    WHERE eligible_at IS NOT NULL;

-- For executeEligiblePayouts: status=ELIGIBLE and not on hold
CREATE INDEX IF NOT EXISTS idx_pl_eligible_payout
    ON payout_ledger (status, on_hold, eligible_at)
    WHERE status = 'ELIGIBLE' AND on_hold = FALSE;

-- For stale PROCESSING recovery
CREATE INDEX IF NOT EXISTS idx_pl_stale_processing
    ON payout_ledger (status, updated_at)
    WHERE status = 'PROCESSING';

COMMENT ON TABLE payout_ledger IS
    'One row per completed booking. Tracks the full lifecycle from payout scheduling to final transfer.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. provider_events — webhook deduplication store
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS provider_events (
    id                  BIGSERIAL       PRIMARY KEY,

    -- Deduplication key — the provider's own unique event ID
    provider_event_id   VARCHAR(150)    NOT NULL,
    provider_name       VARCHAR(50)     NOT NULL,

    -- Context (nullable: some events are not booking-specific)
    booking_id          BIGINT,
    event_type          VARCHAR(100)    NOT NULL,

    -- Raw payload for reprocessing / audit
    raw_payload         JSONB,
    signature_header    VARCHAR(500),
    signature_verified  BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Processing state
    processed_at        TIMESTAMPTZ,
    processing_error    VARCHAR(500),
    received_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_pe_provider_event_id UNIQUE (provider_event_id)
);

CREATE INDEX IF NOT EXISTS idx_pe_booking_id
    ON provider_events (booking_id)
    WHERE booking_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pe_processed_at
    ON provider_events (processed_at)
    WHERE processed_at IS NULL;   -- Fast lookup for unprocessed events

CREATE INDEX IF NOT EXISTS idx_pe_received_at
    ON provider_events (received_at);

COMMENT ON TABLE provider_events IS
    'Deduplication store for payment provider webhooks. Each unique providerEventId is stored only once.';
