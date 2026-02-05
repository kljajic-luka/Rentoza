package org.example.rentoza.booking.checkin;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * REST API Response Optimizer for Check-In endpoints.
 * 
 * <h2>Phase 3: API Optimization</h2>
 * <p>Implements enterprise-grade API patterns from Turo/Airbnb:
 * <ul>
 *   <li><b>ETag Support:</b> Conditional GET with If-None-Match header</li>
 *   <li><b>Sparse Fieldsets:</b> ?fields=status,photos - return only requested fields</li>
 *   <li><b>Cache-Control:</b> Proper HTTP caching directives</li>
 *   <li><b>Compression Hints:</b> Vary headers for gzip content negotiation</li>
 * </ul>
 * 
 * <h3>ETag Generation</h3>
 * <p>ETags are weak validators based on content hash (MD5).
 * Format: W/"checksum" (weak validator allows semantic equivalence).
 * 
 * <h3>Usage Example</h3>
 * <pre>
 * GET /api/bookings/123/check-in/status
 * If-None-Match: W/"abc123"
 * 
 * Response: 304 Not Modified (if unchanged)
 * Response: 200 OK with ETag: W/"xyz789" (if changed)
 * </pre>
 * 
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag">MDN: ETag</a>
 */
@Component
@Slf4j
public class CheckInResponseOptimizer {

    /**
     * Allowed fields for sparse fieldset filtering.
     * Prevents clients from requesting arbitrary JSON paths.
     */
    public static final Set<String> ALLOWED_SPARSE_FIELDS = Set.of(
        "bookingId",
        "status",
        "hostCheckInComplete",
        "guestConditionAcknowledged",
        "handshakeComplete",
        "hostConfirmedHandshake",
        "guestConfirmedHandshake",
        "hostCheckInPhotoCount",
        "photos",
        "odometerStart",
        "fuelLevelStart",
        "handoffType",
        "geofenceStatus",
        "noShowDeadline",
        "lastUpdated",
        "canHostComplete",
        "canGuestAcknowledge",
        "canStartTrip"
    );

    /**
     * Generate ETag for a CheckInStatusDTO.
     * Uses weak validator (W/) to indicate semantic equivalence.
     */
    public String generateETag(CheckInStatusDTO status) {
        try {
            // Build a fingerprint of key fields that affect display
            String fingerprint = String.format(
                "%d|%s|%s|%s|%s|%d|%s",
                status.getBookingId(),
                status.getStatus(),
                status.isHostCheckInComplete(),
                status.isGuestConditionAcknowledged(),
                status.isHandshakeComplete(),
                status.getHostCheckInPhotoCount(),
                status.getLastUpdated()
            );

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(hash).substring(0, 12);

            return "W/\"" + encoded + "\"";
        } catch (Exception e) {
            log.warn("[CheckInOptimizer] Failed to generate ETag: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if client's cached version matches current ETag.
     */
    public boolean isNotModified(String ifNoneMatch, String currentETag) {
        if (ifNoneMatch == null || currentETag == null) {
            return false;
        }

        // Handle multiple ETags in If-None-Match header
        String[] clientETags = ifNoneMatch.split(",");
        for (String clientETag : clientETags) {
            String trimmed = clientETag.trim();
            
            // ETag matching (weak comparison as per RFC 7232)
            if (trimmed.equals(currentETag) || 
                trimmed.equals("*") ||
                weakMatch(trimmed, currentETag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Weak ETag comparison (ignore W/ prefix).
     * W/"abc" == "abc" == W/"abc"
     */
    private boolean weakMatch(String etag1, String etag2) {
        String stripped1 = stripWeakPrefix(etag1);
        String stripped2 = stripWeakPrefix(etag2);
        return stripped1.equals(stripped2);
    }

    private String stripWeakPrefix(String etag) {
        if (etag.startsWith("W/")) {
            return etag.substring(2);
        }
        return etag;
    }

    /**
     * Build optimized response with ETag and Cache-Control headers.
     * 
     * @param status The check-in status DTO
     * @param ifNoneMatch Client's If-None-Match header value
     * @return ResponseEntity with 304 Not Modified or 200 OK with fresh data
     */
    public ResponseEntity<CheckInStatusDTO> buildOptimizedResponse(
            CheckInStatusDTO status,
            String ifNoneMatch) {
        
        String etag = generateETag(status);
        
        // Check for conditional GET
        if (isNotModified(ifNoneMatch, etag)) {
            log.debug("[CheckInOptimizer] 304 Not Modified for booking {}", status.getBookingId());
            return ResponseEntity.status(304)
                .eTag(etag)
                .build();
        }

        // Fresh response with caching headers
        return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5))
                .mustRevalidate()
                .cachePrivate())
            .header(HttpHeaders.VARY, "Accept-Encoding", "Authorization")
            .body(status);
    }

    /**
     * Apply sparse fieldsets filter.
     * 
     * <p>Returns a new DTO with only requested fields populated.
     * This reduces payload size for mobile clients that only need
     * specific information (e.g., status only for badge updates).
     * 
     * @param status The full check-in status DTO
     * @param fields Comma-separated list of field names (e.g., "status,photos")
     * @return Filtered DTO (null fields omitted in JSON serialization)
     */
    public CheckInStatusDTO applySparseFieldset(CheckInStatusDTO status, String fields) {
        if (fields == null || fields.isBlank()) {
            return status; // Return full response
        }

        Set<String> requestedFields = Set.of(fields.split(","));
        
        // Validate requested fields
        for (String field : requestedFields) {
            if (!ALLOWED_SPARSE_FIELDS.contains(field.trim())) {
                log.warn("[CheckInOptimizer] Invalid sparse field requested: {}", field);
                // Could throw BadRequest, but we'll be lenient and just ignore
            }
        }

        // Build sparse response
        CheckInStatusDTO sparse = new CheckInStatusDTO();
        
        // Always include bookingId for context
        sparse.setBookingId(status.getBookingId());
        
        if (requestedFields.contains("status")) {
            sparse.setStatus(status.getStatus());
        }
        if (requestedFields.contains("hostCheckInComplete")) {
            sparse.setHostCheckInComplete(status.isHostCheckInComplete());
        }
        if (requestedFields.contains("guestConditionAcknowledged")) {
            sparse.setGuestConditionAcknowledged(status.isGuestConditionAcknowledged());
        }
        if (requestedFields.contains("handshakeComplete")) {
            sparse.setHandshakeComplete(status.isHandshakeComplete());
        }
        if (requestedFields.contains("hostConfirmedHandshake")) {
            sparse.setHostConfirmedHandshake(status.isHostConfirmedHandshake());
        }
        if (requestedFields.contains("guestConfirmedHandshake")) {
            sparse.setGuestConfirmedHandshake(status.isGuestConfirmedHandshake());
        }
        if (requestedFields.contains("hostCheckInPhotoCount")) {
            sparse.setHostCheckInPhotoCount(status.getHostCheckInPhotoCount());
        }
        if (requestedFields.contains("photos")) {
            sparse.setPhotos(status.getPhotos());
        }
        if (requestedFields.contains("odometerStart")) {
            sparse.setOdometerStart(status.getOdometerStart());
        }
        if (requestedFields.contains("fuelLevelStart")) {
            sparse.setFuelLevelStart(status.getFuelLevelStart());
        }
        if (requestedFields.contains("handoffType")) {
            sparse.setHandoffType(status.getHandoffType());
        }
        if (requestedFields.contains("geofenceStatus")) {
            sparse.setGeofenceStatus(status.getGeofenceStatus());
        }
        if (requestedFields.contains("noShowDeadline")) {
            sparse.setNoShowDeadline(status.getNoShowDeadline());
        }
        if (requestedFields.contains("lastUpdated")) {
            sparse.setLastUpdated(status.getLastUpdated());
        }
        if (requestedFields.contains("canHostComplete")) {
            sparse.setCanHostComplete(status.isCanHostComplete());
        }
        if (requestedFields.contains("canGuestAcknowledge")) {
            sparse.setCanGuestAcknowledge(status.isCanGuestAcknowledge());
        }
        if (requestedFields.contains("canStartTrip")) {
            sparse.setCanStartTrip(status.isCanStartTrip());
        }

        log.debug("[CheckInOptimizer] Applied sparse fieldset: {} fields for booking {}", 
            requestedFields.size(), status.getBookingId());
        
        return sparse;
    }
}
