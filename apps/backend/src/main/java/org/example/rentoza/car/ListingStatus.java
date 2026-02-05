package org.example.rentoza.car;

/**
 * Car listing lifecycle status (replaces ApprovalStatus).
 * 
 * <p>Workflow: DRAFT → PENDING_APPROVAL → APPROVED (or REJECTED/SUSPENDED)
 */
public enum ListingStatus {
    /**
     * Owner still adding car details and documents.
     * Not visible to renters.
     */
    DRAFT,
    
    /**
     * Owner submitted car for admin approval.
     * Awaiting document verification.
     */
    PENDING_APPROVAL,
    
    /**
     * Admin verified all documents and approved.
     * Car visible in marketplace.
     */
    APPROVED,
    
    /**
     * Admin rejected due to failed checks.
     * Owner sees rejection reason and can resubmit.
     */
    REJECTED,
    
    /**
     * Temporarily suspended (expired documents, policy violation).
     * Not visible to renters.
     */
    SUSPENDED
}
