package org.example.rentoza.booking.extension;

/**
 * Status of a trip extension request.
 */
public enum TripExtensionStatus {
    
    /**
     * Extension request submitted, awaiting host response.
     */
    PENDING,
    
    /**
     * Host approved the extension.
     */
    APPROVED,
    
    /**
     * Host declined the extension.
     */
    DECLINED,
    
    /**
     * Request expired without host response (24h timeout).
     */
    EXPIRED,
    
    /**
     * Request cancelled by guest.
     */
    CANCELLED;
    
    public boolean isOpen() {
        return this == PENDING;
    }
    
    public boolean isApproved() {
        return this == APPROVED;
    }
}


