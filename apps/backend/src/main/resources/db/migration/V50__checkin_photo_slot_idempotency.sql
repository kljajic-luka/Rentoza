-- ============================================================================
-- V50: Enforce one active photo per required slot per booking
-- ============================================================================
-- Prevents duplicate photos for required check-in/checkout slots.
-- Only applies to required photo types (exterior angles, interior, odometer,
-- fuel gauge). Damage and custom types are multi-entry by design.
-- Soft-deleted rows (deleted_at IS NOT NULL) are excluded so retakes work
-- via soft-delete-then-insert.
--
-- SAFETY: Before creating the unique index, soft-delete any pre-existing
-- duplicate active rows (keeping the newest per slot) so index creation
-- does not fail on real production data.
-- ============================================================================

-- Step 1: Soft-delete pre-existing duplicate active required-slot rows.
-- For each (booking_id, photo_type) with >1 active row, keep only the newest
-- (by id DESC) and mark the rest as soft-deleted.
UPDATE check_in_photos
SET deleted_at = NOW(),
    deleted_by = NULL,
    deleted_reason = 'V50_MIGRATION_DEDUP'
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY booking_id, photo_type ORDER BY id DESC) AS rn
        FROM check_in_photos
        WHERE deleted_at IS NULL
          AND photo_type IN (
              'HOST_EXTERIOR_FRONT', 'HOST_EXTERIOR_REAR', 'HOST_EXTERIOR_LEFT', 'HOST_EXTERIOR_RIGHT',
              'HOST_INTERIOR_DASHBOARD', 'HOST_INTERIOR_REAR', 'HOST_ODOMETER', 'HOST_FUEL_GAUGE',
              'GUEST_EXTERIOR_FRONT', 'GUEST_EXTERIOR_REAR', 'GUEST_EXTERIOR_LEFT', 'GUEST_EXTERIOR_RIGHT',
              'GUEST_INTERIOR_DASHBOARD', 'GUEST_INTERIOR_REAR', 'GUEST_ODOMETER', 'GUEST_FUEL_GAUGE',
              'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT',
              'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE',
              'HOST_CHECKOUT_EXTERIOR_FRONT', 'HOST_CHECKOUT_EXTERIOR_REAR', 'HOST_CHECKOUT_EXTERIOR_LEFT', 'HOST_CHECKOUT_EXTERIOR_RIGHT',
              'HOST_CHECKOUT_INTERIOR_DASHBOARD', 'HOST_CHECKOUT_INTERIOR_REAR', 'HOST_CHECKOUT_ODOMETER', 'HOST_CHECKOUT_FUEL_GAUGE'
          )
    ) ranked
    WHERE rn > 1
);

-- Step 2: Create the partial unique index (now safe — no duplicate active rows).
CREATE UNIQUE INDEX IF NOT EXISTS uq_checkin_photo_required_slot
ON check_in_photos (booking_id, photo_type)
WHERE deleted_at IS NULL
AND photo_type IN (
    -- Host check-in required (8)
    'HOST_EXTERIOR_FRONT', 'HOST_EXTERIOR_REAR', 'HOST_EXTERIOR_LEFT', 'HOST_EXTERIOR_RIGHT',
    'HOST_INTERIOR_DASHBOARD', 'HOST_INTERIOR_REAR', 'HOST_ODOMETER', 'HOST_FUEL_GAUGE',
    -- Guest check-in required (8)
    'GUEST_EXTERIOR_FRONT', 'GUEST_EXTERIOR_REAR', 'GUEST_EXTERIOR_LEFT', 'GUEST_EXTERIOR_RIGHT',
    'GUEST_INTERIOR_DASHBOARD', 'GUEST_INTERIOR_REAR', 'GUEST_ODOMETER', 'GUEST_FUEL_GAUGE',
    -- Guest checkout required (6)
    'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT',
    'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE',
    -- Host checkout required (8)
    'HOST_CHECKOUT_EXTERIOR_FRONT', 'HOST_CHECKOUT_EXTERIOR_REAR', 'HOST_CHECKOUT_EXTERIOR_LEFT', 'HOST_CHECKOUT_EXTERIOR_RIGHT',
    'HOST_CHECKOUT_INTERIOR_DASHBOARD', 'HOST_CHECKOUT_INTERIOR_REAR', 'HOST_CHECKOUT_ODOMETER', 'HOST_CHECKOUT_FUEL_GAUGE'
);
