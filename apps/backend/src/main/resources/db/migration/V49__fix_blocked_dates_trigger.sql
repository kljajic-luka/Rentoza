-- ============================================================================
-- Fix blocked_dates trigger and align schema with booking cancellation flow
-- ============================================================================
-- Issue: trigger_booking_block_dates references booking_id on blocked_dates,
-- but production table lacks this column (causing cancellation rollback).
--
-- Enterprise fix:
-- 1) Add booking_id + owner_id columns if missing (non-breaking, nullable)
-- 2) Add FK + indexes for integrity and performance
-- 3) Replace trigger to use booking start_time/end_time range
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'blocked_dates'
          AND column_name = 'booking_id'
    ) THEN
        ALTER TABLE public.blocked_dates
            ADD COLUMN booking_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'blocked_dates'
          AND column_name = 'owner_id'
    ) THEN
        ALTER TABLE public.blocked_dates
            ADD COLUMN owner_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_blocked_dates_booking'
    ) THEN
        ALTER TABLE public.blocked_dates
            ADD CONSTRAINT fk_blocked_dates_booking
            FOREIGN KEY (booking_id) REFERENCES public.bookings(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_blocked_dates_owner'
    ) THEN
        ALTER TABLE public.blocked_dates
            ADD CONSTRAINT fk_blocked_dates_owner
            FOREIGN KEY (owner_id) REFERENCES public.users(id)
            ON DELETE SET NULL;
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_blocked_dates_booking
    ON public.blocked_dates(booking_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_blocked_dates_booking_unique
    ON public.blocked_dates(booking_id)
    WHERE booking_id IS NOT NULL;

-- Replace trigger to work with range-based blocked_dates table
CREATE OR REPLACE FUNCTION public.trigger_booking_block_dates()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_owner_id BIGINT;
    v_occupying_statuses TEXT[] := ARRAY[
        'ACTIVE', 'APPROVED', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE',
        'CHECK_IN_COMPLETE', 'CHECK_IN_DISPUTE', 'IN_TRIP', 'CHECKOUT_OPEN',
        'CHECKOUT_GUEST_COMPLETE', 'CHECKOUT_HOST_COMPLETE'
    ];
BEGIN
    SELECT owner_id INTO v_owner_id
    FROM public.cars
    WHERE id = NEW.car_id;

    -- Block dates when booking enters an occupying status
    IF NEW.status = ANY(v_occupying_statuses)
       AND (OLD IS NULL OR NOT (OLD.status = ANY(v_occupying_statuses))) THEN

        INSERT INTO public.blocked_dates (car_id, start_date, end_date, booking_id, owner_id, created_at)
        VALUES (NEW.car_id, NEW.start_time::date, NEW.end_time::date, NEW.id, v_owner_id, NOW())
        ON CONFLICT (booking_id) DO UPDATE
            SET start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                car_id = EXCLUDED.car_id,
                owner_id = EXCLUDED.owner_id;
    END IF;

    -- Unblock dates when booking is cancelled
    IF NEW.status = 'CANCELLED'
       AND (OLD.status IS DISTINCT FROM 'CANCELLED') THEN
        DELETE FROM public.blocked_dates
        WHERE booking_id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trigger_booking_dates ON public.bookings;
CREATE TRIGGER trigger_booking_dates
    AFTER INSERT OR UPDATE ON public.bookings
    FOR EACH ROW
    EXECUTE FUNCTION public.trigger_booking_block_dates();
