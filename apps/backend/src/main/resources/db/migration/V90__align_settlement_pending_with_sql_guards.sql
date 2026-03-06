-- ============================================================================
-- V90: Align settlement-pending occupancy semantics with PostgreSQL guards
-- ============================================================================
-- V88 updated the overlap-prevention trigger functions for checkout-in-progress
-- statuses, but the later CHECKOUT_SETTLEMENT_PENDING status was added only in
-- Java. This forward migration brings the DB trigger functions and the blocked
-- date occupancy helper back in sync with BookingStatus.BLOCKING_STATUSES.
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_renter_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
    IF NEW.status NOT IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                          'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'CHECK_IN_DISPUTE',
                          'IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE',
                          'CHECKOUT_HOST_COMPLETE', 'CHECKOUT_SETTLEMENT_PENDING') THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('renter_booking_' || NEW.renter_id::text));

    SELECT COUNT(*) INTO overlap_count
    FROM bookings
    WHERE renter_id = NEW.renter_id
      AND id != COALESCE(NEW.id, 0)
      AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                     'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'CHECK_IN_DISPUTE',
                     'IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE',
                     'CHECKOUT_HOST_COMPLETE', 'CHECKOUT_SETTLEMENT_PENDING')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;

    IF overlap_count > 0 THEN
        RAISE EXCEPTION 'USER_OVERLAP: User already has an active booking for this time period'
            USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_car_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
    IF NEW.status NOT IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                          'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'CHECK_IN_DISPUTE',
                          'IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE',
                          'CHECKOUT_HOST_COMPLETE', 'CHECKOUT_SETTLEMENT_PENDING') THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(NEW.car_id);

    SELECT COUNT(*) INTO overlap_count
    FROM bookings
    WHERE car_id = NEW.car_id
      AND id != COALESCE(NEW.id, 0)
      AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                     'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'CHECK_IN_DISPUTE',
                     'IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE',
                     'CHECKOUT_HOST_COMPLETE', 'CHECKOUT_SETTLEMENT_PENDING')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;

    IF overlap_count > 0 THEN
        RAISE EXCEPTION 'CAR_UNAVAILABLE: Car is already booked for the requested time period'
            USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

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
        'CHECKOUT_HOST_COMPLETE',
        'CHECKOUT_SETTLEMENT_PENDING'
    ]);
END;
$$;