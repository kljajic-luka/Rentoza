package org.example.rentoza.config.timezone;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Central timezone configuration for the Rentoza platform.
 * 
 * <h2>Business Context</h2>
 * <p>Rentoza operates exclusively in the Serbian market (Serbia, Croatia, Bosnia-Herzegovina),
 * all of which use the Europe/Belgrade timezone (CET/CEST with identical DST rules).
 * 
 * <h2>Why This Matters</h2>
 * <ul>
 *   <li><b>Cloud Servers:</b> AWS/Azure/GCP servers default to UTC. Without explicit timezone,
 *       "4 AM cleanup" runs at 5 AM (winter) or 6 AM (summer) Serbian time.</li>
 *   <li><b>DST Transitions:</b> Europe/Belgrade switches between CET (UTC+1) and CEST (UTC+2)
 *       on the last Sunday of March/October. Schedulers without explicit zones drift by 1 hour.</li>
 *   <li><b>Booking Logic:</b> Check-in/check-out windows must align with user expectations
 *       in Serbian local time, not server time.</li>
 * </ul>
 * 
 * <h2>Usage Guidelines</h2>
 * <pre>
 * // ✅ CORRECT: Use Serbia timezone explicitly
 * LocalDateTime now = SerbiaTimeZone.now();
 * ZonedDateTime zonedNow = SerbiaTimeZone.zonedNow();
 * 
 * // ❌ WRONG: Uses server timezone (UTC on cloud)
 * LocalDateTime now = LocalDateTime.now();
 * </pre>
 * 
 * <h2>For @Scheduled Methods</h2>
 * <pre>
 * // Always specify zone explicitly:
 * &#64;Scheduled(cron = "0 0 4 * * *", zone = SerbiaTimeZone.ZONE_ID_STRING)
 * public void dailyCleanup() { ... }
 * </pre>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.0 - Timezone Standardization
 */
public final class SerbiaTimeZone {

    /**
     * The canonical timezone ID for Serbia/Croatia/Bosnia.
     * <p>All three countries use identical DST rules.
     */
    public static final String ZONE_ID_STRING = "Europe/Belgrade";

    /**
     * The ZoneId instance for Serbian timezone.
     * <p>Thread-safe and immutable. Use this for all timezone operations.
     */
    public static final ZoneId ZONE_ID = ZoneId.of(ZONE_ID_STRING);

    /**
     * UTC offset during Central European Time (winter).
     * <p>Valid from last Sunday of October to last Sunday of March.
     */
    public static final ZoneOffset OFFSET_CET = ZoneOffset.ofHours(1);

    /**
     * UTC offset during Central European Summer Time (summer).
     * <p>Valid from last Sunday of March to last Sunday of October.
     */
    public static final ZoneOffset OFFSET_CEST = ZoneOffset.ofHours(2);

    // ==================== CURRENT TIME UTILITIES ====================

    /**
     * Get current time in Serbian timezone as LocalDateTime.
     * <p>Use this instead of {@code LocalDateTime.now()}.
     * 
     * @return Current Serbian local time
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE_ID);
    }

    /**
     * Get current date in Serbian timezone.
     * <p>Use this instead of {@code LocalDate.now()}.
     * 
     * @return Current Serbian date
     */
    public static LocalDate today() {
        return LocalDate.now(ZONE_ID);
    }

    /**
     * Get current time in Serbian timezone as ZonedDateTime.
     * <p>Includes full timezone information for DST-aware calculations.
     * 
     * @return Current Serbian zoned time
     */
    public static ZonedDateTime zonedNow() {
        return ZonedDateTime.now(ZONE_ID);
    }

    /**
     * Get current instant (UTC timestamp).
     * <p>Use for database storage when Instant columns are preferred.
     * 
     * @return Current instant (UTC)
     */
    public static Instant instant() {
        return Instant.now();
    }

    // ==================== CONVERSION UTILITIES ====================

    /**
     * Convert LocalDateTime (assumed Serbian timezone) to Instant for database storage.
     * 
     * @param localDateTime Serbian local time
     * @return UTC instant
     */
    public static Instant toInstant(LocalDateTime localDateTime) {
        if (localDateTime == null) return null;
        return localDateTime.atZone(ZONE_ID).toInstant();
    }

    /**
     * Convert Instant (from database) to Serbian LocalDateTime for display.
     * 
     * @param instant UTC instant from database
     * @return Serbian local time
     */
    public static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, ZONE_ID);
    }

    /**
     * Convert LocalDate to start-of-day Instant in Serbian timezone.
     * <p>Useful for date range queries where dates are in Serbian local time.
     * 
     * @param date Serbian local date
     * @return UTC instant at midnight Serbian time
     */
    public static Instant toStartOfDayInstant(LocalDate date) {
        if (date == null) return null;
        return date.atStartOfDay(ZONE_ID).toInstant();
    }

    /**
     * Convert LocalDate to end-of-day Instant in Serbian timezone.
     * <p>Actually returns start of next day for exclusive range queries.
     * 
     * @param date Serbian local date
     * @return UTC instant at midnight (next day) Serbian time
     */
    public static Instant toEndOfDayInstant(LocalDate date) {
        if (date == null) return null;
        return date.plusDays(1).atStartOfDay(ZONE_ID).toInstant();
    }

    // ==================== DST AWARENESS ====================

    /**
     * Check if Serbia is currently in Daylight Saving Time (CEST).
     * 
     * @return true if CEST (summer), false if CET (winter)
     */
    public static boolean isDaylightSavingTime() {
        return ZONE_ID.getRules().isDaylightSavings(Instant.now());
    }

    /**
     * Get current UTC offset for Serbia.
     * 
     * @return +01:00 (CET) or +02:00 (CEST)
     */
    public static ZoneOffset currentOffset() {
        return ZONE_ID.getRules().getOffset(Instant.now());
    }

    /**
     * Get the next DST transition date.
     * <p>Useful for scheduling considerations around clock changes.
     * 
     * @return Next DST transition (spring forward or fall back)
     */
    public static ZonedDateTime nextDstTransition() {
        var transition = ZONE_ID.getRules().nextTransition(Instant.now());
        return transition != null ? transition.getInstant().atZone(ZONE_ID) : null;
    }

    // ==================== FORMATTING ====================

    /**
     * Standard Serbian date format: "31.01.2026"
     */
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Standard Serbian datetime format: "31.01.2026 14:30"
     */
    public static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * ISO format with Serbian timezone: "2026-01-31T14:30:00+01:00"
     */
    public static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * Format current Serbian time in standard format.
     * 
     * @return Formatted datetime string
     */
    public static String formatNow() {
        return now().format(DATETIME_FORMAT);
    }

    // ==================== PRIVATE CONSTRUCTOR ====================

    private SerbiaTimeZone() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }
}
