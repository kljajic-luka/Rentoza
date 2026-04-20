-- ============================================================================
-- V71: Trust & Evidence System Audit Remediation
-- ============================================================================
-- Addresses findings from the forensic production-readiness audit:
--   R1/R2: image_hash on check_in_photos — column already exists from V61
--          (application code now populates it in CheckInPhotoService)
--   R3: audit_storage_key sentinel — application code now sets 'AUDIT_UPLOAD_FAILED'
--   R4: Lock timeout — application code uses @QueryHints
--   R5: Idempotency guard — application code check
--   R6: Write-once storage — application code passes x-upsert: false
--   R7: Event partitioning preparation (P2)
--   R9: GPS cross-validation indexes (P2)
--
-- This migration adds:
--   1. Index for audit integrity gap detection (R3 ops query support)
--   2. Index for image hash cross-booking fraud queries (R1 performance)
--   3. Preparation for event table archival (R7)
--   4. Listing GPS column index for upload-time cross-validation (R9)
-- ============================================================================

-- 1. R3: Partial index to efficiently find photos with audit integrity gaps
-- Ops query: SELECT * FROM check_in_photos WHERE audit_storage_key = 'AUDIT_UPLOAD_FAILED'
CREATE INDEX IF NOT EXISTS idx_checkin_photo_audit_gap
    ON check_in_photos(audit_storage_key)
    WHERE audit_storage_key = 'AUDIT_UPLOAD_FAILED';

-- 2. R1: Ensure image_hash index exists (V61 created it, but guard with IF NOT EXISTS)
-- This index supports the cross-booking duplicate detection query in CheckInPhotoRepository
CREATE INDEX IF NOT EXISTS idx_check_in_photo_hash
    ON check_in_photos(image_hash)
    WHERE image_hash IS NOT NULL;

-- 3. R7 (P2): Add event_timestamp index for future partition boundary queries
-- When partitioning is implemented, the cutover query will use:
--   INSERT INTO check_in_events_partitioned SELECT * FROM check_in_events WHERE event_timestamp >= $boundary
CREATE INDEX IF NOT EXISTS idx_checkin_event_timestamp
    ON check_in_events(event_timestamp);

-- 4. R9 (P2): Add composite index for GPS cross-validation queries at upload time
-- Supports querying a car's listing location alongside booking to compare with photo GPS
-- Note: columns are location_latitude/location_longitude (prefixed via @AttributeOverride in Car.java)
CREATE INDEX IF NOT EXISTS idx_car_location_coords
    ON cars(location_latitude, location_longitude)
    WHERE location_latitude IS NOT NULL AND location_longitude IS NOT NULL;

-- ============================================================================
-- Verification queries (run manually after migration)
-- ============================================================================
-- SELECT indexname FROM pg_indexes WHERE tablename = 'check_in_photos'
--   AND indexname = 'idx_checkin_photo_audit_gap';
--
-- SELECT indexname FROM pg_indexes WHERE tablename = 'check_in_events'
--   AND indexname = 'idx_checkin_event_timestamp';
--
-- SELECT indexname FROM pg_indexes WHERE tablename = 'cars'
--   AND indexname = 'idx_car_location_coords';
