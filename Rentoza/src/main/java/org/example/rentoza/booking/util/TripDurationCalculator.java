package org.example.rentoza.booking.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;

/**
 * Enterprise-grade utility for DST-safe trip duration calculations.
 * 
 * <p>This calculator handles timezone complexities that can cause
 * subtle bugs during Daylight Saving Time transitions:
 * <ul>
 *   <li><b>Spring Forward:</b> March ~30 - clocks skip 02:00-03:00 (trip is 1h shorter)</li>
 *   <li><b>Fall Back:</b> October ~26 - 02:00-03:00 occurs twice (ambiguous times)</li>
 * </ul>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Calculate actual duration (accounts for DST)
 * Duration actual = TripDurationCalculator.calculateActualDuration(start, end);
 * 
 * // Check if trip spans DST transition
 * boolean spansDst = TripDurationCalculator.spansDstTransition(start, end);
 * 
 * // Validate time exists (not in DST gap)
 * boolean valid = TripDurationCalculator.isValidLocalTime(datetime);
 * }</pre>
 * 
 * <p><b>Phase 3 - Enterprise Hardening:</b> Part of Time Window Logic Improvement Plan
 * 
 * @see <a href="https://www.timeanddate.com/time/change/serbia/belgrade">Serbia DST Schedule</a>
 * @since 2026-01 (Phase 3)
 */
public final class TripDurationCalculator {

    /**
     * Serbia timezone - all Rentoza bookings use this timezone.
     */
    public static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    /**
     * Private constructor - utility class.
     */
    private TripDurationCalculator() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    // ========================================================================
    // DURATION CALCULATION
    // ========================================================================

    /**
     * Calculate the actual trip duration accounting for DST transitions.
     * 
     * <p>Unlike simple {@code ChronoUnit.HOURS.between()}, this method
     * correctly handles DST transitions:
     * <ul>
     *   <li>Spring forward: 24 clock hours = 23 actual hours</li>
     *   <li>Fall back: 24 clock hours = 25 actual hours</li>
     * </ul>
     * 
     * @param start Trip start time (Serbia local time)
     * @param end Trip end time (Serbia local time)
     * @return Actual duration considering DST transitions
     * @throws IllegalArgumentException if start or end is null
     */
    public static Duration calculateActualDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Start and end times must not be null");
        }

        ZonedDateTime startZoned = start.atZone(SERBIA_ZONE);
        ZonedDateTime endZoned = end.atZone(SERBIA_ZONE);

        return Duration.between(startZoned, endZoned);
    }

    /**
     * Calculate trip duration in hours (rounded down).
     * 
     * @param start Trip start time
     * @param end Trip end time
     * @return Actual hours (may differ from clock hours during DST)
     */
    public static long calculateActualHours(LocalDateTime start, LocalDateTime end) {
        return calculateActualDuration(start, end).toHours();
    }

    /**
     * Calculate trip duration in days (rounded down).
     * 
     * @param start Trip start time
     * @param end Trip end time
     * @return Actual days
     */
    public static long calculateActualDays(LocalDateTime start, LocalDateTime end) {
        return calculateActualDuration(start, end).toDays();
    }

    // ========================================================================
    // DST TRANSITION DETECTION
    // ========================================================================

    /**
     * Check if a time period spans a DST transition.
     * 
     * <p>Important for:
     * <ul>
     *   <li>Displaying warnings to users</li>
     *   <li>Adjusting billing calculations</li>
     *   <li>Scheduler timing adjustments</li>
     * </ul>
     * 
     * @param start Period start (Serbia local time)
     * @param end Period end (Serbia local time)
     * @return true if a DST transition occurs within the period
     */
    public static boolean spansDstTransition(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return false;
        }

        ZoneRules rules = SERBIA_ZONE.getRules();
        ZonedDateTime startZoned = start.atZone(SERBIA_ZONE);
        ZonedDateTime endZoned = end.atZone(SERBIA_ZONE);

        ZoneOffsetTransition nextTransition = rules.nextTransition(startZoned.toInstant());

        if (nextTransition == null) {
            return false;
        }

        Instant transitionInstant = nextTransition.getInstant();
        return transitionInstant.isAfter(startZoned.toInstant())
                && transitionInstant.isBefore(endZoned.toInstant());
    }

    /**
     * Get information about the next DST transition after a given time.
     * 
     * @param from Starting point
     * @return DST transition info, or null if no upcoming transition
     */
    public static DstTransitionInfo getNextDstTransition(LocalDateTime from) {
        if (from == null) {
            return null;
        }

        ZoneRules rules = SERBIA_ZONE.getRules();
        ZonedDateTime fromZoned = from.atZone(SERBIA_ZONE);
        ZoneOffsetTransition transition = rules.nextTransition(fromZoned.toInstant());

        if (transition == null) {
            return null;
        }

        return new DstTransitionInfo(
                transition.getDateTimeBefore(),
                transition.getDateTimeAfter(),
                transition.isGap() // true = spring forward, false = fall back
        );
    }

    // ========================================================================
    // TIME VALIDATION
    // ========================================================================

    /**
     * Check if a local datetime is valid (not in a DST gap).
     * 
     * <p>During spring forward, certain times don't exist:
     * <ul>
     *   <li>2026-03-29 02:00 to 02:59 in Serbia doesn't exist</li>
     *   <li>Clocks jump directly from 01:59:59 to 03:00:00</li>
     * </ul>
     * 
     * @param dateTime The datetime to validate
     * @return true if the time exists (not in a DST gap)
     */
    public static boolean isValidLocalTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return false;
        }

        ZoneRules rules = SERBIA_ZONE.getRules();
        return rules.isValidOffset(dateTime, rules.getOffset(dateTime.atZone(SERBIA_ZONE).toInstant()));
    }

    /**
     * Check if a local datetime falls in an ambiguous period (DST overlap).
     * 
     * <p>During fall back, certain times occur twice:
     * <ul>
     *   <li>2026-10-25 02:00 to 02:59 in Serbia occurs twice</li>
     *   <li>First at +02:00 (CEST), then at +01:00 (CET)</li>
     * </ul>
     * 
     * @param dateTime The datetime to check
     * @return true if the time is ambiguous (occurs twice)
     */
    public static boolean isAmbiguousTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return false;
        }

        ZoneRules rules = SERBIA_ZONE.getRules();
        return rules.getValidOffsets(dateTime).size() > 1;
    }

    /**
     * Resolve an ambiguous time to the earlier occurrence (summer time / CEST).
     * 
     * @param dateTime The potentially ambiguous datetime
     * @return ZonedDateTime with the earlier offset
     */
    public static ZonedDateTime resolveToEarlierOffset(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("DateTime must not be null");
        }

        ZoneRules rules = SERBIA_ZONE.getRules();
        var offsets = rules.getValidOffsets(dateTime);

        if (offsets.size() > 1) {
            // Multiple offsets = ambiguous time, choose earlier (summer time)
            return dateTime.atOffset(offsets.get(0)).atZoneSameInstant(SERBIA_ZONE);
        }

        return dateTime.atZone(SERBIA_ZONE);
    }

    /**
     * Resolve an ambiguous time to the later occurrence (winter time / CET).
     * 
     * @param dateTime The potentially ambiguous datetime
     * @return ZonedDateTime with the later offset
     */
    public static ZonedDateTime resolveToLaterOffset(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("DateTime must not be null");
        }

        ZoneRules rules = SERBIA_ZONE.getRules();
        var offsets = rules.getValidOffsets(dateTime);

        if (offsets.size() > 1) {
            // Multiple offsets = ambiguous time, choose later (winter time)
            return dateTime.atOffset(offsets.get(1)).atZoneSameInstant(SERBIA_ZONE);
        }

        return dateTime.atZone(SERBIA_ZONE);
    }

    // ========================================================================
    // SAFE CONVERSIONS
    // ========================================================================

    /**
     * Convert a LocalDateTime to Instant using Serbia timezone.
     * 
     * <p>For ambiguous times (fall back), uses the earlier offset by default.
     * 
     * @param dateTime Local datetime in Serbia
     * @return UTC instant
     */
    public static Instant toInstant(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return resolveToEarlierOffset(dateTime).toInstant();
    }

    /**
     * Convert an Instant to LocalDateTime in Serbia timezone.
     * 
     * @param instant UTC instant
     * @return Local datetime in Serbia
     */
    public static LocalDateTime toSerbiaLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atZone(SERBIA_ZONE).toLocalDateTime();
    }

    /**
     * Get the current time in Serbia timezone.
     * 
     * @return Current LocalDateTime in Serbia
     */
    public static LocalDateTime nowInSerbia() {
        return LocalDateTime.now(SERBIA_ZONE);
    }

    /**
     * Get the current instant.
     * 
     * @return Current UTC instant
     */
    public static Instant nowInstant() {
        return Instant.now();
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    /**
     * Information about a DST transition.
     */
    public record DstTransitionInfo(
            LocalDateTime before,
            LocalDateTime after,
            boolean isSpringForward
    ) {
        /**
         * Get the duration of the transition (typically +1h or -1h).
         */
        public Duration getAdjustment() {
            return Duration.between(before, after);
        }

        /**
         * Get human-readable description.
         */
        public String getDescription() {
            if (isSpringForward) {
                return String.format("Prolećno pomeranje: %s → %s (sat unapred)",
                        before.toLocalTime(), after.toLocalTime());
            } else {
                return String.format("Jesenje pomeranje: %s → %s (sat unazad)",
                        before.toLocalTime(), after.toLocalTime());
            }
        }
    }
}
