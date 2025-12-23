package org.example.rentoza.booking.checkin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.PhotoGuidanceDTO;
import org.example.rentoza.booking.checkin.dto.PhotoSequenceValidationDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for photo guidance endpoints.
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>GET /api/checkin/photo-guidance/{photoType} - Get guidance for specific photo type</li>
 *   <li>GET /api/checkin/photo-guidance/guest-sequence - Get all guest check-in guidance in order</li>
 *   <li>GET /api/checkin/photo-guidance/host-sequence - Get all host check-in guidance in order</li>
 *   <li>GET /api/checkin/photo-guidance/checkout-sequence - Get all checkout guidance in order</li>
 *   <li>POST /api/checkin/photo-guidance/validate - Validate photo sequence completeness</li>
 * </ul>
 * 
 * @since Enterprise Upgrade Phase 2
 */
@RestController
@RequestMapping("/api/checkin/photo-guidance")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Photo Guidance", description = "Guided photo capture instructions for check-in/checkout")
public class PhotoGuidanceController {
    
    private final PhotoGuidanceService photoGuidanceService;
    
    /**
     * Get detailed guidance for a specific photo type.
     * 
     * @param photoType The photo type to get guidance for
     * @return PhotoGuidanceDTO with instructions, silhouette URL, etc.
     */
    @GetMapping("/{photoType}")
    @Operation(summary = "Get guidance for specific photo type",
               description = "Returns detailed instructions, silhouette URL, and tips for capturing a specific photo")
    public ResponseEntity<PhotoGuidanceDTO> getGuidance(
            @Parameter(description = "Photo type enum value", example = "GUEST_EXTERIOR_FRONT")
            @PathVariable CheckInPhotoType photoType) {
        
        log.debug("Photo guidance requested for type: {}", photoType);
        PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(photoType);
        return ResponseEntity.ok(guidance);
    }
    
    /**
     * Get all guest check-in photo guidance in sequence order.
     */
    @GetMapping("/guest-sequence")
    @Operation(summary = "Get guest check-in photo sequence",
               description = "Returns guidance for all 8 required guest check-in photos in order")
    public ResponseEntity<List<PhotoGuidanceDTO>> getGuestCheckInSequence() {
        log.debug("Guest check-in guidance sequence requested");
        List<PhotoGuidanceDTO> sequence = photoGuidanceService.getGuestCheckInGuidanceSequence();
        return ResponseEntity.ok(sequence);
    }
    
    /**
     * Get all host check-in photo guidance in sequence order.
     */
    @GetMapping("/host-sequence")
    @Operation(summary = "Get host check-in photo sequence",
               description = "Returns guidance for all 8 required host check-in photos in order")
    public ResponseEntity<List<PhotoGuidanceDTO>> getHostCheckInSequence() {
        log.debug("Host check-in guidance sequence requested");
        List<PhotoGuidanceDTO> sequence = photoGuidanceService.getHostCheckInGuidanceSequence();
        return ResponseEntity.ok(sequence);
    }
    
    /**
     * Get all host checkout photo guidance in sequence order.
     */
    @GetMapping("/checkout-sequence")
    @Operation(summary = "Get checkout photo sequence",
               description = "Returns guidance for all 8 required checkout photos in order")
    public ResponseEntity<List<PhotoGuidanceDTO>> getCheckoutSequence() {
        log.debug("Checkout guidance sequence requested");
        List<PhotoGuidanceDTO> sequence = photoGuidanceService.getHostCheckoutGuidanceSequence();
        return ResponseEntity.ok(sequence);
    }
    
    /**
     * Validate that submitted photo types meet requirements.
     */
    @PostMapping("/validate")
    @Operation(summary = "Validate photo sequence",
               description = "Checks if submitted photo types meet the required sequence")
    public ResponseEntity<PhotoSequenceValidationDTO> validateSequence(
            @Parameter(description = "Photo type(s) being validated for", example = "guest-checkin")
            @RequestParam(defaultValue = "guest-checkin") String sequenceType,
            @RequestBody List<CheckInPhotoType> submittedTypes) {
        
        log.debug("Validating {} sequence with {} photos", sequenceType, submittedTypes.size());
        
        PhotoSequenceValidationDTO validation = switch (sequenceType) {
            case "guest-checkin" -> photoGuidanceService.validateGuestCheckInSequence(submittedTypes, true);
            case "host-checkout" -> photoGuidanceService.validateHostCheckoutSequence(submittedTypes, true);
            default -> photoGuidanceService.validateGuestCheckInSequence(submittedTypes, true);
        };
        
        return ResponseEntity.ok(validation);
    }
}
