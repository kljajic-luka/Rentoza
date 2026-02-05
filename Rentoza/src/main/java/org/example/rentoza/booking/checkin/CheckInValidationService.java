package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.dto.BookingEligibilityDTO;
import org.example.rentoza.exception.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Centralized validation service for check-in workflow.
 * 
 * <h2>Extracted From</h2>
 * <p>This service was extracted from CheckInService (1,543 lines) to follow
 * the Single Responsibility Principle (SRP).
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Access control validation (host/guest authorization)</li>
 *   <li>Check-in timing validation for insurance compliance (Phase 4A)</li>
 *   <li>License verification validation (Phase 4B)</li>
 *   <li>Booking eligibility checks</li>
 *   <li>Geofence proximity validation</li>
 * </ul>
 * 
 * <h2>Phase 4 Safety Improvements</h2>
 * <ul>
 *   <li><b>Phase 4A:</b> Check-in timing - prevents early handoff before insurance active</li>
 *   <li><b>Phase 4B:</b> License verification - ensures host verifies guest's license in-person</li>
 * </ul>
 * 
 * @see CheckInService for check-in orchestration
 * @see CheckInEventService for audit trail
 */
@Service
@Slf4j
public class CheckInValidationService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final CheckInEventService eventService;
    private final RenterVerificationService renterVerificationService;
    private final FeatureFlags featureFlags;
    
    // Metrics
    private final Counter earlyCheckInBlockedCounter;
    private final Counter licenseVerificationCounter;

    // ========== PHASE 4A: CHECK-IN TIMING CONFIGURATION ==========
    
    @Value("${app.checkin.timing.validation-enabled:true}")
    private boolean checkInTimingValidationEnabled;

    @Value("${app.checkin.timing.max-early-hours:2}")
    private int maxEarlyCheckInHours;

    // ========== PHASE 4B: LICENSE VERIFICATION CONFIGURATION ==========
    
    @Value("${app.checkin.license-verification.enabled:true}")
    private boolean licenseVerificationEnabled;

    @Value("${app.checkin.license-verification.required:false}")
    private boolean licenseVerificationRequired;

    public CheckInValidationService(
            CheckInEventService eventService,
            RenterVerificationService renterVerificationService,
            FeatureFlags featureFlags,
            MeterRegistry meterRegistry) {
        this.eventService = eventService;
        this.renterVerificationService = renterVerificationService;
        this.featureFlags = featureFlags;
        
        // Initialize metrics
        this.earlyCheckInBlockedCounter = Counter.builder("checkin.early_blocked")
                .description("Count of early check-in attempts blocked")
                .register(meterRegistry);
        this.licenseVerificationCounter = Counter.builder("checkin.license_verified")
                .description("Count of license verifications completed")
                .register(meterRegistry);
    }

    // ========== ACCESS VALIDATION ==========

    /**
     * Validates that the user has access to the booking (is host or guest).
     * 
     * @param booking The booking to check access for
     * @param userId The user attempting access
     * @throws AccessDeniedException if user is neither host nor guest
     */
    public void validateAccess(Booking booking, Long userId) {
        if (!isHost(booking, userId) && !isGuest(booking, userId)) {
            log.warn("[Validation] Access denied for user {} to booking {}", userId, booking.getId());
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }
    }

    /**
     * Validates that the user is the host of the booking.
     * 
     * @param booking The booking to check
     * @param userId The user ID
     * @throws AccessDeniedException if user is not the host
     */
    public void validateHostAccess(Booking booking, Long userId) {
        if (!isHost(booking, userId)) {
            log.warn("[Validation] Host access denied for user {} to booking {}", userId, booking.getId());
            throw new AccessDeniedException("Samo vlasnik vozila može pristupiti ovoj akciji");
        }
    }

    /**
     * Validates that the user is the guest of the booking.
     * 
     * @param booking The booking to check
     * @param userId The user ID
     * @throws AccessDeniedException if user is not the guest
     */
    public void validateGuestAccess(Booking booking, Long userId) {
        if (!isGuest(booking, userId)) {
            log.warn("[Validation] Guest access denied for user {} to booking {}", userId, booking.getId());
            throw new AccessDeniedException("Samo gost može pristupiti ovoj akciji");
        }
    }

    /**
     * Checks if the user is the host (car owner) of the booking.
     * 
     * @param booking The booking
     * @param userId The user ID
     * @return true if user is the host
     */
    public boolean isHost(Booking booking, Long userId) {
        return booking.getCar().getOwner().getId().equals(userId);
    }

    /**
     * Checks if the user is the guest (renter) of the booking.
     * 
     * @param booking The booking
     * @param userId The user ID
     * @return true if user is the guest
     */
    public boolean isGuest(Booking booking, Long userId) {
        return booking.getRenter().getId().equals(userId);
    }

    // ========== PHASE 4A: CHECK-IN TIMING VALIDATION ==========

    /**
     * Validates check-in timing for insurance compliance.
     * 
     * <p>Insurance policies typically begin at the scheduled trip start time. Completing
     * check-in too early creates a coverage gap where the vehicle is with the guest
     * but insurance is not yet active.
     * 
     * <p>Default: Check-in allowed up to 2 hours before trip start.
     * 
     * @param booking The booking to validate
     * @param userId The user attempting to complete check-in
     * @param actorRole HOST or GUEST
     * @throws IllegalStateException if check-in is attempted too early
     */
    public void validateCheckInTiming(Booking booking, Long userId, CheckInActorRole actorRole) {
        if (!checkInTimingValidationEnabled) {
            log.debug("[Validation-Phase4A] Timing validation disabled by configuration");
            return;
        }
        
        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        LocalDateTime tripStart = booking.getStartTime();
        LocalDateTime earliestAllowedCheckIn = tripStart.minusHours(maxEarlyCheckInHours);
        
        long minutesUntilTrip = ChronoUnit.MINUTES.between(now, tripStart);
        long maxEarlyMinutes = maxEarlyCheckInHours * 60L;
        
        if (now.isBefore(earliestAllowedCheckIn)) {
            // Log the blocked attempt
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.EARLY_CHECK_IN_BLOCKED,
                userId,
                actorRole,
                Map.of(
                    "tripStartTime", tripStart.toString(),
                    "attemptTime", now.toString(),
                    "minutesUntilTrip", minutesUntilTrip,
                    "maxEarlyMinutes", maxEarlyMinutes,
                    "earliestAllowedTime", earliestAllowedCheckIn.toString()
                )
            );
            
            earlyCheckInBlockedCounter.increment();
            
            log.warn("[Validation-Phase4A] Early check-in blocked for booking {}. " +
                    "Attempt at {}, trip starts at {}, earliest allowed at {}",
                    booking.getId(), now, tripStart, earliestAllowedCheckIn);
            
            // Format user-friendly message
            String earliestTimeFormatted = earliestAllowedCheckIn.format(TIME_FORMATTER);
            
            throw new IllegalStateException(String.format(
                "Prijem nije dozvoljen više od %d sat(a) pre početka putovanja. " +
                "Molimo pokušajte ponovo u %s ili kasnije. " +
                "Ovo osigurava da je vaše osiguranje aktivno tokom predaje vozila.",
                maxEarlyCheckInHours,
                earliestTimeFormatted
            ));
        }
        
        // Log successful timing validation
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.CHECK_IN_TIMING_VALIDATED,
            userId,
            actorRole,
            Map.of(
                "tripStartTime", tripStart.toString(),
                "attemptTime", now.toString(),
                "minutesUntilTrip", minutesUntilTrip,
                "maxEarlyMinutes", maxEarlyMinutes
            )
        );
        
        log.debug("[Validation-Phase4A] Timing validation passed for booking {}. " +
                "Check-in at {}, trip starts at {} ({}min away)",
                booking.getId(), now, tripStart, minutesUntilTrip);
    }

    // ========== PHASE 4B: LICENSE VERIFICATION VALIDATION ==========

    /**
     * Validates that license verification has been completed before handshake.
     * 
     * <p>When license verification is required, the host must confirm they have
     * visually verified the guest's driver's license in-person before the handshake
     * can proceed.
     * 
     * @param booking The booking to validate
     * @throws IllegalStateException if license verification is required but not completed
     */
    public void validateLicenseVerification(Booking booking) {
        if (!licenseVerificationEnabled || !licenseVerificationRequired) {
            log.debug("[Validation-Phase4B] License verification disabled or not required");
            return;
        }
        
        if (booking.getLicenseVerifiedInPersonAt() == null) {
            log.warn("[Validation-Phase4B] License verification required but not completed for booking {}",
                    booking.getId());
            throw new IllegalStateException(
                "Morate potvrditi da ste lično proverili vozačku dozvolu gosta " +
                "pre završetka predaje vozila. Ovo je neophodno za validnost osiguranja."
            );
        }
        
        log.debug("[Validation-Phase4B] License verification confirmed for booking {} at {}",
                booking.getId(), booking.getLicenseVerifiedInPersonAt());
    }

    /**
     * Records that license verification has been completed.
     * 
     * @param booking The booking
     * @param userId The host user ID who verified the license
     */
    public void recordLicenseVerification(Booking booking, Long userId) {
        licenseVerificationCounter.increment();
        
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.LICENSE_VERIFIED_IN_PERSON,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                "verifiedAt", LocalDateTime.now(SERBIA_ZONE).toString(),
                "guestUserId", booking.getRenter().getId().toString()
            )
        );
        
        log.info("[Validation-Phase4B] License verification recorded for booking {} by host {}",
                booking.getId(), userId);
    }

    // ========== BOOKING ELIGIBILITY VALIDATION ==========

    /**
     * Validates that the guest is eligible to complete the booking.
     * 
     * <p>Checks include:
     * <ul>
     *   <li>Email verification</li>
     *   <li>Phone verification</li>
     *   <li>Identity verification (if required)</li>
     *   <li>Payment method on file</li>
     * </ul>
     * 
     * @param booking The booking to validate
     * @throws ValidationException if guest is not eligible
     */
    public void validateGuestEligibility(Booking booking) {
        if (!featureFlags.isStrictCheckinEnabled()) {
            log.debug("[Validation] Guest eligibility check disabled by feature flag");
            return;
        }
        
        BookingEligibilityDTO eligibility = renterVerificationService.checkBookingEligibility(
            booking.getRenter().getId(),
            booking.getEndTime().toLocalDate()
        );
        
        if (!eligibility.isEligible()) {
            log.warn("[Validation] Guest {} not eligible for booking {}: {}",
                    booking.getRenter().getId(), booking.getId(), eligibility.getMessageSr());
            throw new ValidationException("Check-in blocked: " + eligibility.getMessageSr());
        }
        
        log.debug("[Validation] Guest eligibility confirmed for booking {}", booking.getId());
    }

    // ========== STATUS VALIDATION ==========

    /**
     * Validates that the booking is in the expected status for an operation.
     * 
     * @param booking The booking to validate
     * @param expectedStatuses The allowed statuses
     * @param operationName The name of the operation for error messages
     * @throws IllegalStateException if booking is not in an expected status
     */
    public void validateBookingStatus(Booking booking, BookingStatus[] expectedStatuses, String operationName) {
        for (BookingStatus expected : expectedStatuses) {
            if (booking.getStatus() == expected) {
                return;
            }
        }
        
        log.warn("[Validation] Invalid status for {} on booking {}: expected one of {}, got {}",
                operationName, booking.getId(), expectedStatuses, booking.getStatus());
        
        throw new IllegalStateException(String.format(
            "Rezervacija nije u ispravnom statusu za %s. Trenutni status: %s",
            operationName, booking.getStatus().name()
        ));
    }

    // ========== CONFIGURATION GETTERS (for CheckInService) ==========

    public boolean isCheckInTimingValidationEnabled() {
        return checkInTimingValidationEnabled;
    }

    public int getMaxEarlyCheckInHours() {
        return maxEarlyCheckInHours;
    }

    public boolean isLicenseVerificationEnabled() {
        return licenseVerificationEnabled;
    }

    public boolean isLicenseVerificationRequired() {
        return licenseVerificationRequired;
    }
}
