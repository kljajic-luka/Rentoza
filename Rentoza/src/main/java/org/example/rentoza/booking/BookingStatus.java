package org.example.rentoza.booking;

public enum BookingStatus {
    PENDING_APPROVAL,  // Guest requested, awaiting host approval
    ACTIVE,            // Host approved, trip is active
    DECLINED,          // Host declined request
    CANCELLED,         // Booking cancelled (by renter or owner)
    COMPLETED,         // Trip finished
    EXPIRED,           // Pending request expired (legacy - for backwards compatibility)
    EXPIRED_SYSTEM     // System auto-expired due to host inactivity (48h or trip-start buffer)
}
