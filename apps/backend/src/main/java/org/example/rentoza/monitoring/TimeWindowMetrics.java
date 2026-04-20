package org.example.rentoza.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.util.TripDurationCalculator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-related metrics for monitoring booking system health.
 * 
 * <p>Provides enterprise-grade observability for:
 * <ul>
 *   <li>DST transition bookings</li>
 *   <li>Extension conflicts</li>
 *   <li>Scheduler clock drift</li>
 *   <li>Validation rejections</li>
 * </ul>
 * 
 * <h3>Grafana Dashboard Integration</h3>
 * <pre>
 * Panels:
 * - "DST Period Bookings" - spike monitoring
 * - "Extension Conflicts" - daily count
 * - "Scheduler Execution Lag" - should be < 1 minute
 * - "Validation Rejections by Type" - breakdown
 * </pre>
 * 
 * <h3>Alert Configuration</h3>
 * <ul>
 *   <li>Clock drift > 5 seconds: WARN</li>
 *   <li>Clock drift > 30 seconds: CRITICAL</li>
 *   <li>Scheduler skip rate > 10%: WARN</li>
 * </ul>
 * 
 * <p><b>Phase 3 - Enterprise Hardening:</b> Part of Time Window Logic Improvement Plan
 * 
 * @since 2026-01 (Phase 3)
 */
@Component
@Slf4j
public class TimeWindowMetrics {

    private final MeterRegistry meterRegistry;
    private final BookingRepository bookingRepository;

    // ========== DST METRICS ==========

    /** Counter for bookings created during DST transition periods */
    private final Counter dstBookingCreationCounter;
    
    /** Counter for check-ins occurring during DST transition */
    private final Counter dstCheckInCounter;
    
    /** Counter for checkouts occurring during DST transition */
    private final Counter dstCheckOutCounter;

    // ========== EXTENSION METRICS ==========

    /** Counter for extensions blocked due to availability conflicts */
    private final Counter extensionConflictCounter;
    
    /** Counter for extension requests (for ratio calculation) */
    private final Counter extensionRequestCounter;

    // ========== VALIDATION METRICS ==========

    /** Counter for lead time validation failures */
    private final Counter leadTimeRejectionCounter;
    
    /** Counter for minimum duration validation failures */
    private final Counter minDurationRejectionCounter;
    
    /** Counter for DST gap time validation failures */
    private final Counter dstGapRejectionCounter;
    
    /** Counter for time granularity validation failures */
    private final Counter granularityRejectionCounter;

    // ========== SCHEDULER METRICS ==========

    /** Timer for scheduler clock drift measurement */
    private final Timer clockDriftTimer;
    
    /** Gauge for current system time vs expected (in milliseconds) */
    private final AtomicLong clockDriftMs = new AtomicLong(0);
    
    /** Counter for scheduler idempotency skips */
    private final Counter schedulerIdempotencySkipCounter;

    // ========== HEALTH INDICATORS ==========
    
    /** Last scheduler execution timestamp (for staleness detection) */
    private final AtomicLong lastSchedulerRunEpochMs = new AtomicLong(System.currentTimeMillis());

    /** One-time guard to avoid flooding logs if DST calculator is unavailable. */
    private final java.util.concurrent.atomic.AtomicBoolean dstCalculatorUnavailableLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public TimeWindowMetrics(MeterRegistry meterRegistry, BookingRepository bookingRepository) {
        this.meterRegistry = meterRegistry;
        this.bookingRepository = bookingRepository;

        // DST metrics
        this.dstBookingCreationCounter = Counter.builder("booking.dst_transition")
                .description("Bookings created during DST transition periods")
                .tag("type", "creation")
                .register(meterRegistry);

        this.dstCheckInCounter = Counter.builder("booking.dst_transition")
                .description("Check-ins during DST transition")
                .tag("type", "checkin")
                .register(meterRegistry);

        this.dstCheckOutCounter = Counter.builder("booking.dst_transition")
                .description("Checkouts during DST transition")
                .tag("type", "checkout")
                .register(meterRegistry);

        // Extension metrics
        this.extensionConflictCounter = Counter.builder("extension.conflict_blocked")
                .description("Extensions blocked due to availability conflicts")
                .register(meterRegistry);

        this.extensionRequestCounter = Counter.builder("extension.request_total")
                .description("Total extension requests")
                .register(meterRegistry);

        // Validation metrics
        this.leadTimeRejectionCounter = Counter.builder("booking.validation.rejected")
                .description("Booking validations rejected")
                .tag("reason", "lead_time")
                .register(meterRegistry);

        this.minDurationRejectionCounter = Counter.builder("booking.validation.rejected")
                .description("Booking validations rejected")
                .tag("reason", "min_duration")
                .register(meterRegistry);

        this.dstGapRejectionCounter = Counter.builder("booking.validation.rejected")
                .description("Booking validations rejected")
                .tag("reason", "dst_gap")
                .register(meterRegistry);

        this.granularityRejectionCounter = Counter.builder("booking.validation.rejected")
                .description("Booking validations rejected")
                .tag("reason", "time_granularity")
                .register(meterRegistry);

        // Scheduler metrics
        this.clockDriftTimer = Timer.builder("scheduler.clock_drift")
                .description("Difference between expected and actual execution time")
                .register(meterRegistry);

        this.schedulerIdempotencySkipCounter = Counter.builder("scheduler.skipped.idempotency")
                .description("Scheduler executions skipped due to idempotency")
                .register(meterRegistry);

        // Clock drift gauge
        Gauge.builder("scheduler.clock_drift_ms", clockDriftMs, AtomicLong::get)
                .description("Current clock drift in milliseconds")
                .register(meterRegistry);

        // Scheduler staleness gauge
        Gauge.builder("scheduler.last_run_age_seconds", () -> 
                (System.currentTimeMillis() - lastSchedulerRunEpochMs.get()) / 1000.0)
                .description("Seconds since last scheduler execution")
                .register(meterRegistry);
    }

    // ========== PUBLIC RECORDING METHODS ==========

    /**
     * Record a booking creation event, detecting if it spans DST.
     * 
     * @param startTime Booking start time
     * @param endTime Booking end time
     */
    public void recordBookingCreation(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            if (TripDurationCalculator.spansDstTransition(startTime, endTime)) {
                dstBookingCreationCounter.increment();
                log.info("[Metrics] Booking created spanning DST transition: {} to {}", startTime, endTime);
            }
        } catch (LinkageError e) {
            logDstCalculatorUnavailable("recordBookingCreation", e);
        }
    }

    /**
     * Record a check-in event.
     * 
     * @param checkInTime Time of check-in
     */
    public void recordCheckIn(LocalDateTime checkInTime) {
        if (isInDstTransitionPeriod(checkInTime)) {
            dstCheckInCounter.increment();
        }
    }

    /**
     * Record a checkout event.
     * 
     * @param checkoutTime Time of checkout
     */
    public void recordCheckout(LocalDateTime checkoutTime) {
        if (isInDstTransitionPeriod(checkoutTime)) {
            dstCheckOutCounter.increment();
        }
    }

    /**
     * Record an extension conflict (blocked due to availability).
     */
    public void recordExtensionConflict() {
        extensionConflictCounter.increment();
    }

    /**
     * Record an extension request.
     */
    public void recordExtensionRequest() {
        extensionRequestCounter.increment();
    }

    /**
     * Record a validation rejection.
     * 
     * @param reason One of: lead_time, min_duration, dst_gap, time_granularity
     */
    public void recordValidationRejection(String reason) {
        switch (reason) {
            case "lead_time" -> leadTimeRejectionCounter.increment();
            case "min_duration" -> minDurationRejectionCounter.increment();
            case "dst_gap" -> dstGapRejectionCounter.increment();
            case "time_granularity" -> granularityRejectionCounter.increment();
            default -> log.warn("[Metrics] Unknown validation rejection reason: {}", reason);
        }
    }

    /**
     * Record scheduler idempotency skip.
     */
    public void recordSchedulerIdempotencySkip() {
        schedulerIdempotencySkipCounter.increment();
    }

    /**
     * Record scheduler execution for health monitoring.
     * 
     * @param expectedTime When the scheduler was expected to run
     * @param actualTime When the scheduler actually ran
     */
    public void recordSchedulerExecution(Instant expectedTime, Instant actualTime) {
        long driftMs = Duration.between(expectedTime, actualTime).toMillis();
        clockDriftMs.set(Math.abs(driftMs));
        clockDriftTimer.record(Math.abs(driftMs), TimeUnit.MILLISECONDS);
        lastSchedulerRunEpochMs.set(actualTime.toEpochMilli());

        if (Math.abs(driftMs) > 30_000) {
            log.warn("[Metrics] CRITICAL: Scheduler clock drift {}ms exceeds 30 second threshold", driftMs);
        } else if (Math.abs(driftMs) > 5_000) {
            log.warn("[Metrics] Scheduler clock drift {}ms exceeds 5 second threshold", driftMs);
        }
    }

    // ========== SCHEDULED HEALTH CHECK ==========

    /**
     * Periodic health check for time-related systems.
     * Runs every minute to verify scheduler health.
     */
    @Scheduled(cron = "0 * * * * *", zone = "Europe/Belgrade")
    public void checkTimeSystemHealth() {
        Instant expected = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant actual = Instant.now();
        recordSchedulerExecution(expected, actual);

        try {
            // Check if we're in a DST transition period
            LocalDateTime now = TripDurationCalculator.nowInSerbia();
            var nextDst = TripDurationCalculator.getNextDstTransition(now);
            if (nextDst != null) {
                Duration untilDst = Duration.between(now, nextDst.before());
                if (untilDst.toHours() < 24) {
                    log.info("[Metrics] DST transition in {} hours: {}", 
                            untilDst.toHours(), nextDst.getDescription());
                }
            }
        } catch (LinkageError e) {
            logDstCalculatorUnavailable("checkTimeSystemHealth", e);
        }
    }

    // ========== PRIVATE HELPERS ==========

    /**
     * Check if a datetime is within 1 hour of a DST transition.
     */
    private boolean isInDstTransitionPeriod(LocalDateTime dateTime) {
        try {
            var nextDst = TripDurationCalculator.getNextDstTransition(dateTime.minusHours(2));
            if (nextDst == null) {
                return false;
            }
            Duration distance = Duration.between(dateTime, nextDst.before()).abs();
            return distance.toHours() < 1;
        } catch (LinkageError e) {
            logDstCalculatorUnavailable("isInDstTransitionPeriod", e);
            return false;
        }
    }

    private void logDstCalculatorUnavailable(String operation, LinkageError error) {
        if (dstCalculatorUnavailableLogged.compareAndSet(false, true)) {
            log.error("[Metrics] TripDurationCalculator nije dostupan; DST metrike su privremeno iskljucene (operacija={}): {}",
                    operation, error.toString(), error);
            return;
        }
        log.debug("[Metrics] TripDurationCalculator i dalje nije dostupan (operacija={}): {}",
                operation, error.toString());
    }
}
