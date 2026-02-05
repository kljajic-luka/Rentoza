package org.example.rentoza.car;

public enum ApprovalStatus {
    PENDING,      // Awaiting admin approval
    APPROVED,     // Approved, visible in marketplace
    REJECTED,     // Rejected by admin
    SUSPENDED     // Suspended for policy violation
}
