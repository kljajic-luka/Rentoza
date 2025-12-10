package org.example.rentoza.admin.entity;

/**
 * Types of resources that can be affected by admin actions.
 * 
 * <p>Used for categorizing audit log entries and enabling
 * resource-specific queries (e.g., find all actions on USER #5847).
 * 
 * @see AdminAuditLog
 * @see AdminAction
 */
public enum ResourceType {
    /** User account */
    USER,
    /** Car listing */
    CAR,
    /** Booking/rental transaction */
    BOOKING,
    /** Dispute/damage claim */
    DISPUTE,
    /** Financial transaction */
    TRANSACTION,
    /** Payment/payout */
    PAYMENT,
    /** Review/rating */
    REVIEW,
    /** Platform configuration */
    CONFIG
}
