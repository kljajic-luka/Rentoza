package org.example.rentoza.booking.checkin;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for managing the check-in audit trail.
 * 
 * <p>This service handles the creation of immutable {@link CheckInEvent} records.
 * Events are critical for:
 * <ul>
 *   <li>Insurance claims (proving who did what, when)</li>
 *   <li>Dispute resolution (host vs guest conflicts)</li>
 *   <li>Fraud detection (pattern analysis)</li>
 *   <li>Compliance auditing (GDPR data access logs)</li>
 * </ul>
 * 
 * <h2>Immutability</h2>
 * <p>Events can only be created, never updated or deleted. This is enforced at:
 * <ul>
 *   <li>JPA level: {@code @Immutable} annotation</li>
 *   <li>Database level: Triggers preventing UPDATE/DELETE</li>
 *   <li>Service level: No update methods exposed</li>
 * </ul>
 *
 * @see CheckInEvent
 * @see CheckInEventRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInEventService {

    private final CheckInEventRepository eventRepository;

    /**
     * Record a user-triggered event.
     * 
     * @param booking          Parent booking
     * @param sessionId        Check-in session UUID
     * @param eventType        Type of event
     * @param actorId          User ID who triggered the event
     * @param actorRole        Role of the actor (HOST or GUEST)
     * @param clientTimestamp  Optional client-side timestamp
     * @param metadata         Event-specific data
     * @return Created event (immutable)
     */
    @Transactional
    public CheckInEvent recordEvent(
            Booking booking,
            String sessionId,
            CheckInEventType eventType,
            Long actorId,
            CheckInActorRole actorRole,
            Instant clientTimestamp,
            Map<String, Object> metadata) {
        
        // Extract request info for audit
        String ipAddress = null;
        String userAgent = null;
        
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                ipAddress = getClientIpAddress(request);
                userAgent = request.getHeader("User-Agent");
                if (userAgent != null && userAgent.length() > 500) {
                    userAgent = userAgent.substring(0, 500);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract request info for audit", e);
        }
        
        CheckInEvent event = CheckInEvent.create(
            booking,
            sessionId,
            eventType,
            actorId,
            actorRole,
            clientTimestamp,
            metadata,
            ipAddress,
            userAgent
        );
        
        event = eventRepository.save(event);
        
        log.info("[Audit] Event recorded: booking={}, type={}, actor={}/{}, session={}", 
            booking.getId(), eventType, actorRole, actorId, sessionId);
        
        // Add event to booking's collection for in-memory access
        booking.getCheckInEvents().add(event);
        
        return event;
    }

    /**
     * Record a user-triggered event with minimal parameters.
     */
    @Transactional
    public CheckInEvent recordEvent(
            Booking booking,
            String sessionId,
            CheckInEventType eventType,
            Long actorId,
            CheckInActorRole actorRole,
            Map<String, Object> metadata) {
        return recordEvent(booking, sessionId, eventType, actorId, actorRole, null, metadata);
    }

    /**
     * Record a system-triggered event (scheduler, background job).
     */
    @Transactional
    public CheckInEvent recordSystemEvent(
            Booking booking,
            String sessionId,
            CheckInEventType eventType,
            Map<String, Object> metadata) {
        
        CheckInEvent event = CheckInEvent.createSystemEvent(booking, sessionId, eventType, metadata);
        event = eventRepository.save(event);
        
        log.info("[Audit] System event recorded: booking={}, type={}, session={}", 
            booking.getId(), eventType, sessionId);
        
        booking.getCheckInEvents().add(event);
        
        return event;
    }

    /**
     * Check if a specific event type has occurred for a booking.
     */
    @Transactional(readOnly = true)
    public boolean hasEventOfType(Long bookingId, CheckInEventType eventType) {
        return eventRepository.existsByBookingIdAndEventType(bookingId, eventType);
    }

    /**
     * Get all events for a booking (for audit display).
     */
    @Transactional(readOnly = true)
    public List<CheckInEvent> getBookingEvents(Long bookingId) {
        return eventRepository.findByBookingIdOrderByEventTimestampAsc(bookingId);
    }

    /**
     * Get all events for a check-in session.
     */
    @Transactional(readOnly = true)
    public List<CheckInEvent> getSessionEvents(String sessionId) {
        return eventRepository.findByCheckInSessionIdOrderByEventTimestampAsc(sessionId);
    }

    /**
     * Get events by actor role for a booking.
     */
    @Transactional(readOnly = true)
    public List<CheckInEvent> getEventsByRole(Long bookingId, CheckInActorRole role) {
        return eventRepository.findByBookingIdAndActorRole(bookingId, role);
    }

    /**
     * Count photo upload events for a booking.
     * Used to validate minimum photo requirement.
     */
    @Transactional(readOnly = true)
    public long countPhotoUploads(Long bookingId) {
        return eventRepository.countByBookingIdAndEventType(bookingId, CheckInEventType.HOST_PHOTO_UPLOADED);
    }

    /**
     * Get the last event of a specific type for idempotency checks.
     */
    @Transactional(readOnly = true)
    public CheckInEvent getLastEvent(Long bookingId, CheckInEventType eventType) {
        return eventRepository.findLastEventByBookingIdAndEventType(bookingId, eventType);
    }

    /**
     * Get all events by a specific user (for user activity audit).
     */
    @Transactional(readOnly = true)
    public List<CheckInEvent> getUserEvents(Long userId) {
        return eventRepository.findByActorIdOrderByEventTimestampDesc(userId);
    }

    // ========== HELPER METHODS ==========

    /**
     * Extract client IP address, handling proxies.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String[] headerNames = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED"
        };
        
        for (String header : headerNames) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For may contain multiple IPs, take the first one
                int commaIndex = ip.indexOf(',');
                if (commaIndex > 0) {
                    ip = ip.substring(0, commaIndex);
                }
                return ip.trim();
            }
        }
        
        return request.getRemoteAddr();
    }
}
