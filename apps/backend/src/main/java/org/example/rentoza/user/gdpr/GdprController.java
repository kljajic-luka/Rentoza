package org.example.rentoza.user.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.JwtUserPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * GDPR Compliance Controller - Implements EU data protection rights.
 * 
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Article 15 - Right of access (data export)</li>
 *   <li>Article 17 - Right to erasure (account deletion)</li>
 *   <li>Article 7 - Consent management</li>
 * </ul>
 * 
 * <p>Serbia is an EU candidate country and these rights are implemented
 * proactively for compliance readiness.
 */
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "GDPR", description = "GDPR compliance endpoints - data export, deletion, consent")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
@Slf4j
public class GdprController {

    private final GdprService gdprService;

    // ==================== Data Export (Article 15) ====================

    @Operation(
            summary = "Export my data",
            description = """
                    **GDPR Article 15 - Right of Access**
                    
                    Export all personal data associated with your account in JSON format.
                    
                    **Included data:**
                    - Profile information (name, email, phone)
                    - Booking history
                    - Reviews given and received
                    - Car listings (if owner)
                    - Payment history (masked card numbers)
                    - Consent records
                    
                    **Processing time:** Immediate (< 30 seconds)
                    
                    **Rate limit:** 1 request per 24 hours
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Data exported successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UserDataExportDTO.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded - try again in 24 hours")
    })
    @GetMapping("/data-export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> exportMyData(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            HttpServletRequest request
    ) {
        log.info("GDPR: Data export requested by user {}", principal.id());

        try {
            UserDataExportDTO export = gdprService.exportUserData(principal.id());

            // GAP-5: Log the data export as a data access event
            gdprService.logDataAccess(principal.id(), principal.id(), "USER",
                    "EXPORT_DATA", "GDPR Article 15 data export",
                    "Web App", extractClientIp(request));

            String filename = "rentoza-data-export-" + principal.id() + "-" +
                    LocalDateTime.now().toLocalDate() + ".json";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(export);
        } catch (GdprRateLimitException e) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Rate limit exceeded",
                    "message", "Data export is limited to once per 24 hours",
                    "retryAfter", e.getRetryAfter()
            ));
        }
    }

    // ==================== Account Deletion (Article 17) ====================

    @Operation(
            summary = "Request account deletion",
            description = """
                    **GDPR Article 17 - Right to Erasure (Right to be Forgotten)**
                    
                    Initiate account deletion process.
                    
                    **Soft delete policy:**
                    - Account deactivated immediately
                    - Personal data anonymized within 30 days
                    - Some data retained for legal obligations (7 years for financial records)
                    
                    **Active bookings:**
                    If you have upcoming bookings, they must be cancelled first.
                    
                    **Irreversible:** This action cannot be undone.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(examples = @ExampleObject(
                            value = "{\"confirmEmail\": \"user@example.com\", \"reason\": \"No longer using the service\"}"
                    ))
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deletion scheduled",
                    content = @Content(examples = @ExampleObject(
                            value = "{\"message\": \"Account deletion scheduled\", \"deletionDate\": \"2024-03-01\"}"))),
            @ApiResponse(responseCode = "400", description = "Cannot delete - active bookings exist"),
            @ApiResponse(responseCode = "409", description = "Email confirmation mismatch")
    })
    @DeleteMapping("/delete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> deleteMyAccount(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @RequestBody AccountDeletionRequestDTO request
    ) {
        log.warn("GDPR: Account deletion requested by user {}", principal.id());
        
        try {
            AccountDeletionResultDTO result = gdprService.initiateAccountDeletion(
                    principal.id(), 
                    principal.getUsername(),
                    request
            );
            
            return ResponseEntity.ok(result);
        } catch (ActiveBookingsException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot delete account",
                    "message", "You have active or upcoming bookings that must be cancelled first",
                    "activeBookings", e.getBookingIds()
            ));
        } catch (EmailMismatchException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Confirmation failed",
                    "message", "Email address does not match your account"
            ));
        }
    }

    @Operation(
            summary = "Cancel deletion request",
            description = "Cancel a pending account deletion request within the grace period (30 days)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deletion cancelled"),
            @ApiResponse(responseCode = "400", description = "No pending deletion request")
    })
    @PostMapping("/cancel-deletion")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> cancelDeletion(@AuthenticationPrincipal JwtUserPrincipal principal) {
        log.info("GDPR: Deletion cancellation requested by user {}", principal.id());
        
        try {
            gdprService.cancelAccountDeletion(principal.id());
            return ResponseEntity.ok(Map.of(
                    "message", "Account deletion cancelled",
                    "accountStatus", "ACTIVE"
            ));
        } catch (NoDeletionPendingException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No pending deletion",
                    "message", "No account deletion request is pending for your account"
            ));
        }
    }

    // ==================== Consent Management (Article 7) ====================

    @Operation(
            summary = "Get my consent preferences",
            description = "Retrieve current consent settings for marketing, analytics, and data processing."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consent preferences retrieved",
                    content = @Content(schema = @Schema(implementation = ConsentPreferencesDTO.class)))
    })
    @GetMapping("/consent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConsentPreferencesDTO> getConsentPreferences(
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        ConsentPreferencesDTO preferences = gdprService.getConsentPreferences(principal.id());
        return ResponseEntity.ok(preferences);
    }

    @Operation(
            summary = "Update consent preferences",
            description = """
                    Update your consent preferences.
                    
                    **Consent types:**
                    - `marketingEmails` - Promotional emails and newsletters
                    - `smsNotifications` - SMS booking reminders and promotions
                    - `analyticsTracking` - Usage analytics for service improvement
                    - `thirdPartySharing` - Sharing data with partners (insurance, etc.)
                    
                    **Note:** Essential service communications cannot be disabled.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consent updated"),
            @ApiResponse(responseCode = "400", description = "Invalid consent type")
    })
    @PutMapping("/consent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConsentPreferencesDTO> updateConsentPreferences(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @RequestBody ConsentPreferencesDTO preferences,
            HttpServletRequest request
    ) {
        // GAP-4 fix: capture real client IP and User-Agent for consent provenance
        String ipAddress = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        log.info("GDPR: Consent preferences updated by user {} from IP {}", principal.id(), ipAddress);

        ConsentPreferencesDTO updated = gdprService.updateConsentPreferences(
                principal.id(), preferences, ipAddress, userAgent);
        return ResponseEntity.ok(updated);
    }

    // ==================== Data Processing Records ====================

    @Operation(
            summary = "Get data processing history",
            description = "View a log of how your data has been accessed and processed."
    )
    @GetMapping("/data-access-log")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getDataAccessLog(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Parameter(description = "Number of days to look back", example = "30")
            @RequestParam(defaultValue = "30") int days
    ) {
        var accessLog = gdprService.getDataAccessLog(principal.id(), days);
        return ResponseEntity.ok(accessLog);
    }

    // ==================== Helpers ====================

    /**
     * Extract real client IP, considering proxy headers (X-Forwarded-For).
     * Cloud Run sets X-Forwarded-For with the real client IP.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may contain multiple IPs; first is the original client
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
