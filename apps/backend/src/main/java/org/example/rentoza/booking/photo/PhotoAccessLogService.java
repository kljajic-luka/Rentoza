package org.example.rentoza.booking.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * P2-9/P1 FIX: Service for logging all photo access events.
 * 
 * <p>Every photo GET request is logged asynchronously for:
 * <ul>
 *   <li>Audit compliance</li>
 *   <li>Fraud detection</li>
 *   <li>Dispute evidence</li>
 * </ul>
 * 
 * Logging is async to avoid blocking the photo serving endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoAccessLogService {

    private final PhotoAccessLogRepository accessLogRepository;
    private final UserRepository userRepository;

    /**
     * Log a successful photo access (asynchronously).
     *
     * @param userId The user who accessed the photo
     * @param bookingId The booking the photo belongs to
     * @param photoId Optional: the specific photo accessed
     * @param accessType Type of access (GET_SINGLE, LIST_PHOTOS, ADMIN_ACCESS, etc.)
     * @param ipAddress Pre-extracted client IP address
     * @param userAgent Pre-extracted User-Agent header value
     */
    @Async
    @Transactional
    public void logPhotoAccess(Long userId, Long bookingId, Long photoId, String accessType,
                               String ipAddress, String userAgent) {
        logPhotoAccessInternal(userId, bookingId, photoId, accessType, true, 200, null, ipAddress, userAgent);
    }

    /**
     * Log a denied photo access (asynchronously).
     *
     * @param userId The user who tried to access
     * @param bookingId The booking ID
     * @param photoId Optional: the specific photo
     * @param statusCode HTTP status code (403, 404, 429, etc.)
     * @param denialReason Why access was denied
     * @param ipAddress Pre-extracted client IP address
     * @param userAgent Pre-extracted User-Agent header value
     */
    @Async
    @Transactional
    public void logPhotoAccessDenied(Long userId, Long bookingId, Long photoId,
                                     int statusCode, String denialReason,
                                     String ipAddress, String userAgent) {
        logPhotoAccessInternal(userId, bookingId, photoId, "GET_SINGLE", false, statusCode, denialReason, ipAddress, userAgent);
    }

    /**
     * Log batch photo access.
     *
     * @param userId The user
     * @param bookingId The booking
     * @param photoCount Number of photos accessed
     * @param ipAddress Pre-extracted client IP address
     * @param userAgent Pre-extracted User-Agent header value
     */
    @Async
    @Transactional
    public void logPhotosListAccess(Long userId, Long bookingId, int photoCount,
                                    String ipAddress, String userAgent) {
        try {
            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty()) {
                log.warn("[PhotoAccessLog] User not found: {}", userId);
                return;
            }

            PhotoAccessLog accessLog = PhotoAccessLog.builder()
                    .user(user.get())
                    .bookingId(bookingId)
                    .accessType("LIST_PHOTOS")
                    .httpStatusCode(200)
                    .accessGranted(true)
                    .purpose("VIEW")
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .context(String.format("Listed %d photos", photoCount))
                    .build();

            accessLogRepository.save(accessLog);
            log.info("[PhotoAccessLog] Logged LIST_PHOTOS: user={}, booking={}, count={}",
                userId, bookingId, photoCount);

        } catch (Exception e) {
            log.error("[PhotoAccessLog] Failed to log batch access: userId={}, bookingId={}",
                userId, bookingId, e);
        }
    }

    /**
     * Log rate limit violation.
     *
     * @param userId The user who exceeded rate limit
     * @param bookingId The booking accessed
     * @param ipAddress Pre-extracted client IP address
     * @param userAgent Pre-extracted User-Agent header value
     */
    @Async
    @Transactional
    public void logRateLimitViolation(Long userId, Long bookingId,
                                      String ipAddress, String userAgent) {
        logPhotoAccessInternal(userId, bookingId, null, "GET_SINGLE", false, 429, "RATE_LIMIT_EXCEEDED", ipAddress, userAgent);
    }

    /**
     * Internal method to log photo access.
     */
    private void logPhotoAccessInternal(Long userId, Long bookingId, Long photoId,
                                        String accessType, boolean granted,
                                        int statusCode, String denialReason,
                                        String ipAddress, String userAgent) {
        try {
            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty()) {
                log.warn("[PhotoAccessLog] User not found: {}", userId);
                return;
            }

            PhotoAccessLog accessLog = PhotoAccessLog.builder()
                    .user(user.get())
                    .bookingId(bookingId)
                    .photoId(photoId)
                    .accessType(accessType)
                    .httpStatusCode(statusCode)
                    .accessGranted(granted)
                    .purpose("VIEW")
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .denialReason(denialReason)
                    .build();

            accessLogRepository.save(accessLog);

            if (!granted) {
                log.warn("[PhotoAccessLog] Denied access: user={}, booking={}, reason={}, ip={}",
                    userId, bookingId, denialReason, ipAddress);
            }

        } catch (Exception e) {
            log.error("[PhotoAccessLog] Failed to log access: userId={}, bookingId={}, photoId={}",
                userId, bookingId, photoId, e);
            // Don't throw: logging failures should not break photo serving
        }
    }

    /**
     * Check if a user has suspicious access patterns (security monitoring).
     *
     * @param userId The user
     * @return true if user shows signs of abuse
     */
    public boolean isSuspiciousAccessPattern(Long userId) {
        // Check if user downloaded more than 50 photos in last hour
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        long recentAccess = accessLogRepository.countAccessesByUserSince(userId, oneHourAgo);

        return recentAccess > 50;
    }
}
