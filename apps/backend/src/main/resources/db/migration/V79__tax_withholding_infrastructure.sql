-- V79: Tax Withholding Infrastructure
-- Adds withholding calculation fields to payout_ledger and creates tax_withholding_summary
-- for monthly aggregation and PPPPD (Serbian tax form) filing tracking.

-- =====================================================================
-- 1. Add tax withholding columns to payout_ledger
-- =====================================================================

ALTER TABLE payout_ledger
    ADD COLUMN IF NOT EXISTS gross_owner_income       NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS normalized_expenses_rate NUMERIC(5, 4) DEFAULT 0.2000,
    ADD COLUMN IF NOT EXISTS taxable_base             NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS income_tax_rate          NUMERIC(5, 4) DEFAULT 0.2000,
    ADD COLUMN IF NOT EXISTS income_tax_withheld      NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS net_owner_payout         NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS tax_withholding_status   VARCHAR(30) DEFAULT 'CALCULATED',
    ADD COLUMN IF NOT EXISTS owner_tax_type           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS remittance_reference     VARCHAR(100);

COMMENT ON COLUMN payout_ledger.gross_owner_income IS 'tripAmount - platformFee (owner gross income before tax)';
COMMENT ON COLUMN payout_ledger.normalized_expenses_rate IS 'Serbian normalized expenses rate (default 20%)';
COMMENT ON COLUMN payout_ledger.taxable_base IS 'grossOwnerIncome - (grossOwnerIncome * normalizedExpensesRate)';
COMMENT ON COLUMN payout_ledger.income_tax_rate IS 'Serbian income tax rate on taxable base (default 20%)';
COMMENT ON COLUMN payout_ledger.income_tax_withheld IS 'taxableBase * incomeTaxRate';
COMMENT ON COLUMN payout_ledger.net_owner_payout IS 'grossOwnerIncome - incomeTaxWithheld (actual transfer amount)';
COMMENT ON COLUMN payout_ledger.tax_withholding_status IS 'CALCULATED | EXEMPT | REMITTED | REPORTED';
COMMENT ON COLUMN payout_ledger.owner_tax_type IS 'INDIVIDUAL (withholding applies) | LEGAL_ENTITY (exempt)';
COMMENT ON COLUMN payout_ledger.remittance_reference IS 'Tax remittance payment reference for PPPPD filing';

-- =====================================================================
-- 2. Create tax_withholding_summary table
-- =====================================================================

CREATE TABLE IF NOT EXISTS tax_withholding_summary (
    id                      BIGSERIAL PRIMARY KEY,
    owner_user_id           BIGINT NOT NULL REFERENCES users(id),
    tax_period_year         INT NOT NULL,
    tax_period_month        INT NOT NULL,
    total_gross_income      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_normalized_expenses NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_taxable_base      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_tax_withheld      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_net_paid          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    payout_count            INT NOT NULL DEFAULT 0,
    ppppd_filed             BOOLEAN NOT NULL DEFAULT FALSE,
    ppppd_filing_date       DATE,
    ppppd_reference         VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tax_summary_owner_period UNIQUE (owner_user_id, tax_period_year, tax_period_month),
    CONSTRAINT ck_tax_period_month CHECK (tax_period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_tax_period_year CHECK (tax_period_year >= 2024)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_tws_owner ON tax_withholding_summary (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_tws_period ON tax_withholding_summary (tax_period_year, tax_period_month);
CREATE INDEX IF NOT EXISTS idx_tws_unfiled ON tax_withholding_summary (ppppd_filed)
    WHERE ppppd_filed = FALSE;

COMMENT ON TABLE tax_withholding_summary IS 'Monthly tax withholding aggregation per owner for PPPPD filing';

-- =====================================================================
-- 3. Backfill existing payout_ledger rows
-- =====================================================================

-- Set gross_owner_income from existing hostPayoutAmount (which = tripAmount - platformFee)
UPDATE payout_ledger
SET gross_owner_income = host_payout_amount
WHERE gross_owner_income IS NULL;

-- For existing rows, assume INDIVIDUAL tax type and compute withholding retroactively.
-- This is a one-time backfill; rates are snapshots and will not change.
UPDATE payout_ledger
SET owner_tax_type = 'INDIVIDUAL',
    normalized_expenses_rate = 0.2000,
    taxable_base = ROUND(host_payout_amount * (1 - 0.2000), 2),
    income_tax_rate = 0.2000,
    income_tax_withheld = ROUND(ROUND(host_payout_amount * (1 - 0.2000), 2) * 0.2000, 2),
    net_owner_payout = host_payout_amount - ROUND(ROUND(host_payout_amount * (1 - 0.2000), 2) * 0.2000, 2),
    tax_withholding_status = 'CALCULATED'
WHERE income_tax_withheld IS NULL;
