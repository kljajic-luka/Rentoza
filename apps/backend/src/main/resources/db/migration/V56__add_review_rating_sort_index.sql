-- =============================================================================
-- V56: Add composite index for review-based rating sort
-- =============================================================================
-- The correlated subquery in CarService.ratingOrderSpec() filters
-- reviews by (reviewee_id, direction) and aggregates AVG(rating).
-- This covering index lets PostgreSQL satisfy the subquery with an
-- index-only scan instead of a sequential scan on reviews.
--
-- Columns:
--   reviewee_id  — join key to users.id (car owner)
--   direction    — filter key (FROM_USER)
--   rating       — aggregate column (AVG) — included for index-only scan
-- =============================================================================

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reviews_reviewee_direction_rating
    ON reviews (reviewee_id, direction)
    INCLUDE (rating);
