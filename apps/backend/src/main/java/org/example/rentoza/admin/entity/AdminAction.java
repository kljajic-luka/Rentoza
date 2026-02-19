package org.example.rentoza.admin.entity;

/**
 * Administrative actions tracked in immutable audit log.
 * 
 * <p>Used for categorizing, searching, and filtering audit trail entries.
 * Each action represents a distinct admin operation that must be tracked
 * for compliance, forensics, and accountability.
 * 
 * @see AdminAuditLog
 */
public enum AdminAction {
    // ==================== USER MODERATION ====================
    /** User banned from platform */
    USER_BANNED,
    /** User ban removed */
    USER_UNBANNED,
    /** User account permanently deleted */
    USER_DELETED,
    /** User temporarily suspended */
    USER_SUSPENDED,
    /** User identity verified by admin */
    USER_VERIFIED_ID,
    /** User identity verification rejected by admin (with reason) */
    USER_VERIFICATION_REJECTED,
    
    // ==================== CAR APPROVALS ====================
    /** Car listing approved for marketplace */
    CAR_APPROVED,
    /** Car listing rejected with reason */
    CAR_REJECTED,
    /** Car flagged for manual review */
    CAR_FLAGGED,
    /** Car removed from platform */
    CAR_REMOVED,
    /** Car suspended for policy violation */
    CAR_SUSPENDED,
    /** Car suspended automatically due to expired documents */
    CAR_SUSPENDED_AUTO,
    /** Car reactivated from suspension */
    CAR_REACTIVATED,
    
    // ==================== DOCUMENT VERIFICATION ====================
    /** Document verified by admin as valid/current */
    DOCUMENT_VERIFIED,
    /** Document rejected by admin with reason */
    DOCUMENT_REJECTED,
    /** Document viewed/downloaded by admin */
    DOCUMENT_VIEWED,
    /** Owner identity verified by admin */
    OWNER_VERIFIED,
    /** Owner identity verification rejected by admin */
    OWNER_VERIFICATION_REJECTED,
    
    // ==================== BOOKINGS ====================
    /** Booking force-cancelled by admin */
    BOOKING_CANCELLED,
    /** Booking extended by admin */
    BOOKING_EXTENDED,
    /** Refund forced by admin */
    BOOKING_FORCED_REFUND,
    /** Booking force-completed by admin */
    BOOKING_FORCE_COMPLETED,
    
    // ==================== DISPUTES ====================
    /** Dispute resolved with outcome */
    DISPUTE_RESOLVED,
    /** Dispute escalated to senior admin */
    DISPUTE_ESCALATED,
    /** Dispute mediated between parties */
    DISPUTE_MEDIATED,
    /** Dispute resolution failed (payment/notification error) */
    DISPUTE_RESOLUTION_FAILED,
    
    // ==================== FINANCIAL ====================
    /** Payout processed to host */
    PAYOUT_PROCESSED,
    /** Failed payout retried */
    PAYOUT_RETRIED,
    /** Payout cancelled */
    PAYOUT_CANCELLED,
    /** Payout failed */
    PAYOUT_FAILED,
    /** Transaction refunded */
    TRANSACTION_REFUNDED,
    
    // ==================== SYSTEM ====================
    /** Platform configuration updated */
    CONFIG_UPDATED,
    /** Admin logged into admin panel */
    ADMIN_LOGIN,
    /** Admin permissions changed */
    PERMISSION_CHANGED,
    /** Analytics data exported */
    ANALYTICS_EXPORTED,
    /** Audit logs exported */
    AUDIT_EXPORTED
}
