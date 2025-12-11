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
    /** Car reactivated from suspension */
    CAR_REACTIVATED,
    
    // ==================== BOOKINGS ====================
    /** Booking force-cancelled by admin */
    BOOKING_CANCELLED,
    /** Booking extended by admin */
    BOOKING_EXTENDED,
    /** Refund forced by admin */
    BOOKING_FORCED_REFUND,
    
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
    ANALYTICS_EXPORTED
}
