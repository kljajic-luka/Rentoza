-- =====================================================================
-- V85: Backfill listing_status from legacy approval_status
-- =====================================================================
-- Problem:
-- Public marketplace queries now use listing_status=APPROVED.
-- Some existing rows still have legacy approval_status=APPROVED while
-- listing_status remained PENDING_APPROVAL, causing zero public results.
--
-- Goal:
-- One-time data repair to align listing_status with approval_status for
-- pre-Phase-5 rows and any drifted records.
-- =====================================================================

UPDATE cars
SET listing_status = CASE approval_status::text
    WHEN 'APPROVED' THEN 'APPROVED'
    WHEN 'REJECTED' THEN 'REJECTED'
    WHEN 'SUSPENDED' THEN 'SUSPENDED'
    WHEN 'PENDING' THEN 'PENDING_APPROVAL'
    ELSE COALESCE(listing_status::text, 'PENDING_APPROVAL')
END
WHERE
    (approval_status::text = 'APPROVED' AND listing_status::text IS DISTINCT FROM 'APPROVED')
 OR (approval_status::text = 'REJECTED' AND listing_status::text IS DISTINCT FROM 'REJECTED')
 OR (approval_status::text = 'SUSPENDED' AND listing_status::text IS DISTINCT FROM 'SUSPENDED')
 OR (approval_status::text = 'PENDING' AND listing_status::text IS DISTINCT FROM 'PENDING_APPROVAL');
