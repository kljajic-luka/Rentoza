package org.example.rentoza.booking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.booking.dto.CancellationPreviewDTO;
import org.example.rentoza.booking.dto.CancellationRequestDTO;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.booking.dto.RentalAgreementDTO;
import org.example.rentoza.booking.dto.UserBookingResponseDTO;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.config.logging.RequestLoggingContextFilter;
import org.example.rentoza.exception.ApiErrorResponse;
import org.example.rentoza.exception.ResourceNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * REST controller for booking operations with RLS enforcement.
 * All endpoints now protected with @PreAuthorize annotations for defense-in-depth.
 * Service layer provides additional ownership validation.
 */
@RestController
@RequestMapping("/api/bookings")
@Tag(name = "Bookings", description = "Booking creation, management, and cancellation")
@SecurityRequirement(name = "bearerAuth")
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingService service;
    private final BookingApprovalService approvalService;
    private final RentalAgreementService rentalAgreementService;
    private final RentalAgreementWorkflowService rentalAgreementWorkflowService;
    private final FeatureFlags featureFlags;

    public BookingController(BookingService service, BookingApprovalService approvalService,
                             RentalAgreementService rentalAgreementService,
                             RentalAgreementWorkflowService rentalAgreementWorkflowService,
                             FeatureFlags featureFlags) {
        this.service = service;
        this.approvalService = approvalService;
        this.rentalAgreementService = rentalAgreementService;
        this.rentalAgreementWorkflowService = rentalAgreementWorkflowService;
        this.featureFlags = featureFlags;
    }

    /**
     * Get current user's bookings.
     * RLS-ENFORCED: User can only see their own bookings.
     */
    @Operation(
            summary = "Get my bookings",
            description = "Retrieve all bookings for the currently authenticated user. " +
                    "Includes both past and upcoming bookings."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bookings retrieved successfully",
                    content = @Content(schema = @Schema(implementation = UserBookingResponseDTO.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - valid JWT required"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserBookingResponseDTO>> getMyBookings(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        try {
            List<UserBookingResponseDTO> bookings = service.getMyBookings(principal.getUsername());
            return ResponseEntity.ok(bookings);
        } catch (RuntimeException e) {
            log.error("Error fetching user bookings", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get current user's bookings with pagination.
     * RLS-ENFORCED: User can only see their own bookings.
     */
    @Operation(
            summary = "Get my bookings (paged)",
            description = "Retrieve bookings for the currently authenticated user with pagination support."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bookings page retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - valid JWT required"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/me/paged")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<org.springframework.data.domain.Page<UserBookingResponseDTO>> getMyBookingsPaged(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            org.springframework.data.domain.Page<UserBookingResponseDTO> bookings =
                    service.getMyBookingsPaged(principal.getUsername(), page, size);
            return ResponseEntity.ok(bookings);
        } catch (RuntimeException e) {
            log.error("Error fetching paged user bookings", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a new booking.
     * RLS-ENFORCED: Authenticated users can create bookings (renter extracted from JWT).
     */
    @Operation(
            summary = "Create a booking",
            description = """
                    Create a new booking request for a vehicle.
                    
                    **Business Rules:**
                    - Minimum booking lead time: 2 hours
                    - Minimum trip duration: 1 day
                    - Maximum trip duration: 30 days
                    - Renter must be 21+ years old
                    - Renter must have valid driver's license
                    
                    **Booking States:**
                    - PENDING_APPROVAL → Awaiting host approval
                    - APPROVED → Host approved, ready for trip
                    - REJECTED → Host declined the request
                    """
    )
    @ApiResponses({
                @ApiResponse(responseCode = "201", description = "Booking created successfully",
                    content = @Content(schema = @Schema(implementation = BookingResponseDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request - validation failed",
                    content = @Content(examples = @ExampleObject(
                            value = "{\"error\": \"Trip duration exceeds maximum allowed (30 days)\"}"))),
            @ApiResponse(responseCode = "409", description = "Conflict - car already booked for dates")
    })
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> createBooking(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Booking details",
                    required = true,
                    content = @Content(schema = @Schema(implementation = BookingRequestDTO.class))
            )
            @jakarta.validation.Valid @RequestBody BookingRequestDTO dto,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        // P0 FIX: Let domain exceptions (BookingConflictException, UserOverlapException,
        // ValidationException) propagate to GlobalExceptionHandler for proper HTTP status codes.
        // BookingConflictException → 409 with code=CAR_UNAVAILABLE
        // UserOverlapException → 409 with code=USER_OVERLAP
        // ValidationException → 400 with structured error
        // ResourceNotFoundException → 404
        // PaymentAuthorizationException → 402 with code=PAYMENT_FAILED
        BookingService.BookingCreationResult result = service.createBooking(dto, principal.getUsername());

        // R1-FIX: Handle 3DS/SCA redirect. Booking + payment transaction rows are
        // persisted (not rolled back). Frontend redirects user to the provider's 3DS
        // verification URL. The webhook confirms payment upon successful verification.
        if (result.redirectRequired()) {
            return ResponseEntity.ok(Map.of(
                    "booking", new BookingResponseDTO(result.booking()),
                    "redirectRequired", true,
                    "redirectUrl", result.redirectUrl()
            ));
        }

        java.net.URI location = ServletUriComponentsBuilder
            .fromCurrentRequestUri()
            .path("/{id}")
            .buildAndExpand(result.booking().getId())
            .toUri();
        return ResponseEntity.created(location).body(new BookingResponseDTO(result.booking()));
    }

    /**
     * Get bookings for a specific user by email.
     * RLS-ENFORCED: User can only access their own bookings (verified at service layer).
     */
    @Operation(
            summary = "Get user bookings by email",
            description = "Retrieve bookings for a specific user. User can only access their own bookings.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bookings retrieved"),
            @ApiResponse(responseCode = "403", description = "Forbidden - cannot access other users' bookings")
    })
    @GetMapping("/user/{email}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getUserBookings(
            @Parameter(description = "User email address", example = "user@example.com")
            @PathVariable String email,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Verify the authenticated user can only access their own bookings
            if (!principal.getUsername().equalsIgnoreCase(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to access other users' bookings"));
            }

            return ResponseEntity.ok(service.getBookingsByUser(email));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }

    /**
     * Get all bookings for a specific car.
     * RLS-ENFORCED: SpEL expression delegates to BookingSecurityService.canViewCarBookings
     * which verifies car ownership via CarRepository.findByIdAndOwnerId (W5 fix).
     * Admin bypass is handled both in the SpEL expression and inside canViewCarBookings.
     *
     * <p>G3: Updated @PreAuthorize to exercise W5 defense-in-depth ownership check
     * at the authorization layer, not just the service layer.
     */
    @Operation(
            summary = "Get bookings for a car",
            description = "Retrieve all bookings for a specific car. Only accessible by car owner or admin."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bookings retrieved"),
            @ApiResponse(responseCode = "403", description = "Forbidden - not car owner")
    })
    @GetMapping("/car/{carId}")
    @PreAuthorize("@bookingSecurity.canViewCarBookings(#carId, authentication.principal.id) or hasRole('ADMIN')")
    public ResponseEntity<List<BookingResponseDTO>> getBookingsForCar(
            @Parameter(description = "Car ID", example = "123")
            @PathVariable Long carId) {
        return ResponseEntity.ok(service.getBookingsForCar(carId));
    }

    /**
     * Get public-safe booking slots for a specific car (calendar availability).
     */
    @Operation(
            summary = "Get public booking slots",
            description = """
                    Get booked date ranges for a car (public access).
                    
                    **Use case:** Calendar UI to show unavailable dates.
                    
                    **Returns:** Only date ranges, no personal information.
                    """,
            security = {}  // No auth required
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking slots retrieved",
                    content = @Content(schema = @Schema(implementation = org.example.rentoza.booking.dto.BookingSlotDTO.class)))
    })
    @GetMapping("/car/{carId}/public")
    @PreAuthorize("permitAll()")
    public ResponseEntity<List<org.example.rentoza.booking.dto.BookingSlotDTO>> getPublicBookingsForCar(
            @Parameter(description = "Car ID", example = "123")
            @PathVariable Long carId) {
        try {
            List<org.example.rentoza.booking.dto.BookingSlotDTO> slots = service.getPublicBookedSlots(carId);
            return ResponseEntity.ok(slots);
        } catch (RuntimeException e) {
            log.error("Error fetching public booking slots for car {}", carId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get booking by ID - with ownership verification.
     * RLS-ENFORCED: Only renter, car owner, or admin can view booking.
     * Uses BookingSecurityService for SpEL-based access control.
     */
    @Operation(
            summary = "Get booking by ID",
            description = "Retrieve booking details. Accessible by booking renter, car owner, or admin."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking found",
                    content = @Content(schema = @Schema(implementation = BookingResponseDTO.class))),
            @ApiResponse(responseCode = "404", description = "Booking not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - not authorized to view this booking")
    })
    @GetMapping("/{id}")
    @PreAuthorize("@bookingSecurity.canAccessBooking(#id, authentication.principal.id) or hasRole('ADMIN')")
    public ResponseEntity<?> getBookingById(
            @Parameter(description = "Booking ID", example = "456")
            @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        Booking booking = service.getBookingById(id);
        BookingResponseDTO dto = new BookingResponseDTO(booking);
        dto.setAgreementSummary(rentalAgreementWorkflowService.buildSummary(
                booking,
                rentalAgreementService.getOrGenerateAgreement(id).orElse(null),
                principal.id()
        ));
        return ResponseEntity.ok(dto);
    }

    /**
     * Get conversation-safe booking summary for chat enrichment.
     */
    @Operation(
            summary = "Get booking for chat context",
            description = "Get minimal booking info for chat enrichment. Used by chat microservice."
    )
    @GetMapping("/{id}/conversation-view")
    @PreAuthorize("hasAuthority('INTERNAL_SERVICE') or @bookingSecurity.canAccessBooking(#id, authentication?.principal?.id)")
    public ResponseEntity<?> getConversationView(
            @Parameter(description = "Booking ID")
            @PathVariable Long id,
            @Parameter(description = "User ID for service-to-service calls", hidden = true)
            @RequestHeader(value = "X-Act-As-User-Id", required = false) String actAsUserIdHeader
    ) {
        log.debug("[ConversationView] Request for bookingId={}", id);
        
        try {
            // Determine user ID based on authentication type
            Long actAsUserId;
            
            // Check if request is from INTERNAL_SERVICE
            boolean isInternalService = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication()
                    .getAuthorities()
                    .stream()
                    .anyMatch(auth -> "INTERNAL_SERVICE".equals(auth.getAuthority()));
            
            if (isInternalService) {
                // INTERNAL_SERVICE must provide X-Act-As-User-Id header
                if (actAsUserIdHeader == null || actAsUserIdHeader.isBlank()) {
                    log.warn("INTERNAL_SERVICE request missing X-Act-As-User-Id header for booking {}", id);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "X-Act-As-User-Id header required for service-to-service calls"));
                }
                
                try {
                    actAsUserId = Long.parseLong(actAsUserIdHeader);
                    log.debug("[ConversationView] INTERNAL_SERVICE request: bookingId={}, actAsUserId={}", id, actAsUserId);
                } catch (NumberFormatException e) {
                    log.warn("Invalid X-Act-As-User-Id header for booking {}", id);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "X-Act-As-User-Id must be a valid user ID"));
                }
            } else {
                // Direct JWT: Use authenticated user's ID (ignore X-Act-As-User-Id header)
                actAsUserId = org.springframework.security.core.context.SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getPrincipal() instanceof org.example.rentoza.security.JwtUserPrincipal principal
                        ? principal.id()
                        : null;
                
                if (actAsUserId == null) {
                    log.warn("Direct JWT request missing principal for booking {}", id);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("error", "Authentication required"));
                }
                
                log.debug("[ConversationView] Direct JWT request: bookingId={}, userId={}", id, actAsUserId);
            }
            
            // Call service method with actAsUserId for RLS enforcement
            org.example.rentoza.booking.dto.BookingConversationDTO dto = service.getConversationView(id, actAsUserId);
            return ResponseEntity.ok(dto);
            
        } catch (ResourceNotFoundException e) {
            log.warn("Booking not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Booking not found", "message", e.getMessage()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            log.warn("Access denied for booking {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error in conversation-view for booking {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Debug endpoint - list all booking IDs.
     * WI-13: Defense-in-depth — service layer checks isAdmin(), but @PreAuthorize
     * provides an additional method-level security gate at the controller boundary.
     */
    @GetMapping("/debug/ids")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getAllBookingIds() {
        List<Long> ids = service.getAllBookingIds();
        return ResponseEntity.ok(Map.of(
                "count", ids.size(),
                "bookingIds", ids
        ));
    }

    // ==================== CANCELLATION POLICY MIGRATION (Phase 2) ====================

    /**
     * Preview cancellation consequences before committing.
     * 
     * <p>Returns a preview of what would happen if the booking was cancelled,
     * including penalty amounts, refunds, and applicable rules. Does NOT
     * actually cancel the booking.
     * 
     * <p>RLS-ENFORCED: Only renter or host can preview cancellation.
     * 
     * @param id Booking ID to preview cancellation for
     * @return Preview DTO with financial consequences
     */
    @GetMapping("/{id}/cancellation-preview")
    @PreAuthorize("@bookingSecurity.canModifyBooking(#id, authentication.principal.id) or hasRole('ADMIN')")
    public ResponseEntity<?> getCancellationPreview(@PathVariable Long id) {
        try {
            CancellationPreviewDTO preview = service.getCancellationPreview(id);
            return ResponseEntity.ok(preview);
        } catch (IllegalStateException e) {
            // Booking in non-cancellable state
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Cannot cancel",
                "message", e.getMessage()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cancel a booking with Turo-style policy enforcement.
     * 
     * <p>This endpoint:
     * <ul>
     *   <li>Calculates penalties/refunds based on timing and trip duration</li>
     *   <li>Creates an immutable CancellationRecord audit entry</li>
     *   <li>Applies host penalty escalation (RSD 5,500 → 11,000 → 16,500)</li>
     *   <li>Triggers 7-day suspension for 3rd+ host cancellation</li>
     *   <li>Sends notifications to both parties with financial details</li>
     * </ul>
     * 
     * <p><b>Guest Cancellation Rules:</b>
     * <ul>
     *   <li>>24h before trip: Full refund</li>
     *   <li>&lt;1h since booking (remorse): Full refund</li>
     *   <li>&lt;24h, short trip (≤2 days): 1 day penalty</li>
     *   <li>&lt;24h, long trip (>2 days): 50% penalty</li>
     * </ul>
     * 
     * <p><b>Host Cancellation:</b> Guest always gets full refund, host pays tiered penalty.
     * 
     * <p>RLS-ENFORCED: Only renter or host can cancel their booking.
     * 
     * @param id Booking ID to cancel
     * @param request Cancellation reason and optional notes
     * @return Cancellation result with financial details
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("@bookingSecurity.canModifyBooking(#id, authentication.principal.id) or hasRole('ADMIN')")
    public ResponseEntity<?> cancelBookingWithPolicy(
            @PathVariable Long id,
            @Valid @RequestBody CancellationRequestDTO request
    ) {
        try {
            CancellationResultDTO result = service.cancelBookingWithPolicy(id, request);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            // Booking in non-cancellable state or already cancelled
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Cannot cancel",
                "message", e.getMessage()
            ));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Unauthorized",
                "message", e.getMessage()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Phase 2.3: Validate booking availability without creating the booking.
     * RLS-ENFORCED: Authenticated users can check availability.
     * Returns 409 Conflict if dates are not available, 200 OK if available.
     * 
     * @param dto Booking request with car ID and date range
     * @return 200 with {available: true} if dates are free, 409 with error if conflict
     */
    @PostMapping("/validate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> validateBooking(@RequestBody BookingRequestDTO dto) {
        try {
            boolean available = service.checkAvailability(dto);
            
            if (!available) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Conflict",
                    "message", "Selected dates are no longer available. Please choose different dates.",
                    "available", false
                ));
            }
            
            return ResponseEntity.ok(Map.of("available", true));
        } catch (RuntimeException e) {
            log.error("Error validating booking availability", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ========== HOST APPROVAL WORKFLOW ENDPOINTS ==========

    /**
     * Approve a pending booking request.
     * RLS-ENFORCED: Only car owner can approve bookings for their cars.
     * Transitions booking from PENDING_APPROVAL to ACTIVE.
     * 
     * @param id Booking ID to approve
     * @return Approved booking response
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN') and @bookingSecurity.canDecide(#id, authentication?.principal?.id)")
    public ResponseEntity<?> approveBooking(@PathVariable Long id) {
        try {
            Long ownerId = getAuthenticatedUserId();
            log.info("[BookingController] Received approve request for bookingId={} from ownerId={}", id, ownerId);

            BookingResponseDTO approvedBooking = approvalService.approveBooking(id, ownerId);
            return ResponseEntity.ok(approvedBooking);

        } catch (org.example.rentoza.exception.ResourceNotFoundException e) {
            log.warn("Booking not found for approval: id={}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Booking not found"));

        } catch (IllegalStateException e) {
            log.warn("Invalid state for approval: bookingId={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));

        } catch (org.springframework.security.access.AccessDeniedException e) {
            log.warn("Unauthorized approval attempt: bookingId={}", id);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You are not authorized to approve this booking"));

        } catch (org.example.rentoza.exception.BookingConflictException e) {
            log.warn("Booking conflict during approval: bookingId={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            log.error("Unexpected error approving booking {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred"));
        }
    }

    /**
     * Decline a pending booking request with optional reason.
     * RLS-ENFORCED: Only car owner can decline bookings for their cars.
     * Transitions booking from PENDING_APPROVAL to DECLINED.
     * 
     * @param id Booking ID to decline
     * @param reason Optional decline reason
     * @return Declined booking response
     */
    @PutMapping("/{id}/decline")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN') and @bookingSecurity.canDecide(#id, authentication?.principal?.id)")
    public ResponseEntity<?> declineBooking(
            @PathVariable Long id,
            @RequestParam(required = false) String reason
    ) {
        try {
            Long ownerId = getAuthenticatedUserId();
            log.info("[BookingController] Received decline request for bookingId={} from ownerId={}", id, ownerId);

            BookingResponseDTO declinedBooking = approvalService.declineBooking(id, ownerId, reason);
            return ResponseEntity.ok(declinedBooking);

        } catch (org.example.rentoza.exception.ResourceNotFoundException e) {
            log.warn("Booking not found for decline: id={}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Booking not found"));

        } catch (IllegalStateException e) {
            log.warn("Invalid state for decline: bookingId={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));

        } catch (org.springframework.security.access.AccessDeniedException e) {
            log.warn("Unauthorized decline attempt: bookingId={}", id);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You are not authorized to decline this booking"));

        } catch (Exception e) {
            log.error("Unexpected error declining booking {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred"));
        }
    }

    /**
     * Get all pending approval requests for the authenticated owner's cars.
     * RLS-ENFORCED: Only returns bookings for cars owned by the authenticated user.
     * 
     * @return List of pending booking requests
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> getPendingRequests() {
        try {
            Long ownerId = getAuthenticatedUserId();

            List<Booking> pendingBookings = approvalService.getPendingRequests(ownerId);
            List<BookingResponseDTO> response = pendingBookings.stream()
                    .map(BookingResponseDTO::new)
                    .toList();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching pending requests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch pending requests"));
        }
    }

    /**
     * Get detailed booking information.
     * RLS-ENFORCED: Service layer checks if user is renter/owner/admin.
     */
    @GetMapping("/{id}/details")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<org.example.rentoza.booking.dto.BookingDetailsDTO> getBookingDetails(@PathVariable Long id) {
        return ResponseEntity.ok(service.getBookingDetails(id));
    }

    /**
     * Get guest preview for a booking.
     * RLS-ENFORCED: Only the car owner can view the guest preview.
     * Returns a restricted DTO with no contact info.
     * Response is not cached to protect PII.
     * 
     * @param id Booking ID
     * @return GuestBookingPreviewDTO
     */
    @GetMapping("/{id}/guest-preview")
    @PreAuthorize("@bookingSecurity.isOwner(#id, authentication.principal.id)")
    public ResponseEntity<org.example.rentoza.dto.GuestBookingPreviewDTO> getGuestPreview(@PathVariable Long id) {
        Long ownerId = getAuthenticatedUserId();
        org.example.rentoza.dto.GuestBookingPreviewDTO preview = service.getGuestPreview(id, ownerId);
        
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore().mustRevalidate())
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(preview);
    }

    // ========== RENTAL AGREEMENT ENDPOINTS ==========

    /**
     * Get the rental agreement for a booking.
     * Accessible by booking renter, booking owner, or admin.
     */
    @GetMapping("/{bookingId}/agreement")
    @PreAuthorize("isAuthenticated() and @bookingSecurity.canAccessBooking(#bookingId, authentication?.principal?.id)")
    public ResponseEntity<?> getAgreement(@PathVariable Long bookingId) {
        try {
            var agreement = rentalAgreementService.getOrGenerateAgreement(bookingId);
            if (agreement.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiErrorResponse.notFound(
                                "Ugovor o iznajmljivanju nije pronađen za ovu rezervaciju.",
                                RequestLoggingContextFilter.getCurrentRequestId(),
                                "/api/bookings/" + bookingId + "/agreement"
                        ));
            }
                    return ResponseEntity.ok(RentalAgreementDTO.from(
                        agreement.get(),
                        featureFlags.isRentalAgreementCheckinEnforced()
                    ));
        } catch (Exception e) {
            log.error("Error fetching agreement for booking {}", bookingId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiErrorResponse.internalError(
                            "Došlo je do greške pri učitavanju ugovora o iznajmljivanju.",
                            RequestLoggingContextFilter.getCurrentRequestId(),
                            "/api/bookings/" + bookingId + "/agreement"
                    ));
        }
    }

    /**
     * Accept the rental agreement for a booking.
     * Only the booking renter or owner can accept (each for their own role).
     */
    @PostMapping("/{bookingId}/agreement/accept")
    @PreAuthorize("isAuthenticated() and @bookingSecurity.canAccessBooking(#bookingId, authentication?.principal?.id)")
    public ResponseEntity<?> acceptAgreement(
            @PathVariable Long bookingId,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            Long userId = getAuthenticatedUserId();
            String ip = resolveClientIp(request);
            String userAgent = request.getHeader("User-Agent");

            // Determine if user is owner or renter and route accordingly
            var agreement = rentalAgreementService.getAgreementForBooking(bookingId);
            if (agreement.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.notFound(
                        "Ugovor o iznajmljivanju nije pronađen za ovu rezervaciju.",
                        RequestLoggingContextFilter.getCurrentRequestId(),
                        "/api/bookings/" + bookingId + "/agreement/accept"
                    ));
            }

            RentalAgreement updated;
            if (agreement.get().getOwnerUserId().equals(userId)) {
                updated = rentalAgreementService.acceptAsOwner(bookingId, userId, ip, userAgent);
            } else if (agreement.get().getRenterUserId().equals(userId)) {
                updated = rentalAgreementService.acceptAsRenter(bookingId, userId, ip, userAgent);
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiErrorResponse.forbidden(
                        "Samo učesnici rezervacije mogu prihvatiti ugovor o iznajmljivanju.",
                        RequestLoggingContextFilter.getCurrentRequestId(),
                        "/api/bookings/" + bookingId + "/agreement/accept"
                    ));
            }

        return ResponseEntity.ok(RentalAgreementDTO.from(
            updated,
            featureFlags.isRentalAgreementCheckinEnforced()
        ));

        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.forbidden(
                    e.getMessage(),
                    RequestLoggingContextFilter.getCurrentRequestId(),
                    "/api/bookings/" + bookingId + "/agreement/accept"
                ));
        } catch (org.example.rentoza.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.notFound(
                    e.getMessage(),
                    RequestLoggingContextFilter.getCurrentRequestId(),
                    "/api/bookings/" + bookingId + "/agreement/accept"
                ));
        } catch (RentalAgreementConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.conflict(
                    e.getCode(),
                    e.getMessage(),
                    null,
                    RequestLoggingContextFilter.getCurrentRequestId(),
                    "/api/bookings/" + bookingId + "/agreement/accept"
                ));
        } catch (org.springframework.dao.ConcurrencyFailureException |
                 jakarta.persistence.OptimisticLockException |
                 jakarta.persistence.PessimisticLockException |
                 jakarta.persistence.LockTimeoutException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.conflict(
                    "RENTAL_AGREEMENT_STATE_CHANGED",
                    "Status ugovora o iznajmljivanju je upravo promenjen. Osvežite stranicu i pokušajte ponovo.",
                    null,
                    RequestLoggingContextFilter.getCurrentRequestId(),
                    "/api/bookings/" + bookingId + "/agreement/accept"
                ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.conflict(
                    "RENTAL_AGREEMENT_CONFLICT",
                    e.getMessage(),
                    null,
                    RequestLoggingContextFilter.getCurrentRequestId(),
                    "/api/bookings/" + bookingId + "/agreement/accept"
                ));
        } catch (Exception e) {
            log.error("Error accepting agreement for booking {}", bookingId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.internalError(
                    "Došlo je do greške pri prihvatanju ugovora o iznajmljivanju.",
                    RequestLoggingContextFilter.getCurrentRequestId(),
                    "/api/bookings/" + bookingId + "/agreement/accept"
                ));
        }
    }

    private String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Helper to safely extract user ID from security context.
     * Handles JwtUserPrincipal correctly.
     */
    private Long getAuthenticatedUserId() {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        
        if (principal instanceof org.example.rentoza.security.JwtUserPrincipal jwtPrincipal) {
            return jwtPrincipal.id();
        }
        
        // Fallback or error if principal is not what we expect
        // This prevents ClassCastException or NumberFormatException
        log.error("Unexpected principal type: {}", principal.getClass().getName());
        throw new IllegalStateException("Unable to determine authenticated user ID");
    }
}