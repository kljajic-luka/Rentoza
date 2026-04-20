package org.example.rentoza.availability;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.availability.dto.BlockDateRequestDTO;
import org.example.rentoza.availability.dto.BlockedDateResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for managing car availability blocking.
 * Provides endpoints for owners to block/unblock dates and for renters to check availability.
 */
@RestController
@RequestMapping("/api/availability")
@Slf4j
public class BlockedDateController {

    private final BlockedDateService blockedDateService;

    public BlockedDateController(BlockedDateService blockedDateService) {
        this.blockedDateService = blockedDateService;
    }

    /**
     * GET /api/availability/{carId}
     * Retrieve all blocked dates for a specific car.
     * PUBLIC: Accessible to all users (including guests) for availability checking.
     * No PII is exposed - only date ranges.
     *
     * @param carId The ID of the car
     * @return List of blocked date ranges
     */
    @GetMapping("/{carId}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> getBlockedDates(@PathVariable Long carId) {
        try {
            List<BlockedDateResponseDTO> blockedDates = blockedDateService.getBlockedDatesForCar(carId);
            return ResponseEntity.ok(blockedDates);
        } catch (Exception e) {
            log.error("Error retrieving blocked dates for car {}: {}", carId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve blocked dates"));
        }
    }

    /**
     * POST /api/availability/block
     * Block a date range for a car.
     * Only the car owner (authenticated via JWT) can block dates.
     *
     * Request body example:
     * {
     *   "carId": 1,
     *   "startDate": "2025-12-01",
     *   "endDate": "2025-12-10"
     * }
     *
     * @param request The block date request containing carId, startDate, and endDate
     * @param authHeader JWT authentication header
     * @return The created blocked date range
     */
    @PostMapping("/block")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> blockDateRange(
            @Valid @RequestBody BlockDateRequestDTO request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            BlockedDateResponseDTO blockedDate = blockedDateService.blockDateRange(request, principal.getUsername());
            log.info("Successfully blocked dates for car {}: {} to {}",
                    request.getCarId(), request.getStartDate(), request.getEndDate());
            return ResponseEntity.status(HttpStatus.CREATED).body(blockedDate);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Validation error blocking dates: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error blocking dates: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to block dates"));
        }
    }

    /**
     * DELETE /api/availability/block/{blockId}
     * Unblock (delete) a specific blocked date range.
     * Only the car owner (authenticated via JWT) can unblock their own dates.
     *
     * @param blockId The ID of the blocked date range to delete
     * @param authHeader JWT authentication header
     * @return 204 No Content on success
     */
    @DeleteMapping("/block/{blockId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> unblockDateRange(
            @PathVariable Long blockId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            blockedDateService.unblockDateRange(blockId, principal.getUsername());
            log.info("Successfully unblocked date range {}", blockId);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            log.warn("Authorization error unblocking dates: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error unblocking dates: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to unblock dates"));
        }
    }
}
