-- ============================================================================
-- V62: Review Immutability + Uniqueness Constraints
-- Feature 11 Pre-Production Hardening (TURO Standard)
-- ============================================================================
--
-- P0-4 FIX: Enforce review immutability at DB level
--   - Remove the RLS update policy that allows review editing
--   - Add a trigger that prevents any UPDATE on review content fields
--
-- P1-1 FIX: Unique constraint per booking per direction
--   - Prevents race conditions where two concurrent requests could create
--     duplicate reviews for the same booking+direction
-- ============================================================================

-- ============================================================================
-- P0-4: Remove review update RLS policy (prevents editing via Supabase client)
-- ============================================================================
DROP POLICY IF EXISTS "reviews_author_update" ON public.reviews;

-- ============================================================================
-- P0-4: Add immutability trigger (prevents editing via any path including direct SQL)
-- ============================================================================

-- Function to prevent updates on review content/rating fields
-- Allows only reviewer/reviewee NULL-ing for GDPR anonymization
CREATE OR REPLACE FUNCTION prevent_review_mutation()
RETURNS TRIGGER AS $$
BEGIN
    -- Allow anonymization (setting reviewer/reviewee to NULL for GDPR)
    IF (NEW.reviewer_id IS NULL AND OLD.reviewer_id IS NOT NULL) OR
       (NEW.reviewee_id IS NULL AND OLD.reviewee_id IS NOT NULL) THEN
        -- Only allow the anonymization fields to change
        NEW.rating := OLD.rating;
        NEW.comment := OLD.comment;
        NEW.cleanliness_rating := OLD.cleanliness_rating;
        NEW.maintenance_rating := OLD.maintenance_rating;
        NEW.communication_rating := OLD.communication_rating;
        NEW.convenience_rating := OLD.convenience_rating;
        NEW.accuracy_rating := OLD.accuracy_rating;
        NEW.timeliness_rating := OLD.timeliness_rating;
        NEW.respect_for_rules_rating := OLD.respect_for_rules_rating;
        NEW.direction := OLD.direction;
        NEW.car_id := OLD.car_id;
        NEW.booking_id := OLD.booking_id;
        NEW.created_at := OLD.created_at;
        RETURN NEW;
    END IF;

    -- Block all other updates
    RAISE EXCEPTION 'Reviews are immutable and cannot be edited after submission.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Drop existing trigger if any
DROP TRIGGER IF EXISTS trg_reviews_immutable ON public.reviews;

-- Create trigger: fires BEFORE UPDATE to block mutations
CREATE TRIGGER trg_reviews_immutable
    BEFORE UPDATE ON public.reviews
    FOR EACH ROW
    EXECUTE FUNCTION prevent_review_mutation();

-- ============================================================================
-- P1-1: Unique constraint - one review per booking per direction
-- Prevents duplicate reviews from race conditions
-- ============================================================================

-- First, clean up any existing duplicates (keep the earliest review)
DELETE FROM public.reviews r1
WHERE EXISTS (
    SELECT 1 FROM public.reviews r2
    WHERE r2.booking_id = r1.booking_id
      AND r2.direction = r1.direction
      AND r2.id < r1.id
);

-- Add the unique constraint
ALTER TABLE public.reviews
    DROP CONSTRAINT IF EXISTS uq_reviews_booking_direction;

ALTER TABLE public.reviews
    ADD CONSTRAINT uq_reviews_booking_direction
    UNIQUE (booking_id, direction);

-- ============================================================================
-- Summary of changes:
-- 1. Removed reviews_author_update RLS policy (no more editing via Supabase)
-- 2. Added prevent_review_mutation() trigger (immutability at DB level)
-- 3. Added uq_reviews_booking_direction unique constraint (prevents duplicates)
-- ============================================================================
