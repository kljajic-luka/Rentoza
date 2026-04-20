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
    
    // ========== GUEST CHECK-IN PHOTOS (Dual-Party Verification) ==========
    // Guest captures same angles as host when arriving for pickup
    
    /** Guest check-in: Front exterior view */
    GUEST_EXTERIOR_FRONT,
    
    /** Guest check-in: Rear exterior view */
    GUEST_EXTERIOR_REAR,
    
    /** Guest check-in: Left side exterior view */
    GUEST_EXTERIOR_LEFT,
    
    /** Guest check-in: Right side exterior view */
    GUEST_EXTERIOR_RIGHT,
    
    /** Guest check-in: Interior dashboard view */
    GUEST_INTERIOR_DASHBOARD,
    
    /** Guest check-in: Interior rear seat view */
    GUEST_INTERIOR_REAR,
    
    /** Guest check-in: Odometer reading */
    GUEST_ODOMETER,
    
    /** Guest check-in: Fuel gauge reading */
    GUEST_FUEL_GAUGE,
    
    /** Guest check-in: Custom/additional photo */
    GUEST_CUSTOM,
    
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
    HOST_CHECKOUT_DAMAGE_EVIDENCE,
    
    // ========== HOST CHECKOUT PHOTOS (Dual-Party Verification) ==========
    // Host captures same angles as guest when vehicle is returned
    
    /** Host checkout: Front exterior view */
    HOST_CHECKOUT_EXTERIOR_FRONT,
    
    /** Host checkout: Rear exterior view */
    HOST_CHECKOUT_EXTERIOR_REAR,
    
    /** Host checkout: Left side exterior view */
    HOST_CHECKOUT_EXTERIOR_LEFT,
    
    /** Host checkout: Right side exterior view */
    HOST_CHECKOUT_EXTERIOR_RIGHT,
    
    /** Host checkout: Interior dashboard view */
    HOST_CHECKOUT_INTERIOR_DASHBOARD,
    
    /** Host checkout: Interior rear seat view */
    HOST_CHECKOUT_INTERIOR_REAR,
    
    /** Host checkout: Odometer reading */
    HOST_CHECKOUT_ODOMETER,
    
    /** Host checkout: Fuel gauge reading */
    HOST_CHECKOUT_FUEL_GAUGE,
    
    /** Host checkout: Custom/additional photo */
    HOST_CHECKOUT_CUSTOM;
    
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
     * Excludes HOST_CHECKOUT_* types which belong to the checkout phase.
     * @return true if uploaded by host during check-in
     */
    public boolean isHostPhoto() {
        return name().startsWith("HOST_") && !name().startsWith("HOST_CHECKOUT_");
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
     * @return true if this is one of the 8 required checkout photos
     */
    public boolean isRequiredForCheckout() {
        return switch (this) {
            case CHECKOUT_EXTERIOR_FRONT,
                 CHECKOUT_EXTERIOR_REAR,
                 CHECKOUT_EXTERIOR_LEFT,
                 CHECKOUT_EXTERIOR_RIGHT,
                 CHECKOUT_INTERIOR_DASHBOARD,
                 CHECKOUT_INTERIOR_REAR,
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
    
    /**
     * Check if this photo type is required for guest check-in (dual-party verification).
     * @return true if this is one of the 8 required guest check-in photos
     */
    public boolean isRequiredForGuestCheckIn() {
        return switch (this) {
            case GUEST_EXTERIOR_FRONT,
                 GUEST_EXTERIOR_REAR,
                 GUEST_EXTERIOR_LEFT,
                 GUEST_EXTERIOR_RIGHT,
                 GUEST_INTERIOR_DASHBOARD,
                 GUEST_INTERIOR_REAR,
                 GUEST_ODOMETER,
                 GUEST_FUEL_GAUGE -> true;
            default -> false;
        };
    }
    
    /**
     * Check if this photo type is required for host checkout (dual-party verification).
     * @return true if this is one of the 8 required host checkout photos
     */
    public boolean isRequiredForHostCheckout() {
        return switch (this) {
            case HOST_CHECKOUT_EXTERIOR_FRONT,
                 HOST_CHECKOUT_EXTERIOR_REAR,
                 HOST_CHECKOUT_EXTERIOR_LEFT,
                 HOST_CHECKOUT_EXTERIOR_RIGHT,
                 HOST_CHECKOUT_INTERIOR_DASHBOARD,
                 HOST_CHECKOUT_INTERIOR_REAR,
                 HOST_CHECKOUT_ODOMETER,
                 HOST_CHECKOUT_FUEL_GAUGE -> true;
            default -> false;
        };
    }
    
    /**
     * Check if this is a guest check-in photo type.
     * @return true if this is a GUEST_* type used during check-in
     */
    public boolean isGuestCheckInPhoto() {
        return name().startsWith("GUEST_") && !name().equals("GUEST_DAMAGE_NOTED") && !name().equals("GUEST_HOTSPOT");
    }
    
    /**
     * Get the corresponding host photo type for comparison.
     * Maps GUEST_* types to HOST_* types for discrepancy detection.
     * 
     * @return the corresponding host photo type, or null if no mapping exists
     */
    public CheckInPhotoType getCorrespondingHostType() {
        return switch (this) {
            case GUEST_EXTERIOR_FRONT -> HOST_EXTERIOR_FRONT;
            case GUEST_EXTERIOR_REAR -> HOST_EXTERIOR_REAR;
            case GUEST_EXTERIOR_LEFT -> HOST_EXTERIOR_LEFT;
            case GUEST_EXTERIOR_RIGHT -> HOST_EXTERIOR_RIGHT;
            case GUEST_INTERIOR_DASHBOARD -> HOST_INTERIOR_DASHBOARD;
            case GUEST_INTERIOR_REAR -> HOST_INTERIOR_REAR;
            case GUEST_ODOMETER -> HOST_ODOMETER;
            case GUEST_FUEL_GAUGE -> HOST_FUEL_GAUGE;
            default -> null;
        };
    }
    
    /**
     * Get the corresponding guest checkout photo type for host checkout comparison.
     * Maps HOST_CHECKOUT_* types to CHECKOUT_* types for discrepancy detection.
     * 
     * @return the corresponding guest checkout photo type, or null if no mapping exists
     */
    public CheckInPhotoType getCorrespondingGuestCheckoutType() {
        return switch (this) {
            case HOST_CHECKOUT_EXTERIOR_FRONT -> CHECKOUT_EXTERIOR_FRONT;
            case HOST_CHECKOUT_EXTERIOR_REAR -> CHECKOUT_EXTERIOR_REAR;
            case HOST_CHECKOUT_EXTERIOR_LEFT -> CHECKOUT_EXTERIOR_LEFT;
            case HOST_CHECKOUT_EXTERIOR_RIGHT -> CHECKOUT_EXTERIOR_RIGHT;
            case HOST_CHECKOUT_INTERIOR_DASHBOARD -> CHECKOUT_INTERIOR_DASHBOARD;
            case HOST_CHECKOUT_INTERIOR_REAR -> CHECKOUT_INTERIOR_REAR;
            case HOST_CHECKOUT_ODOMETER -> CHECKOUT_ODOMETER;
            case HOST_CHECKOUT_FUEL_GAUGE -> CHECKOUT_FUEL_GAUGE;
            default -> null;
        };
    }
    
    /**
     * Get all required photo types for guest check-in verification.
     * @return array of required guest check-in photo types
     */
    public static CheckInPhotoType[] getRequiredGuestCheckInTypes() {
        return new CheckInPhotoType[] {
            GUEST_EXTERIOR_FRONT,
            GUEST_EXTERIOR_REAR,
            GUEST_EXTERIOR_LEFT,
            GUEST_EXTERIOR_RIGHT,
            GUEST_INTERIOR_DASHBOARD,
            GUEST_INTERIOR_REAR,
            GUEST_ODOMETER,
            GUEST_FUEL_GAUGE
        };
    }
    
    /**
     * Get all required photo types for host checkout verification.
     * @return array of required host checkout photo types
     */
    public static CheckInPhotoType[] getRequiredHostCheckoutTypes() {
        return new CheckInPhotoType[] {
            HOST_CHECKOUT_EXTERIOR_FRONT,
            HOST_CHECKOUT_EXTERIOR_REAR,
            HOST_CHECKOUT_EXTERIOR_LEFT,
            HOST_CHECKOUT_EXTERIOR_RIGHT,
            HOST_CHECKOUT_INTERIOR_DASHBOARD,
            HOST_CHECKOUT_INTERIOR_REAR,
            HOST_CHECKOUT_ODOMETER,
            HOST_CHECKOUT_FUEL_GAUGE
        };
    }
}
