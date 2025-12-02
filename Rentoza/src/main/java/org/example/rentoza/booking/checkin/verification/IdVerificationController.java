package org.example.rentoza.booking.checkin.verification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.verification.dto.IdVerificationStatusDTO;
import org.example.rentoza.booking.checkin.verification.dto.IdVerificationSubmitDTO;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * REST controller for guest ID verification during check-in.
 * 
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /api/bookings/{id}/verification/status   - Get verification status
 * POST /api/bookings/{id}/verification/initiate - Initialize verification
 * POST /api/bookings/{id}/verification/liveness - Submit liveness selfie
 * POST /api/bookings/{id}/verification/document - Submit ID document
 * </pre>
 * 
 * <h2>Security</h2>
 * <p>Only the guest (renter) of the booking can access these endpoints.
 * PII data (photos, extracted names) is never returned in responses.
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/verification")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class IdVerificationController {

    private final IdVerificationService verificationService;
    private final CurrentUser currentUser;
    private final Counter uploadCounter;

    public IdVerificationController(
            IdVerificationService verificationService,
            CurrentUser currentUser,
            MeterRegistry meterRegistry) {
        this.verificationService = verificationService;
        this.currentUser = currentUser;
        
        this.uploadCounter = Counter.builder("id_verification.upload")
                .description("ID verification photo uploads")
                .register(meterRegistry);
    }

    // ========== STATUS ==========

    /**
     * Get current verification status.
     * 
     * <p>Returns the current state of ID verification for this booking,
     * including next steps and required actions.
     */
    @GetMapping("/status")
    public ResponseEntity<IdVerificationStatusDTO> getStatus(@PathVariable Long bookingId) {
        Long userId = currentUser.id();
        log.debug("[IdVerification] Status request for booking {} by user {}", bookingId, userId);
        
        IdVerificationStatusDTO status = verificationService.getVerificationStatus(bookingId, userId);
        return ResponseEntity.ok(status);
    }

    // ========== INITIATE ==========

    /**
     * Initialize verification process.
     * 
     * <p>Creates a new verification record if one doesn't exist.
     * Returns current status if verification already started.
     */
    @PostMapping("/initiate")
    public ResponseEntity<IdVerificationStatusDTO> initiate(
            @PathVariable Long bookingId,
            @Valid @RequestBody IdVerificationSubmitDTO dto) {
        
        Long userId = currentUser.id();
        log.debug("[IdVerification] Initiating verification for booking {} by user {}", bookingId, userId);
        
        // Ensure bookingId matches
        dto.setBookingId(bookingId);
        
        IdVerificationStatusDTO status = verificationService.initiateVerification(dto, userId);
        return ResponseEntity.ok(status);
    }

    // ========== LIVENESS CHECK ==========

    /**
     * Submit selfie for liveness check.
     * 
     * <p>The selfie is analyzed for anti-spoofing (liveness detection).
     * Requires a minimum confidence score to pass.
     */
    @PostMapping(value = "/liveness", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IdVerificationStatusDTO> submitLiveness(
            @PathVariable Long bookingId,
            @RequestPart("selfie") MultipartFile selfie) throws IOException {
        
        Long userId = currentUser.id();
        log.debug("[IdVerification] Liveness submission for booking {} by user {}", bookingId, userId);
        
        // Validate file
        validateImageFile(selfie, "selfie");
        
        uploadCounter.increment();
        
        IdVerificationStatusDTO status = verificationService.submitLivenessCheck(bookingId, selfie, userId);
        return ResponseEntity.ok(status);
    }

    // ========== DOCUMENT SUBMISSION ==========

    /**
     * Submit ID document for verification.
     * 
     * <p>Submits front (and optionally back) of ID document.
     * Document is analyzed via OCR and compared to user profile.
     */
    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IdVerificationStatusDTO> submitDocument(
            @PathVariable Long bookingId,
            @RequestPart("front") MultipartFile front,
            @RequestPart(value = "back", required = false) MultipartFile back) throws IOException {
        
        Long userId = currentUser.id();
        log.debug("[IdVerification] Document submission for booking {} by user {}", bookingId, userId);
        
        // Validate files
        validateImageFile(front, "front");
        if (back != null) {
            validateImageFile(back, "back");
        }
        
        uploadCounter.increment();
        if (back != null) {
            uploadCounter.increment();
        }
        
        IdVerificationStatusDTO status = verificationService.submitDocument(bookingId, front, back, userId);
        return ResponseEntity.ok(status);
    }

    // ========== VALIDATION ==========

    private void validateImageFile(MultipartFile file, String fieldName) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Fajl " + fieldName + " je obavezan");
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Fajl " + fieldName + " mora biti slika");
        }
        
        // Max 10MB
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Fajl " + fieldName + " ne može biti veći od 10MB");
        }
    }

    // ========== EXCEPTION HANDLERS ==========

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[IdVerification] Illegal state: {}", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", "INVALID_STATE",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[IdVerification] Illegal argument: {}", ex.getMessage());
        
        return ResponseEntity.badRequest().body(Map.of(
            "error", "INVALID_ARGUMENT",
            "message", ex.getMessage()
        ));
    }
}

