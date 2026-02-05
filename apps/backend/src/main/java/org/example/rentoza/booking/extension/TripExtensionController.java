package org.example.rentoza.booking.extension;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.extension.dto.TripExtensionDTO;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for trip extension requests.
 * 
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /api/bookings/{id}/extensions              - Get extensions for booking
 * GET  /api/bookings/{id}/extensions/pending      - Get pending extension
 * POST /api/bookings/{id}/extensions              - Request extension (guest)
 * POST /api/bookings/{id}/extensions/{extId}/approve  - Approve extension (host)
 * POST /api/bookings/{id}/extensions/{extId}/decline  - Decline extension (host)
 * POST /api/bookings/{id}/extensions/{extId}/cancel   - Cancel request (guest)
 * </pre>
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/extensions")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class TripExtensionController {

    private final TripExtensionService extensionService;
    private final CurrentUser currentUser;

    public TripExtensionController(TripExtensionService extensionService, CurrentUser currentUser) {
        this.extensionService = extensionService;
        this.currentUser = currentUser;
    }

    // ========== RETRIEVAL ==========

    @GetMapping
    public ResponseEntity<List<TripExtensionDTO>> getExtensions(@PathVariable Long bookingId) {
        Long userId = currentUser.id();
        List<TripExtensionDTO> extensions = extensionService.getExtensionsForBooking(bookingId, userId);
        return ResponseEntity.ok(extensions);
    }

    @GetMapping("/pending")
    public ResponseEntity<TripExtensionDTO> getPendingExtension(@PathVariable Long bookingId) {
        Long userId = currentUser.id();
        TripExtensionDTO extension = extensionService.getPendingExtension(bookingId, userId);
        
        if (extension == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(extension);
    }

    // ========== GUEST ACTIONS ==========

    @PostMapping
    public ResponseEntity<TripExtensionDTO> requestExtension(
            @PathVariable Long bookingId,
            @Valid @RequestBody ExtensionRequest request) {
        
        Long userId = currentUser.id();
        log.info("[TripExtension] User {} requesting extension for booking {}", userId, bookingId);
        
        TripExtensionDTO extension = extensionService.requestExtension(
                bookingId,
                request.getNewEndDate(),
                request.getReason(),
                userId
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(extension);
    }

    @PostMapping("/{extensionId}/cancel")
    public ResponseEntity<TripExtensionDTO> cancelExtension(
            @PathVariable Long bookingId,
            @PathVariable Long extensionId) {
        
        Long userId = currentUser.id();
        log.info("[TripExtension] User {} cancelling extension {}", userId, extensionId);
        
        TripExtensionDTO extension = extensionService.cancelExtension(extensionId, userId);
        return ResponseEntity.ok(extension);
    }

    // ========== HOST ACTIONS ==========

    @PostMapping("/{extensionId}/approve")
    public ResponseEntity<TripExtensionDTO> approveExtension(
            @PathVariable Long bookingId,
            @PathVariable Long extensionId,
            @RequestBody(required = false) HostResponse response) {
        
        Long userId = currentUser.id();
        log.info("[TripExtension] User {} approving extension {}", userId, extensionId);
        
        String responseText = response != null ? response.getResponse() : null;
        TripExtensionDTO extension = extensionService.approveExtension(extensionId, responseText, userId);
        return ResponseEntity.ok(extension);
    }

    @PostMapping("/{extensionId}/decline")
    public ResponseEntity<TripExtensionDTO> declineExtension(
            @PathVariable Long bookingId,
            @PathVariable Long extensionId,
            @RequestBody(required = false) HostResponse response) {
        
        Long userId = currentUser.id();
        log.info("[TripExtension] User {} declining extension {}", userId, extensionId);
        
        String responseText = response != null ? response.getResponse() : null;
        TripExtensionDTO extension = extensionService.declineExtension(extensionId, responseText, userId);
        return ResponseEntity.ok(extension);
    }

    // ========== REQUEST DTOs ==========

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtensionRequest {
        @NotNull(message = "Novi datum završetka je obavezan")
        @Future(message = "Novi datum mora biti u budućnosti")
        private LocalDate newEndDate;
        
        private String reason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HostResponse {
        private String response;
    }

    // ========== EXCEPTION HANDLERS ==========

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[TripExtension] Illegal state: {}", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", "INVALID_STATE",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[TripExtension] Illegal argument: {}", ex.getMessage());
        
        return ResponseEntity.badRequest().body(Map.of(
            "error", "INVALID_ARGUMENT",
            "message", ex.getMessage()
        ));
    }
}


