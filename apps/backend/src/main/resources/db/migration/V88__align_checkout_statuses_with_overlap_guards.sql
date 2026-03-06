-- ============================================================================
-- V88: Align checkout statuses with overlap guards
-- ============================================================================
-- Existing databases have already run V58, so this forward migration updates the
-- active PostgreSQL trigger functions to keep checkout-in-progress bookings from
-- being overlapped by new car or renter bookings.
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_prevent_overlapping_renter_bookings()
RETURNS TRIGGER AS $$
DECLARE
    overlap_count INTEGER;
BEGIN
    IF NEW.status NOT IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN',
                          'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'CHECK_IN_DISPUTE',
                          'IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE',
                          'CHECKOUT_HOST_COMPLETE') THEN
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
                     'CHECKOUT_HOST_COMPLETE')
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
                          'CHECKOUT_HOST_COMPLETE') THEN
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
                     'CHECKOUT_HOST_COMPLETE')
      AND start_time < NEW.end_time
      AND end_time > NEW.start_time;

    IF overlap_count > 0 THEN
        RAISE EXCEPTION 'CAR_UNAVAILABLE: Car is already booked for the requested time period'
            USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;