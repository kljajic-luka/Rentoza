package org.example.rentoza.booking.checkin;

/**
 * Photo types for check-in and checkout documentation.
 * 
 * <p>Each photo type maps to a specific vehicle area or reading.
 * The host must upload at least 8 photos (configurable via {@code check_in_config}).
 * 
 * <h2>Required Host Photos (8 minimum)</h2>
 * <ul>
 *   <li>4 exterior angles (front, rear, left, right)</li>
 *   <li>2 interior shots (dashboard, rear seats)</li>
 *   <li>1 odometer reading</li>
 *   <li>1 fuel gauge reading</li>
 * </ul>
 * 
 * <h2>EXIF Validation</h2>
 * <p>All photos are validated for:
 * <ul>
 *   <li>Timestamp freshness (max 30 minutes old)</li>
 *   <li>GPS proximity to car/pickup location (within 1km)</li>
 *   <li>Device metadata consistency</li>
 * </ul>
 *
 * @see CheckInPhoto
 * @see ExifValidationStatus
 */
public enum CheckInPhotoType {
    
    // ========== HOST REQUIRED PHOTOS (8 minimum) ==========
    
    /** Front exterior view of vehicle */
    HOST_EXTERIOR_FRONT,
    
    /** Rear exterior view of vehicle */
    HOST_EXTERIOR_REAR,
    
    /** Left side exterior view of vehicle */
    HOST_EXTERIOR_LEFT,
    
    /** Right side exterior view of vehicle */
    HOST_EXTERIOR_RIGHT,
    
    /** Interior dashboard view (steering wheel, center console) */
    HOST_INTERIOR_DASHBOARD,
    
    /** Interior rear seat view */
    HOST_INTERIOR_REAR,
    
    /** Close-up of odometer reading */
    HOST_ODOMETER,
    
    /** Close-up of fuel gauge reading */
    HOST_FUEL_GAUGE,
    
    // ========== HOST OPTIONAL PHOTOS ==========
    
    /** Pre-existing damage documentation (dents, scratches, etc.) */
    HOST_DAMAGE_PREEXISTING,
    
    /** Custom photo (trunk, engine bay, special features) */
    HOST_CUSTOM,
    
    // ========== GUEST PHOTOS ==========
    
    /** Guest-noted damage not in host photos */
    GUEST_DAMAGE_NOTED,
    
    /** Guest-marked hotspot on vehicle diagram */
    GUEST_HOTSPOT,
    
    // ========== GUEST CHECKOUT PHOTOS ==========
    
    /** Checkout: Front exterior */
    CHECKOUT_EXTERIOR_FRONT,
    
    /** Checkout: Rear exterior */
    CHECKOUT_EXTERIOR_REAR,
    
    /** Checkout: Left side exterior */
    CHECKOUT_EXTERIOR_LEFT,
    
    /** Checkout: Right side exterior */
    CHECKOUT_EXTERIOR_RIGHT,
    
    /** Checkout: Interior dashboard view */
    CHECKOUT_INTERIOR_DASHBOARD,
    
    /** Checkout: Interior rear seat view */
    CHECKOUT_INTERIOR_REAR,
    
    /** Checkout: Odometer reading */
    CHECKOUT_ODOMETER,
    
    /** Checkout: Fuel gauge reading */
    CHECKOUT_FUEL_GAUGE,
    
    /** Checkout: New damage discovered by guest */
    CHECKOUT_DAMAGE_NEW,
    
    /** Checkout: Custom photo (additional evidence) */
    CHECKOUT_CUSTOM,
    
    // ========== HOST CHECKOUT CONFIRMATION PHOTOS ==========
    
    /** Host checkout confirmation photo (general) */
    HOST_CHECKOUT_CONFIRMATION,
    
    /** Host checkout: damage evidence photo */
    HOST_CHECKOUT_DAMAGE_EVIDENCE;
    
    /**
     * Check if this photo type is required for host check-in.
     * @return true if this is one of the 8 required host photos
     */
    public boolean isRequiredForHost() {
        return switch (this) {
            case HOST_EXTERIOR_FRONT,
                 HOST_EXTERIOR_REAR,
                 HOST_EXTERIOR_LEFT,
                 HOST_EXTERIOR_RIGHT,
                 HOST_INTERIOR_DASHBOARD,
                 HOST_INTERIOR_REAR,
                 HOST_ODOMETER,
                 HOST_FUEL_GAUGE -> true;
            default -> false;
        };
    }
    
    /**
     * Check if this photo type is a host upload (vs guest or checkout).
     * @return true if uploaded by host during check-in
     */
    public boolean isHostPhoto() {
        return name().startsWith("HOST_");
    }
    
    /**
     * Check if this photo type is a guest upload.
     * @return true if uploaded by guest during check-in
     */
    public boolean isGuestPhoto() {
        return name().startsWith("GUEST_");
    }
    
    /**
     * Check if this photo type is a checkout upload.
     * @return true if part of checkout process
     */
    public boolean isCheckoutPhoto() {
        return name().startsWith("CHECKOUT_") || name().startsWith("HOST_CHECKOUT_");
    }
    
    /**
     * Check if this photo type is required for guest checkout.
     * @return true if this is one of the 6 required checkout photos
     */
    public boolean isRequiredForCheckout() {
        return switch (this) {
            case CHECKOUT_EXTERIOR_FRONT,
                 CHECKOUT_EXTERIOR_REAR,
                 CHECKOUT_EXTERIOR_LEFT,
                 CHECKOUT_EXTERIOR_RIGHT,
                 CHECKOUT_ODOMETER,
                 CHECKOUT_FUEL_GAUGE -> true;
            default -> false;
        };
    }
    
    /**
     * Check if this photo type is a host checkout photo.
     * @return true if uploaded by host during checkout confirmation
     */
    public boolean isHostCheckoutPhoto() {
        return name().startsWith("HOST_CHECKOUT_");
    }
}
