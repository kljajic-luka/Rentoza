package org.example.rentoza.car;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embeddable settings for car booking configuration (Turo-style host control).
 * 
 * <p>Allows car owners to customize booking rules per vehicle:
 * <ul>
 *   <li>Minimum/maximum trip duration</li>
 *   <li>Advance notice requirement</li>
 *   <li>Buffer time between trips</li>
 *   <li>Instant booking toggle</li>
 * </ul>
 * 
 * <p><b>Phase 2 - Validation Alignment:</b>
 * These settings override global defaults and are validated on both
 * frontend (UX) and backend (security).
 * 
 * @see Car#bookingSettings
 * @since 2026-01 (Phase 2)
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarBookingSettings {

    // ========== GLOBAL DEFAULTS (Used when host doesn't customize) ==========
    
    /** System-wide minimum trip duration (hours) */
    public static final int DEFAULT_MIN_TRIP_HOURS = 24;
    
    /** System-wide maximum trip duration (days) */
    public static final int DEFAULT_MAX_TRIP_DAYS = 30;
    
    /** System-wide advance notice (hours) */
    public static final int DEFAULT_ADVANCE_NOTICE_HOURS = 1;
    
    /** System-wide buffer between trips (hours) */
    public static final int DEFAULT_PREP_BUFFER_HOURS = 3;
    
    // ========== HOST-CONFIGURABLE SETTINGS ==========

    /**
     * Minimum trip duration in hours.
     * 
     * <p>Allows hosts to require longer rentals for premium vehicles
     * or reduce minimum for high-turnover economy cars.
     * 
     * <p><b>Range:</b> 1-168 hours (1 hour to 7 days)
     * <p><b>Default:</b> 24 hours (1 day)
     * <p><b>Validation:</b> Backend enforces this during booking creation
     */
    @Column(name = "min_trip_hours")
    @Min(value = 1, message = "Minimalno trajanje mora biti bar 1 sat")
    @Max(value = 168, message = "Minimalno trajanje ne može biti više od 168 sati (7 dana)")
    private Integer minTripHours = DEFAULT_MIN_TRIP_HOURS;

    /**
     * Maximum trip duration in days.
     * 
     * <p>Limits how long a single booking can last. Protects hosts
     * from extended liability and ensures regular maintenance.
     * 
     * <p><b>Range:</b> 1-30 days
     * <p><b>Default:</b> 30 days
     * <p><b>Serbian Regulation:</b> Extended rentals (30+ days) may require
     *     additional documentation per vehicle leasing laws.
     */
    @Column(name = "max_trip_days")
    @Min(value = 1, message = "Maksimalno trajanje mora biti bar 1 dan")
    @Max(value = 30, message = "Maksimalno trajanje ne može biti više od 30 dana")
    private Integer maxTripDays = DEFAULT_MAX_TRIP_DAYS;

    /**
     * Required advance notice in hours before trip start.
     * 
     * <p>Gives hosts time to prepare the vehicle and confirm availability.
     * Higher values provide more preparation time; lower values allow
     * more spontaneous bookings.
     * 
     * <p><b>Range:</b> 0-72 hours (0 = instant, up to 3 days notice)
     * <p><b>Default:</b> 1 hour
     * <p><b>Instant Booking:</b> If instantBookEnabled=true, this determines
     *     how close to trip start guests can book.
     */
    @Column(name = "advance_notice_hours")
    @Min(value = 0, message = "Vreme najave ne može biti negativno")
    @Max(value = 72, message = "Vreme najave ne može biti više od 72 sata")
    private Integer advanceNoticeHours = DEFAULT_ADVANCE_NOTICE_HOURS;

    /**
     * Buffer time between trips in hours.
     * 
     * <p>Automatic gap enforced between consecutive bookings.
     * Provides time for:
     * <ul>
     *   <li>Vehicle cleaning</li>
     *   <li>Damage inspection</li>
     *   <li>Refueling</li>
     *   <li>Minor maintenance</li>
     * </ul>
     * 
     * <p><b>Range:</b> 0-24 hours
     * <p><b>Default:</b> 3 hours
     * <p><b>Calendar Impact:</b> Blocks availability for buffer period
     *     after each booking's end time.
     */
    @Column(name = "prep_buffer_hours")
    @Min(value = 0, message = "Vreme pripreme ne može biti negativno")
    @Max(value = 24, message = "Vreme pripreme ne može biti više od 24 sata")
    private Integer prepBufferHours = DEFAULT_PREP_BUFFER_HOURS;

    /**
     * Whether instant booking is enabled.
     * 
     * <p>When true, guests can book immediately without host approval.
     * When false, host must manually approve each booking request.
     * 
     * <p><b>Default:</b> false (requires host approval)
     * <p><b>Impact:</b>
     * <ul>
     *   <li>TRUE: BookingStatus goes directly to ACTIVE</li>
     *   <li>FALSE: BookingStatus starts as PENDING_APPROVAL</li>
     * </ul>
     */
    @Column(name = "instant_book_enabled")
    private Boolean instantBookEnabled = false;

    // ========== HELPER METHODS ==========

    /**
     * Get effective minimum trip hours, using default if not set.
     * @return Minimum trip hours (never null)
     */
    public int getEffectiveMinTripHours() {
        return minTripHours != null ? minTripHours : DEFAULT_MIN_TRIP_HOURS;
    }

    /**
     * Get effective maximum trip days, using default if not set.
     * @return Maximum trip days (never null)
     */
    public int getEffectiveMaxTripDays() {
        return maxTripDays != null ? maxTripDays : DEFAULT_MAX_TRIP_DAYS;
    }

    /**
     * Get effective advance notice hours, using default if not set.
     * @return Advance notice hours (never null)
     */
    public int getEffectiveAdvanceNoticeHours() {
        return advanceNoticeHours != null ? advanceNoticeHours : DEFAULT_ADVANCE_NOTICE_HOURS;
    }

    /**
     * Get effective prep buffer hours, using default if not set.
     * @return Prep buffer hours (never null)
     */
    public int getEffectivePrepBufferHours() {
        return prepBufferHours != null ? prepBufferHours : DEFAULT_PREP_BUFFER_HOURS;
    }

    /**
     * Check if instant booking is enabled.
     * @return true if instant booking is enabled, false otherwise
     */
    public boolean isInstantBookEnabled() {
        return instantBookEnabled != null && instantBookEnabled;
    }

    /**
     * Create settings with all defaults.
     * @return New CarBookingSettings with default values
     */
    public static CarBookingSettings defaults() {
        return new CarBookingSettings();
    }

    /**
     * Builder-style method to set minimum trip hours.
     */
    public CarBookingSettings withMinTripHours(int hours) {
        this.minTripHours = hours;
        return this;
    }

    /**
     * Builder-style method to set advance notice hours.
     */
    public CarBookingSettings withAdvanceNoticeHours(int hours) {
        this.advanceNoticeHours = hours;
        return this;
    }

    /**
     * Builder-style method to enable instant booking.
     */
    public CarBookingSettings withInstantBookEnabled(boolean enabled) {
        this.instantBookEnabled = enabled;
        return this;
    }
}
