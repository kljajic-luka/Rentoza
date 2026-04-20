-- ============================================================================
-- V60: Fix no-show bookings leaking blocked_dates calendar rows
-- ============================================================================
-- Problem:
--   The V49 trigger only deleted blocked_dates rows when NEW.status = 'CANCELLED'.
--   Bookings resolved as NO_SHOW_HOST or NO_SHOW_GUEST never triggered the
--   delete branch, leaving their rows in blocked_dates until the original end_time.
--   Availability queries (existsOverlappingBlockedDates, findByCarIdOrderByStartDateAsc)
--   consume blocked_dates as-is, so stale rows suppress future inventory.
--
-- Fix:
--   1) Centralise the occupancy set in a reusable SQL function (one source of truth).
--   2) Rewrite trigger: UPSERT on enter-occupying, DELETE on leave-occupying
--      (covers NO_SHOW_*, COMPLETED, EXPIRED, CANCELLED — any exit from occupying).
--   3) One-time cleanup: remove all booking-linked rows whose booking's current
--      status is not occupying (covers rows stranded by the old trigger).
--
-- Safety:
--   - All DDL is idempotent (CREATE OR REPLACE, IF NOT EXISTS).
--   - Cleanup DELETE is bounded by booking_id IS NOT NULL.
--   - Manual blocks (booking_id IS NULL) are untouched.
--   - FK cascade on bookings.id already handles hard-deletes (V49 schema).
-- ============================================================================

-- ============================================================================
-- PART 1: Canonical occupancy helper function
--         (IMMUTABLE so it can be used in indexes / generated columns later)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.fn_booking_is_occupying(p_status TEXT)
    RETURNS BOOLEAN
    LANGUAGE plpgsql
    IMMUTABLE
AS $$
BEGIN
    RETURN p_status = ANY(ARRAY[
        'ACTIVE',
        'APPROVED',
        'CHECK_IN_OPEN',
        'CHECK_IN_HOST_COMPLETE',
        'CHECK_IN_COMPLETE',
        'CHECK_IN_DISPUTE',
        'IN_TRIP',
        'CHECKOUT_OPEN',
        'CHECKOUT_GUEST_COMPLETE',
        'CHECKOUT_HOST_COMPLETE'
    ]);
END;
$$;

-- ============================================================================
-- PART 2: Rewrite trigger function
-- ============================================================================
CREATE OR REPLACE FUNCTION public.trigger_booking_block_dates()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_owner_id BIGINT;
BEGIN
    -- -----------------------------------------------------------------------
    -- Determine owning user for the car (needed for new inserts)
    -- -----------------------------------------------------------------------
    SELECT owner_id INTO v_owner_id
    FROM public.cars
    WHERE id = NEW.car_id;

    -- -----------------------------------------------------------------------
    -- Case A: Entering an occupying status
    --   UPSERT the blocked_dates row so start/end changes are always current.
    --   Handles: INSERT into occupying, UPDATE entering occupying,
    --            UPDATE staying occupying (time changes, e.g. rescheduling).
    -- -----------------------------------------------------------------------
    IF public.fn_booking_is_occupying(NEW.status) THEN

        INSERT INTO public.blocked_dates
            (car_id, start_date, end_date, booking_id, owner_id, created_at)
        VALUES
            (NEW.car_id,
             NEW.start_time::date,
             NEW.end_time::date,
             NEW.id,
             v_owner_id,
             NOW())
        ON CONFLICT (booking_id)
        DO UPDATE SET
            start_date = EXCLUDED.start_date,
            end_date   = EXCLUDED.end_date,
            car_id     = EXCLUDED.car_id,
            owner_id   = EXCLUDED.owner_id;

    -- -----------------------------------------------------------------------
    -- Case B: Leaving an occupying status  (includes CANCELLED, COMPLETED,
    --         NO_SHOW_HOST, NO_SHOW_GUEST, EXPIRED, DECLINED, any future
    --         terminal status — anything NOT in the occupying set)
    --   DELETE the booking-linked row so the dates become available again.
    --   Only fires when OLD was occupying, preventing spurious deletes from
    --   non-occupying → non-occupying transitions (e.g. PENDING → DECLINED).
    -- -----------------------------------------------------------------------
    ELSIF TG_OP = 'UPDATE'
          AND OLD IS NOT NULL
          AND public.fn_booking_is_occupying(OLD.status) THEN

        DELETE FROM public.blocked_dates
        WHERE booking_id = OLD.id;

    END IF;

    RETURN NEW;
END;
$$;

-- Re-attach trigger (DROP IF EXISTS first for idempotency)
DROP TRIGGER IF EXISTS trigger_booking_dates ON public.bookings;
CREATE TRIGGER trigger_booking_dates
    AFTER INSERT OR UPDATE ON public.bookings
    FOR EACH ROW
EXECUTE FUNCTION public.trigger_booking_block_dates();

-- ============================================================================
-- PART 3: One-time cleanup of stale booking-linked rows
--         Removes rows where the booking's current status is non-occupying.
--         Manual blocks (booking_id IS NULL) are untouched.
--         Safe to run more than once (rows absent → DELETE 0 rows).
-- ============================================================================
DELETE FROM public.blocked_dates bd
WHERE bd.booking_id IS NOT NULL
  AND NOT public.fn_booking_is_occupying(
          (SELECT b.status FROM public.bookings b WHERE b.id = bd.booking_id)
      );

-- ============================================================================
-- VERIFICATION (informational — does not fail migration if count > 0)
-- ============================================================================
DO $$
DECLARE
    v_remaining BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_remaining
    FROM public.blocked_dates bd
    WHERE bd.booking_id IS NOT NULL
      AND NOT public.fn_booking_is_occupying(
              (SELECT b.status FROM public.bookings b WHERE b.id = bd.booking_id)
          );

    IF v_remaining > 0 THEN
        RAISE WARNING 'V60 cleanup: % stale booking-linked rows could not be removed (booking may not exist). Investigate manually.', v_remaining;
    ELSE
        RAISE NOTICE 'V60 cleanup: all stale booking-linked rows successfully removed.';
    END IF;
END$$;
