package org.example.rentoza.booking;

public enum BookingStatus {
    PENDING_APPROVAL,  // Guest requested, awaiting host approval
    ACTIVE,            // Host approved, trip is active
    DECLINED,          // Host declined request
    CANCELLED,         // Booking cancelled (by renter or owner)
    COMPLETED,         // Trip finished
    EXPIRED            // Pending request expired (no host response)
}
