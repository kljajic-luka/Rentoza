-- =============================================================================
-- V52: Normalize booking start/end timestamps to Serbia local wall-clock time
-- =============================================================================
--
-- Problem:
-- - start_time/end_time currently use TIMESTAMP WITH TIME ZONE in production.
-- - Application contract expects LocalDateTime in Europe/Belgrade (no offset).
-- - This mismatch causes 1h/2h drift in countdown/scheduler windows when DB/session
--   timezone differs (UTC vs Europe/Belgrade).
--
-- Fix:
-- - Convert bookings.start_time and bookings.end_time to
--   TIMESTAMP WITHOUT TIME ZONE.
-- - Use AT TIME ZONE 'Europe/Belgrade' so the currently displayed local clock
--   value is preserved after migration.
--
-- Idempotency:
-- - Safe to run multiple times. Conversion executes only when the column is
--   still TIMESTAMP WITH TIME ZONE.
-- =============================================================================

DO $$
DECLARE
    start_time_type TEXT;
    end_time_type TEXT;
BEGIN
    SELECT c.data_type
      INTO start_time_type
      FROM information_schema.columns c
     WHERE c.table_schema = current_schema()
       AND c.table_name = 'bookings'
       AND c.column_name = 'start_time';

    SELECT c.data_type
      INTO end_time_type
      FROM information_schema.columns c
     WHERE c.table_schema = current_schema()
       AND c.table_name = 'bookings'
       AND c.column_name = 'end_time';

    IF start_time_type = 'timestamp with time zone' THEN
        ALTER TABLE bookings
            ALTER COLUMN start_time TYPE TIMESTAMP WITHOUT TIME ZONE
            USING (start_time AT TIME ZONE 'Europe/Belgrade');
    END IF;

    IF end_time_type = 'timestamp with time zone' THEN
        ALTER TABLE bookings
            ALTER COLUMN end_time TYPE TIMESTAMP WITHOUT TIME ZONE
            USING (end_time AT TIME ZONE 'Europe/Belgrade');
    END IF;
END $$;
