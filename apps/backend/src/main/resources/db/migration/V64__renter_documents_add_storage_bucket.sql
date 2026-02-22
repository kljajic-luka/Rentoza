-- V64: Add storage_bucket column to renter_documents
--
-- Explicitly tracks which Supabase bucket holds each renter document,
-- preventing future bucket ambiguity in admin tooling.
-- All renter verification documents are in the 'renter-documents' bucket.

ALTER TABLE renter_documents
    ADD COLUMN IF NOT EXISTS storage_bucket VARCHAR(100) NOT NULL DEFAULT 'renter-documents';

-- Backfill: existing rows already use renter-documents bucket
UPDATE renter_documents
    SET storage_bucket = 'renter-documents'
    WHERE storage_bucket IS NULL OR storage_bucket = '';

COMMENT ON COLUMN renter_documents.storage_bucket IS
    'Supabase bucket containing this document. Always renter-documents for renter verification. '
    'Stored explicitly to prevent bucket routing bugs in admin tooling.';
