-- V22: Add security deposit field to bookings table
-- Required for CheckoutSagaOrchestrator deposit release workflow

ALTER TABLE bookings 
ADD COLUMN security_deposit DECIMAL(19, 2) DEFAULT NULL
COMMENT 'Security deposit amount in RSD, set at check-in, released at checkout';

-- Index for finding bookings with unreleased deposits (cleanup/audit queries)
CREATE INDEX idx_bookings_security_deposit 
ON bookings(security_deposit) 
WHERE security_deposit IS NOT NULL;
