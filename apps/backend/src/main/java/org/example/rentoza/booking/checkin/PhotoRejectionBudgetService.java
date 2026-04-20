package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.util.ClientIpResolver;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoRejectionBudgetService {

    private static final int MAX_REJECTIONS_IN_WINDOW = 3;
    private static final long WINDOW_MINUTES = 15;

    private final PhotoRejectionBudgetRepository budgetRepository;
    private final UserRepository userRepository;
    private final CheckInEventService eventService;

    @Transactional(readOnly = true)
    public void assertWithinBudget(Booking booking, Long userId, CheckInActorRole actorRole, CheckInPhotoType photoType) {
        String ipHash = hash(getClientIp());
        String deviceHash = hash(getDeviceFingerprint());
        Instant now = Instant.now();

        budgetRepository
                .findByBookingIdAndUserIdAndActorRoleAndPhotoTypeAndIpAddressHashAndDeviceFingerprintHash(
                        booking.getId(), userId, actorRole, photoType, ipHash, deviceHash)
                .ifPresent(budget -> {
                    if (budget.getCooldownUntil() != null && budget.getCooldownUntil().isAfter(now)) {
                        long retryAfter = Math.max(1, ChronoUnit.SECONDS.between(now, budget.getCooldownUntil()));

                        eventService.recordEvent(
                                booking,
                                booking.getCheckInSessionId(),
                                CheckInEventType.PHOTO_REJECTION_BUDGET_EXCEEDED,
                                userId,
                                actorRole,
                                Map.of(
                                        "photoType", photoType.name(),
                                        "retryAfterSeconds", retryAfter,
                                        "cooldownUntil", budget.getCooldownUntil().toString(),
                                        "rejectionCount", budget.getRejectionCount()
                                )
                        );

                        throw new PhotoRejectionBudgetExceededException(
                                "Previše odbijenih pokušaja za ovaj tip fotografije. Sačekajte pre sledećeg pokušaja.",
                                retryAfter,
                                budget.getCooldownUntil()
                        );
                    }
                });
    }

    @Transactional
    public void registerRejection(Booking booking,
                                  Long userId,
                                  CheckInActorRole actorRole,
                                  CheckInPhotoType photoType,
                                  String rejectionCode) {
        String ipHash = hash(getClientIp());
        String deviceHash = hash(getDeviceFingerprint());
        Instant now = Instant.now();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Korisnik nije pronađen: " + userId));

        PhotoRejectionBudget budget = budgetRepository
                .findByBookingIdAndUserIdAndActorRoleAndPhotoTypeAndIpAddressHashAndDeviceFingerprintHash(
                        booking.getId(), userId, actorRole, photoType, ipHash, deviceHash)
                .orElseGet(() -> PhotoRejectionBudget.builder()
                        .booking(booking)
                        .user(user)
                        .actorRole(actorRole)
                        .photoType(photoType)
                        .ipAddressHash(ipHash)
                        .deviceFingerprintHash(deviceHash)
                        .rejectionCount(0)
                        .windowStartedAt(now)
                        .build());

        Instant windowEnd = budget.getWindowStartedAt().plus(WINDOW_MINUTES, ChronoUnit.MINUTES);
        if (now.isAfter(windowEnd)) {
            budget.setWindowStartedAt(now);
            budget.setRejectionCount(1);
            budget.setCooldownUntil(null);
        } else {
            budget.setRejectionCount(budget.getRejectionCount() + 1);
        }

        budget.setLastRejectionCode(rejectionCode);

        if (budget.getRejectionCount() >= MAX_REJECTIONS_IN_WINDOW) {
            budget.setCooldownUntil(now.plus(WINDOW_MINUTES, ChronoUnit.MINUTES));
        }

        budgetRepository.save(budget);

        log.info("[PhotoBudget] Rejection recorded: bookingId={}, userId={}, role={}, type={}, count={}, cooldownUntil={}",
                booking.getId(), userId, actorRole, photoType, budget.getRejectionCount(), budget.getCooldownUntil());
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "UNKNOWN";
            }
            return ClientIpResolver.resolve(attrs.getRequest());
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String getDeviceFingerprint() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null || attrs.getRequest() == null) {
                return "UNKNOWN";
            }
            String fingerprint = attrs.getRequest().getHeader("X-Device-Fingerprint");
            return (fingerprint == null || fingerprint.isBlank()) ? "UNKNOWN" : fingerprint;
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String hash(String raw) {
        String normalized = raw == null || raw.isBlank() ? "UNKNOWN" : raw.trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash rejection budget key", e);
        }
    }
}
