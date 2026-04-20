-- ============================================================================
-- V98: Backfill missing rental agreement terms reference columns
-- ============================================================================
-- Context:
-- Some environments can miss V90__rental_agreement_terms_reference.sql due to
-- migration drift. Current code expects these columns on reads.
--
-- This migration is intentionally idempotent and safe to run repeatedly.
-- ============================================================================

ALTER TABLE IF EXISTS rental_agreements
    ADD COLUMN IF NOT EXISTS terms_template_id VARCHAR(50) NULL,
    ADD COLUMN IF NOT EXISTS terms_template_hash VARCHAR(128) NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_name = 'rental_agreements'
    ) THEN
        COMMENT ON COLUMN rental_agreements.terms_template_id IS
            'Identifier of the legal terms template version accepted by parties';

        COMMENT ON COLUMN rental_agreements.terms_template_hash IS
            'SHA-256 hash of the terms template content at acceptance time';
    END IF;
END $$;
