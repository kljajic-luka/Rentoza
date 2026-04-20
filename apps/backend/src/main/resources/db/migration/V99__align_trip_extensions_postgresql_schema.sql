-- ============================================================================
-- V99: Align trip_extensions schema with PostgreSQL entity contract
-- ============================================================================
-- Context:
-- Some PostgreSQL environments contain a manually created trip_extensions table
-- because V17/V37 were authored with MySQL-only syntax and never completed via
-- Flyway. Current code expects the full JPA contract, including optimistic
-- locking and pricing fields.
--
-- This migration is forward-only and idempotent. It aligns the live table with
-- current application expectations without rewriting data.
-- ============================================================================

ALTER TABLE IF EXISTS trip_extensions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS additional_cost DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reason VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS response_deadline TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS host_response VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS responded_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS requested_end_date_utc TIMESTAMP WITH TIME ZONE NULL;

CREATE OR REPLACE FUNCTION set_trip_extensions_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    missing_required_columns TEXT[];
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_name = 'trip_extensions'
    ) THEN
        SELECT ARRAY_AGG(required_column)
        INTO missing_required_columns
        FROM (
            SELECT required_column
            FROM unnest(ARRAY[
                'booking_id',
                'original_end_date',
                'requested_end_date',
                'additional_days',
                'daily_rate',
                'status'
            ]) AS required_column
            WHERE NOT EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'trip_extensions'
                  AND column_name = required_column
            )
        ) missing;

        IF missing_required_columns IS NOT NULL THEN
            RAISE EXCEPTION USING MESSAGE = format(
                'trip_extensions exists but is missing required base columns: %s. Repair the table shape before applying V99.',
                array_to_string(missing_required_columns, ', ')
            );
        END IF;

        COMMENT ON COLUMN trip_extensions.version IS
            'Optimistic locking column used by Hibernate @Version';

        COMMENT ON COLUMN trip_extensions.additional_cost IS
            'Total additional cost for the extension request';

        CREATE INDEX IF NOT EXISTS idx_trip_extension_booking ON trip_extensions (booking_id);
        CREATE INDEX IF NOT EXISTS idx_trip_extension_status ON trip_extensions (status);
        CREATE INDEX IF NOT EXISTS idx_trip_extension_deadline ON trip_extensions (response_deadline);

        IF NOT EXISTS (
            SELECT 1
            FROM pg_trigger
            WHERE tgname = 'trg_trip_extensions_updated_at'
        ) THEN
            CREATE TRIGGER trg_trip_extensions_updated_at
                BEFORE UPDATE ON trip_extensions
                FOR EACH ROW
                EXECUTE FUNCTION set_trip_extensions_updated_at();
        END IF;
    END IF;
END $$;