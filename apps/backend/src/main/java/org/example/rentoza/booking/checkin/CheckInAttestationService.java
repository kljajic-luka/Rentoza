package org.example.rentoza.booking.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.RentalAgreementService;
import org.example.rentoza.booking.checkin.dto.CheckInAttestationResponseDTO;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.storage.SupabaseStorageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInAttestationService {

    private static final ZoneId BELGRADE = ZoneId.of("Europe/Belgrade");

    private final CheckInAttestationRepository attestationRepository;
    private final BookingRepository bookingRepository;
    private final CheckInPhotoRepository checkInPhotoRepository;
    private final GuestCheckInPhotoRepository guestCheckInPhotoRepository;
    private final CheckInEventRepository checkInEventRepository;
    private final CheckInEventService checkInEventService;
    private final RentalAgreementService rentalAgreementService;
    private final SupabaseStorageService supabaseStorageService;
    private final PhotoUrlService photoUrlService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public void requestTripStartAttestation(Long bookingId, String checkInSessionId, Long actorId) {
        applicationEventPublisher.publishEvent(
                new CheckInAttestationRequestedEvent(bookingId, checkInSessionId, actorId));
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTripStartAttestationRequested(CheckInAttestationRequestedEvent event) {
        try {
            Booking booking = bookingRepository.findById(event.bookingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

            String activeSessionId = booking.getCheckInSessionId();
            if (activeSessionId == null || activeSessionId.isBlank()) {
                log.warn("[CheckInAttestation] Skipping attestation generation for bookingId={} actorId={} because no active session exists",
                        event.bookingId(), event.actorId());
                return;
            }

            if (!activeSessionId.equals(event.checkInSessionId())) {
                log.warn("[CheckInAttestation] Skipping stale attestation request for bookingId={} expectedSessionId={} activeSessionId={} actorId={}",
                        event.bookingId(), event.checkInSessionId(), activeSessionId, event.actorId());
                return;
            }

            generateTripStartAttestation(booking, event.actorId());
        } catch (Exception e) {
            log.error("[CheckInAttestation] Failed to generate trip-start attestation for bookingId={} sessionId={} actorId={}",
                    event.bookingId(), event.checkInSessionId(), event.actorId(), e);
        }
    }

    @Transactional
    public CheckInAttestation generateTripStartAttestation(Booking booking, Long actorId) {
        String sessionId = booking.getCheckInSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Cannot generate attestation without active check-in session");
        }
        return attestationRepository.findByCheckInSessionId(sessionId)
                .orElseGet(() -> createAttestation(booking, actorId));
    }

    @Transactional
    public CheckInAttestationResponseDTO getAttestationForBooking(Long bookingId, Long requestingUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        String activeSessionId = booking.getCheckInSessionId();
        CheckInAttestation attestation;
        if (activeSessionId != null && !activeSessionId.isBlank()) {
            attestation = attestationRepository.findByBookingIdAndCheckInSessionId(bookingId, activeSessionId)
                    .orElseGet(() -> {
                        if (booking.getTripStartedAt() == null) {
                            throw new ResourceNotFoundException("Check-in attestation not found for active session");
                        }
                        return generateTripStartAttestation(booking, requestingUserId);
                    });
        } else {
            attestation = attestationRepository.findLatestFirstByBookingId(bookingId).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Check-in attestation not found for booking: " + bookingId));
        }

        String signedUrl = photoUrlService.generateSignedUrl(
                SupabaseStorageService.BUCKET_CHECK_IN_AUDIT,
                attestation.getArtifactStorageKey(),
                attestation.getId()
        );

        CheckInActorRole actorRole = resolveActorRole(booking, requestingUserId);

        checkInEventService.recordEvent(
                booking,
                attestation.getCheckInSessionId(),
                CheckInEventType.CHECKIN_ATTESTATION_ACCESSED,
                requestingUserId,
                actorRole,
                Map.of("attestationId", attestation.getId(), "storageKey", attestation.getArtifactStorageKey())
        );

        return CheckInAttestationResponseDTO.builder()
                .bookingId(bookingId)
                .checkInSessionId(attestation.getCheckInSessionId())
                .payloadHash(attestation.getPayloadHash())
                .artifactUrl(signedUrl)
                .createdAt(attestation.getCreatedAt())
                .build();
    }

    private CheckInActorRole resolveActorRole(Booking booking, Long userId) {
        if (booking.getCar().getOwner().getId().equals(userId)) {
            return CheckInActorRole.HOST;
        }
        if (booking.getRenter().getId().equals(userId)) {
            return CheckInActorRole.GUEST;
        }
        return CheckInActorRole.SYSTEM;
    }

    private CheckInAttestation createAttestation(Booking booking, Long actorId) {
        try {
            String sessionId = booking.getCheckInSessionId();
            Map<String, Object> payload = buildCanonicalPayload(booking);
            String canonicalJson = objectMapper.writeValueAsString(payload);
            String payloadHash = sha256(canonicalJson);
            String html = renderDeterministicHtml(payload, payloadHash);
            String artifactStorageKey = supabaseStorageService.uploadCheckInAttestationHtml(
                    booking.getId(),
                    sessionId,
                    html
            );

            CheckInAttestation created = attestationRepository.save(CheckInAttestation.builder()
                    .booking(booking)
                    .checkInSessionId(sessionId)
                    .payloadHash(payloadHash)
                    .payloadJson(canonicalJson)
                    .artifactStorageKey(artifactStorageKey)
                    .build());

            checkInEventService.recordEvent(
                    booking,
                    sessionId,
                    CheckInEventType.CHECKIN_ATTESTATION_GENERATED,
                    actorId,
                    CheckInActorRole.SYSTEM,
                    Map.of(
                            "attestationId", created.getId(),
                            "payloadHash", payloadHash,
                            "artifactStorageKey", artifactStorageKey
                    )
            );

            log.info("[CheckInAttestation] Generated attestation: bookingId={}, attestationId={}", booking.getId(), created.getId());
            return created;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate check-in attestation", e);
        }
    }

    private Map<String, Object> buildCanonicalPayload(Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("checkInSessionId", booking.getCheckInSessionId());
        payload.put("hostId", booking.getCar().getOwner().getId());
        payload.put("guestId", booking.getRenter().getId());
        payload.put("carId", booking.getCar().getId());
        payload.put("scheduledTripStartUtc", booking.getCanonicalStartTimeUtc());
        payload.put("scheduledTripEndUtc", booking.getCanonicalEndTimeUtc());
        payload.put("scheduledTripStartBelgrade", LocalDateTime.ofInstant(booking.getCanonicalStartTimeUtc(), BELGRADE).toString());
        payload.put("scheduledTripEndBelgrade", LocalDateTime.ofInstant(booking.getCanonicalEndTimeUtc(), BELGRADE).toString());
        payload.put("actualTripStartUtc", booking.getTripStartedAt());

        payload.put("hostHandshakeConfirmedAt", eventTimestamp(booking.getId(), CheckInEventType.HANDSHAKE_HOST_CONFIRMED));
        payload.put("guestHandshakeConfirmedAt", eventTimestamp(booking.getId(), CheckInEventType.HANDSHAKE_GUEST_CONFIRMED));
        payload.put("licenseVerifiedInPerson", booking.getLicenseVerifiedInPersonAt() != null);
        payload.put("licenseVerifiedInPersonAt", booking.getLicenseVerifiedInPersonAt());
        payload.put("rentalAgreementAcceptedAtTripStart", rentalAgreementService.isFullyAccepted(booking.getId()));

        List<Map<String, Object>> manifest = new ArrayList<>();
        String sessionId = booking.getCheckInSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Cannot build attestation payload without active check-in session");
        }

        checkInPhotoRepository.findByCheckInSessionId(sessionId).forEach(photo -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("photoId", photo.getId());
            item.put("photoType", photo.getPhotoType().name());
            item.put("imageHash", photo.getImageHash());
            item.put("exifTimestamp", photo.getExifTimestamp());
            item.put("evidenceWeight", photo.getEvidenceWeight() != null ? photo.getEvidenceWeight().name() : null);
            manifest.add(item);
        });
        guestCheckInPhotoRepository.findByCheckInSessionId(sessionId).forEach(photo -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("photoId", photo.getId());
            item.put("photoType", photo.getPhotoType().name());
            item.put("imageHash", photo.getImageHash());
            item.put("exifTimestamp", photo.getExifTimestamp());
            item.put("evidenceWeight", "SECONDARY");
            manifest.add(item);
        });
        payload.put("photoManifest", manifest);

        List<Map<String, Object>> eventSummary = new ArrayList<>();
        checkInEventRepository.findByCheckInSessionIdOrderByEventTimestampAsc(sessionId).forEach(event -> {
            if (event.getEventType() == CheckInEventType.HOST_PHOTO_UPLOADED
                    || event.getEventType() == CheckInEventType.GUEST_CHECK_IN_PHOTO_UPLOADED
                    || event.getEventType() == CheckInEventType.GUEST_CONDITION_ACKNOWLEDGED
                    || event.getEventType() == CheckInEventType.TRIP_STARTED
                    || event.getEventType() == CheckInEventType.LICENSE_VERIFIED_IN_PERSON) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventType", event.getEventType().name());
                item.put("eventTimestamp", event.getEventTimestamp());
                item.put("actorRole", event.getActorRole() != null ? event.getActorRole().name() : null);
                eventSummary.add(item);
            }
        });
        payload.put("eventSummary", eventSummary);

        return payload;
    }

    private Instant eventTimestamp(Long bookingId, CheckInEventType type) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        String sessionId = booking.getCheckInSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        List<CheckInEvent> events = checkInEventRepository.findByCheckInSessionIdAndEventType(sessionId, type);
        if (events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1).getEventTimestamp();
    }

    private String renderDeterministicHtml(Map<String, Object> payload, String payloadHash) throws Exception {
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head><meta charset=\"UTF-8\"><title>Check-In Attestation</title></head>\n"
                + "<body>\n"
                + "<h1>Rentoza Check-In Trip-Start Attestation</h1>\n"
                + "<p><strong>Payload SHA-256:</strong> " + escape(payloadHash) + "</p>\n"
                + "<pre>" + escape(prettyJson) + "</pre>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String sha256(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
