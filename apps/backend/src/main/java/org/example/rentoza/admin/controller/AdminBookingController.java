package org.example.rentoza.admin.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminBookingDto;
import org.example.rentoza.admin.dto.ForceCompleteBookingRequest;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.payment.ChargeLifecycleStatus;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin Booking Management Controller.
 * 
 * <p>Provides:
 * <ul>
 *   <li>Paginated listing with filters (status, date range, search)</li>
 *   <li>Force-complete with payment guardrails + audit trail</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminBookingController {
    
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;
    private final CurrentUser currentUser;
    private final AdminAuditService auditService;
    
    private static final int MAX_PAGE_SIZE = 100;
    
    /**
     * List all bookings with filters.
     * 
     * @param status Filter by booking status
     * @param search Search by renter/owner name or email
     * @param page Page number (0-indexed)
     * @param size Page size (max 100)
     * @return Paginated booking list
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Page<AdminBookingDto>> listBookings(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        
        log.debug("Admin {} listing bookings: status={}, search={}, page={}", 
                  currentUser.id(), status, search, page);
        
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Specification<Booking> spec = buildSpecification(status, search);
        Page<AdminBookingDto> result = bookingRepo.findAll(spec, pageable)
            .map(AdminBookingDto::fromEntity);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * Get booking detail.
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<AdminBookingDto> getBookingDetail(@PathVariable @Positive Long id) {
        Booking booking = bookingRepo.findByIdWithRelations(id)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));
        return ResponseEntity.ok(AdminBookingDto.fromEntity(booking));
    }
    
    /**
     * Force-complete a booking.
     * 
     * <p>Guardrails:
     * <ul>
     *   <li>Cannot force-complete a booking already in a terminal state</li>
     *   <li>Cannot force-complete if payment is still AUTHORIZED (funds held)</li>
     *   <li>Creates an immutable audit trail entry</li>
     * </ul>
     */
    @PostMapping("/{id}/force-complete")
    @Transactional
    public ResponseEntity<?> forceComplete(
            @PathVariable @Positive Long id,
            @Valid @RequestBody ForceCompleteBookingRequest request) {

        log.info("Admin {} force-completing booking {}: {}", currentUser.id(), id, request.getReason());
        
        User admin = userRepo.findById(currentUser.id())
            .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        
        // findByIdWithLock acquires a pessimistic write lock and JOIN FETCHes car/renter/owner
        // in one query, preventing concurrent force-completes and avoiding N+1 on the return DTO.
        Booking booking = bookingRepo.findByIdWithLock(id)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));
        
        // Guard: already terminal
        if (booking.getStatus().isTerminal()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "ALREADY_TERMINAL",
                "message", "Booking is already in terminal state: " + booking.getStatus()
            ));
        }
        
        // Guard: payment authorization still held by provider — use isTerminal() for all states
        ChargeLifecycleStatus chargeStatus = booking.getChargeLifecycleStatus();
        if (chargeStatus != null && !chargeStatus.isTerminal()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "PAYMENT_IN_PROGRESS",
                "message", "Cannot force-complete: payment is in non-terminal state " + chargeStatus +
                           ". Wait for payment to settle or resolve manually.",
                "chargeLifecycleStatus", chargeStatus.name()
            ));
        }
        
        String beforeState = booking.getStatus().name();
        
        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepo.save(booking);
        
        // Audit trail
        auditService.logAction(
            admin,
            AdminAction.BOOKING_FORCE_COMPLETED,
            ResourceType.BOOKING,
            booking.getId(),
            beforeState,
            BookingStatus.COMPLETED.name(),
            request.getReason()
        );
        
        log.info("Booking {} force-completed by admin {} (was: {})", id, currentUser.id(), beforeState);
        
        return ResponseEntity.ok(AdminBookingDto.fromEntity(booking));
    }
    
    // ==================== PRIVATE HELPERS ====================
    
    private Specification<Booking> buildSpecification(BookingStatus status, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            
            if (search != null && !search.isBlank()) {
                String pattern = "%" + escapeLikePattern(search.toLowerCase()) + "%";
                Predicate renterName = cb.like(
                    cb.lower(cb.concat(root.join("renter").get("firstName"), 
                        cb.concat(cb.literal(" "), root.join("renter").get("lastName")))), pattern);
                Predicate renterEmail = cb.like(cb.lower(root.join("renter").get("email")), pattern);
                predicates.add(cb.or(renterName, renterEmail));
            }
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}
