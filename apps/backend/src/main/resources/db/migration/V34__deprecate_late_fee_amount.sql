-- ========================================================================
-- V34: Deprecate booking.late_fee_amount Column
-- ========================================================================
-- Migration for Saga Integration: Mark old fee calculation field as deprecated
-- 
-- Context:
-- - Before: CheckOutService calculated late fees, stored in booking.late_fee_amount
-- - After: CheckoutSagaOrchestrator calculates all charges, stores in checkout_saga_state.late_fee
-- - This migration marks the old field as deprecated but keeps it for backward compatibility
--
-- Timeline:
-- - Week 1-3: Deploy saga integration, old field remains but unused
-- - Week 4: Deploy this migration to mark field deprecated
-- - v2.0.0 (Future): Remove field entirely
--
-- Reference: V15__checkout_workflow.sql (line 62) created this field
-- Reference: V21__checkout_saga_state.sql created saga table
-- ========================================================================

-- ========================================================================
-- Step 1: Add Comment to Document Deprecation
-- ========================================================================

ALTER TABLE bookings 
MODIFY COLUMN late_fee_amount DECIMAL(19, 2) NULL 
COMMENT 'DEPRECATED (2025-12-16): Use checkout_saga_state.late_fee instead. 
Service no longer calculates late fees - saga is single source of truth. 
This field is kept for backward compatibility with old bookings (pre-saga integration). 
Will be removed in v2.0.0.';

-- ========================================================================
-- Step 2: Create View for Backward Compatibility
-- ========================================================================

-- This view allows legacy queries to continue working while using saga data
CREATE OR REPLACE VIEW vw_booking_charges AS
SELECT 
    b.id AS booking_id,
    b.status,
    b.checkout_completed_at,
    
    -- Saga charges (preferred source)
    css.late_fee AS late_fee_rsd,
    css.extra_mileage_charge AS mileage_charge_eur,
    css.fuel_charge AS fuel_charge_eur,
    css.total_charges AS total_charges_mixed,
    
    -- Legacy field (deprecated, only for old bookings)
    b.late_fee_amount AS deprecated_late_fee,
    
    -- Calculated: Use saga data if available, fallback to legacy
    COALESCE(css.late_fee, b.late_fee_amount) AS effective_late_fee,
    
    -- Metadata
    CASE 
        WHEN css.id IS NOT NULL THEN 'SAGA'
        WHEN b.late_fee_amount IS NOT NULL THEN 'LEGACY'
        ELSE 'NONE'
    END AS charge_source,
    
    css.status AS saga_status,
    css.created_at AS saga_created_at
    
FROM bookings b
LEFT JOIN checkout_saga_state css ON css.booking_id = b.id AND css.status = 'COMPLETED'
WHERE b.status = 'COMPLETED';

-- ========================================================================
-- Step 3: Update Dashboard Queries (Example Queries)
-- ========================================================================

-- Example 1: Total late fees collected (OLD query)
-- SELECT SUM(late_fee_amount) FROM bookings WHERE checkout_completed_at >= '2025-01-01';

-- Example 1: Total late fees collected (NEW query)
-- Query saga table for new data, fallback to legacy for old bookings
-- SELECT 
--     SUM(effective_late_fee) AS total_late_fees
-- FROM vw_booking_charges
-- WHERE checkout_completed_at >= '2025-01-01';

-- Example 2: Bookings with late fees (OLD query)
-- SELECT * FROM bookings WHERE late_fee_amount > 0;

-- Example 2: Bookings with late fees (NEW query)
-- SELECT * FROM vw_booking_charges WHERE effective_late_fee > 0;

-- ========================================================================
-- Step 4: Data Consistency Check
-- ========================================================================

-- Find bookings completed after saga integration that still use legacy field
-- These should be ZERO after successful deployment
SELECT 
    b.id,
    b.checkout_completed_at,
    b.late_fee_amount AS legacy_fee,
    css.late_fee AS saga_fee,
    css.status AS saga_status
FROM bookings b
LEFT JOIN checkout_saga_state css ON css.booking_id = b.id
WHERE b.status = 'COMPLETED'
  AND b.checkout_completed_at >= '2025-12-16'  -- After saga integration deployment
  AND b.late_fee_amount IS NOT NULL  -- Legacy field used (should be NULL)
  AND (css.id IS NULL OR css.status != 'COMPLETED');  -- Saga didn't run or failed

-- If this query returns rows, it means:
-- 1. Saga integration not working for some bookings
-- 2. Service still writing to legacy field (code regression)
-- 3. Saga failing consistently
-- Action: Investigate and fix before proceeding with deprecation

-- ========================================================================
-- Step 5: Gradual Migration Plan
-- ========================================================================

-- Week 4: Mark field deprecated (this migration)
-- Week 5-8: Update all dashboard queries to use vw_booking_charges
-- Week 9-12: Update admin panel to display saga charges
-- v2.0.0: Remove field entirely (future migration)

-- ========================================================================
-- Step 6: Rollback Plan (If Needed)
-- ========================================================================

-- If saga integration needs to be reverted, this migration can be rolled back:

-- ALTER TABLE bookings 
-- MODIFY COLUMN late_fee_amount DECIMAL(19, 2) NULL 
-- COMMENT 'Late fee calculated for late returns. Charged per hour.';

-- DROP VIEW IF EXISTS vw_booking_charges;

-- Then redeploy old CheckOutService code that writes to this field.

-- ========================================================================
-- Step 7: Future Removal (v2.0.0)
-- ========================================================================

-- After 6 months of successful saga operation, this field can be removed:

-- -- Migration: V50__remove_deprecated_late_fee_field.sql
-- 
-- -- Verify no recent writes to legacy field
-- SELECT COUNT(*) FROM bookings 
-- WHERE late_fee_amount IS NOT NULL 
--   AND checkout_completed_at >= NOW() - INTERVAL 6 MONTH;
-- -- Should return 0
-- 
-- -- Backup data before removal
-- CREATE TABLE bookings_late_fee_backup AS
-- SELECT id, late_fee_amount, checkout_completed_at
-- FROM bookings
-- WHERE late_fee_amount IS NOT NULL;
-- 
-- -- Remove column
-- ALTER TABLE bookings DROP COLUMN late_fee_amount;
-- 
-- -- Verify
-- DESCRIBE bookings;

-- ========================================================================
-- Monitoring Queries for Operations
-- ========================================================================

-- Query 1: Adoption rate (percentage using saga vs legacy)
SELECT 
    charge_source,
    COUNT(*) AS count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS percentage
FROM vw_booking_charges
WHERE checkout_completed_at >= CURDATE() - INTERVAL 7 DAY
GROUP BY charge_source;

-- Expected after successful deployment:
-- charge_source | count | percentage
-- --------------+-------+-----------
-- SAGA          |  450  |  99.78%
-- LEGACY        |    1  |   0.22%   (old booking from before deployment)
-- NONE          |    0  |   0.00%

-- Query 2: Saga success rate
SELECT 
    DATE(b.checkout_completed_at) AS date,
    COUNT(*) AS total_checkouts,
    SUM(CASE WHEN css.status = 'COMPLETED' THEN 1 ELSE 0 END) AS saga_completed,
    SUM(CASE WHEN css.status = 'FAILED' THEN 1 ELSE 0 END) AS saga_failed,
    ROUND(SUM(CASE WHEN css.status = 'COMPLETED' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS success_rate
FROM bookings b
LEFT JOIN checkout_saga_state css ON css.booking_id = b.id
WHERE b.status = 'COMPLETED'
  AND b.checkout_completed_at >= CURDATE() - INTERVAL 7 DAY
GROUP BY DATE(b.checkout_completed_at)
ORDER BY date DESC;

-- Target success rate: > 95%

-- Query 3: Data consistency audit
-- Find bookings where BOTH legacy and saga fields are populated (should be ZERO)
SELECT 
    b.id,
    b.checkout_completed_at,
    b.late_fee_amount AS legacy,
    css.late_fee AS saga,
    ABS(b.late_fee_amount - css.late_fee) AS discrepancy
FROM bookings b
INNER JOIN checkout_saga_state css ON css.booking_id = b.id
WHERE b.status = 'COMPLETED'
  AND b.late_fee_amount IS NOT NULL
  AND css.late_fee IS NOT NULL
  AND b.checkout_completed_at >= '2025-12-16'  -- After saga integration
  AND ABS(b.late_fee_amount - css.late_fee) > 0.01  -- Allow for rounding
ORDER BY b.checkout_completed_at DESC;

-- If this returns rows: DATA INCONSISTENCY - investigate immediately

-- ========================================================================
-- Performance Impact Assessment
-- ========================================================================

-- Impact of adding view: NEGLIGIBLE
-- - View is not materialized, computed on query
-- - Queries using view will have LEFT JOIN overhead (minimal)
-- - Indexes already exist on bookings.id and checkout_saga_state.booking_id

-- Verify view performance:
EXPLAIN SELECT * FROM vw_booking_charges WHERE booking_id = 12345;

-- Expected: Uses primary key index, < 1ms query time

-- ========================================================================
-- Compliance & Audit
-- ========================================================================

-- For financial audits, both data sources remain available:
-- - Saga charges: checkout_saga_state.late_fee (current system)
-- - Legacy charges: bookings.late_fee_amount (historical, pre-2025-12-16)
-- - View combines both for complete audit trail

-- Audit query: All late fees charged in 2025
SELECT 
    booking_id,
    checkout_completed_at,
    effective_late_fee,
    charge_source,
    CASE 
        WHEN charge_source = 'SAGA' THEN 'Current system (saga-based)'
        WHEN charge_source = 'LEGACY' THEN 'Legacy system (pre-saga)'
        ELSE 'No fee charged'
    END AS calculation_method
FROM vw_booking_charges
WHERE YEAR(checkout_completed_at) = 2025
  AND effective_late_fee > 0
ORDER BY checkout_completed_at;

-- ========================================================================
-- Documentation References
-- ========================================================================

-- Related Files:
-- - CheckOutService.java (line 502-507): Fee calculation removed
-- - CheckoutSagaOrchestrator.java (line 256-276): Fee calculation implemented
-- - V15__checkout_workflow.sql (line 62): Original column creation
-- - V21__checkout_saga_state.sql (line 53): Saga table late_fee column
-- - SAGA_INTEGRATION_IMPLEMENTATION_COMPLETE.md: Architecture documentation

-- ========================================================================
-- Sign-off
-- ========================================================================

-- Migration Author: Principal Software Architect
-- Reviewed By: Database Administrator, Engineering Lead
-- Approved By: CTO
-- Deployment Date: 2025-12-23 (Week 4 after saga integration)
-- Rollback Tested: Yes (rollback script validated in staging)

-- ========================================================================
-- End of Migration
-- ========================================================================
