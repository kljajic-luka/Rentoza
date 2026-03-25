package org.example.rentoza.booking.checkout.saga;

import java.time.Instant;
import java.util.UUID;

/**
 * Checkout Saga step definitions.
 * 
 * <h2>Saga Pattern Implementation</h2>
 * <p>Defines the ordered steps for checkout workflow and their compensating actions.
 * 
 * <h2>Step Order</h2>
 * <ol>
 *   <li>VALIDATE_RETURN - Verify photos, odometer, fuel</li>
 *   <li>CALCULATE_CHARGES - Extra mileage, fuel difference, late fees</li>
 *   <li>CAPTURE_DEPOSIT - Charge damage/mileage from held deposit</li>
 *   <li>RELEASE_DEPOSIT - Return unused deposit portion</li>
 *   <li>COMPLETE_BOOKING - Update booking status to COMPLETED</li>
 * </ol>
 */
public enum CheckoutSagaStep {

    /**
     * Step 1: Validate return condition.
     * 
     * <p>Validates:
     * <ul>
     *   <li>All required checkout photos uploaded</li>
     *   <li>Odometer reading recorded</li>
     *   <li>Fuel level recorded</li>
     *   <li>No unresolved damage claims</li>
     * </ul>
     * 
     * <p>Compensation: None (validation has no side effects)
     */
    VALIDATE_RETURN(1, "Validacija povratka", false),

    /**
     * Step 2: Calculate additional charges.
     * 
     * <p>Calculates:
     * <ul>
     *   <li>Extra mileage charges</li>
     *   <li>Fuel difference charges</li>
     *   <li>Late return fees</li>
     *   <li>Cleaning fees if applicable</li>
     * </ul>
     * 
     * <p>Compensation: None (calculation has no side effects)
     */
    CALCULATE_CHARGES(2, "Izračunavanje dodatnih troškova", false),

    /**
     * Step 3: Capture deposit for damages/charges.
     * 
     * <p>Processes payment capture from held deposit.
     * 
     * <p>Compensation: REFUND_CAPTURED - Refund any captured amount
     */
    CAPTURE_DEPOSIT(3, "Naplata depozita", true),

    /**
     * Step 4: Release remaining deposit.
     * 
     * <p>Schedules the unused deposit for deferred release, or marks the deposit
     * as fully resolved when the entire deposit was captured.
     * 
     * <p>Compensation: CLEAR_RELEASE_MARKERS - undo deferred-release/resolution flags
     * if a later step fails
     */
    RELEASE_DEPOSIT(4, "Povrat depozita", true),

    /**
     * Step 5: Complete booking.
     * 
     * <p>Updates booking status and triggers notifications.
     * 
     * <p>Compensation: REVERT_STATUS - Revert to previous status
     */
    COMPLETE_BOOKING(5, "Završetak rezervacije", true);

    private final int order;
    private final String displayName;
    private final boolean hasCompensation;

    CheckoutSagaStep(int order, String displayName, boolean hasCompensation) {
        this.order = order;
        this.displayName = displayName;
        this.hasCompensation = hasCompensation;
    }

    public int getOrder() {
        return order;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean hasCompensation() {
        return hasCompensation;
    }

    /**
     * Get next step in sequence.
     * 
     * @return Next step or null if this is the last step
     */
    public CheckoutSagaStep next() {
        return switch (this) {
            case VALIDATE_RETURN -> CALCULATE_CHARGES;
            case CALCULATE_CHARGES -> CAPTURE_DEPOSIT;
            case CAPTURE_DEPOSIT -> RELEASE_DEPOSIT;
            case RELEASE_DEPOSIT -> COMPLETE_BOOKING;
            case COMPLETE_BOOKING -> null;
        };
    }

    /**
     * Get previous step for compensation.
     * 
     * @return Previous step or null if this is the first step
     */
    public CheckoutSagaStep previous() {
        return switch (this) {
            case VALIDATE_RETURN -> null;
            case CALCULATE_CHARGES -> VALIDATE_RETURN;
            case CAPTURE_DEPOSIT -> CALCULATE_CHARGES;
            case RELEASE_DEPOSIT -> CAPTURE_DEPOSIT;
            case COMPLETE_BOOKING -> RELEASE_DEPOSIT;
        };
    }
}
