package org.example.rentoza.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminCarDto;
import org.example.rentoza.admin.dto.AdminCarReviewDetailDto;
import org.example.rentoza.admin.dto.CarApprovalRequestDto;
import org.example.rentoza.admin.dto.CarSuspensionRequestDto;
import org.example.rentoza.admin.service.AdminCarService;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.idempotency.IdempotencyService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin Car Management Controller.
 * 
 * <p>Provides endpoints for car moderation with enterprise-grade features:
 * <ul>
 *   <li>Rate limiting via Resilience4j</li>
 *   <li>Idempotency support via X-Idempotency-Key header</li>
 *   <li>Proper DTO validation</li>
 *   <li>Structured logging with MDC context</li>
 * </ul>
 * 
 * @see AdminCarService
 */
@RestController
@RequestMapping("/api/admin/cars")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminCarController {
    
    private final AdminCarService carService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final HateoasAssembler hateoasAssembler;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    
    /**
     * List all cars with pagination.
     * 
     * @param pageable Pagination parameters
     * @return Paginated car list with HATEOAS links
     */
    @GetMapping
    public PagedModel<EntityModel<AdminCarDto>> listCars(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) 
            Pageable pageable) {
        
        Page<AdminCarDto> cars = carService.listCars(pageable);
        
        log.debug("Admin {} listed cars, page {}/{}", 
            currentUser.id(), pageable.getPageNumber(), cars.getTotalPages());
        
        return hateoasAssembler.toModel(cars);
    }
    
    /**
     * Get car detail.
     * 
     * @param id Car ID
     * @return Car detail with owner info
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminCarDto> getCarDetail(@PathVariable @Positive Long id) {
        AdminCarDto car = carService.getCarDetail(id);
        
        log.debug("Admin {} viewed car detail for carId={}", currentUser.id(), id);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Get car review details for document verification workflow.
     * 
     * Includes:
     * - Car overview with photos
     * - All documents with verification status
     * - Owner identity verification status
     * - Pre-calculated approval state
     * 
     * @param id Car ID
     * @return Car review details with documents
     */
    @GetMapping("/{id}/review-detail")
    public ResponseEntity<AdminCarReviewDetailDto> getCarReviewDetail(@PathVariable @Positive Long id) {
        AdminCarReviewDetailDto car = carService.getCarReviewDetail(id);
        
        log.debug("Admin {} requested car review details for carId={}", currentUser.id(), id);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * List cars pending approval.
     * 
     * @return Pending approval list
     */
    @GetMapping("/pending")
    public ResponseEntity<List<AdminCarDto>> getPendingCars() {
        List<AdminCarDto> pending = carService.getPendingCars();
        
        log.debug("Admin {} listed pending cars, count={}", currentUser.id(), pending.size());
        
        return ResponseEntity.ok(pending);
    }
    
    /**
     * Approve a car listing.
     * 
     * <p>Features:
     * <ul>
     *   <li>Rate limited: 100 approvals per minute max</li>
     *   <li>Idempotency: Supply X-Idempotency-Key header for safe retries</li>
     * </ul>
     * 
     * @param id Car ID
     * @param idempotencyKey Optional idempotency key for safe retries
     * @return Updated car
     */
    @PostMapping("/{id}/approve")
    @RateLimiter(name = "admin-approval")
    public ResponseEntity<AdminCarDto> approveCar(
            @PathVariable @Positive Long id,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        
        Long adminId = currentUser.id();
        
        // Check idempotency - return cached response if already processed
        if (idempotencyKey != null) {
            var cached = idempotencyService.checkIdempotency(idempotencyKey, adminId);

            // M-10 FIX: Filter by operation type
            if (cached.isPresent() && !"CAR_APPROVE".equals(cached.get().getOperationType())) {
                log.warn("Idempotency key {} was used for a different operation: {}",
                         idempotencyKey.substring(0, 8), cached.get().getOperationType());
                cached = Optional.empty();
            }

            if (cached.isPresent()) {
                var result = cached.get();
                if (result.getStatus() == IdempotencyService.IdempotencyStatus.COMPLETED) {
                    log.info("Returning cached approval response for key={}", idempotencyKey.substring(0, 8));
                    if (result.getResponseBody() != null) {
                        try {
                            return ResponseEntity.status(result.getHttpStatus())
                                .body(objectMapper.readValue(result.getResponseBody(), AdminCarDto.class));
                        } catch (Exception e) {
                            log.warn("Failed to deserialize cached response body, returning 200 OK", e);
                        }
                    }
                    return ResponseEntity.ok().build();
                }
                if (result.getStatus() == IdempotencyService.IdempotencyStatus.PROCESSING) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(null);
                }
            }

            // Try to acquire lock for processing
            if (!idempotencyService.markProcessing(idempotencyKey, adminId, "CAR_APPROVE")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }
        
        try {
            User admin = getAdmin();
            AdminCarDto car = carService.approveCar(id, admin);
            
            // Store successful result for idempotency
            if (idempotencyKey != null) {
                idempotencyService.storeSuccess(idempotencyKey, adminId, HttpStatus.OK, car);
            }
            
            return ResponseEntity.ok(car);
        } catch (Exception e) {
            // Remove idempotency lock on transient errors
            if (idempotencyKey != null) {
                idempotencyService.remove(idempotencyKey, adminId);
            }
            throw e;
        }
    }
    
    /**
     * Reject a car listing.
     * 
     * <p>Features:
     * <ul>
     *   <li>Rate limited: 100 rejections per minute max</li>
     *   <li>Null validation: Reason is required (min 10 chars)</li>
     * </ul>
     * 
     * @param id Car ID
     * @param request Rejection details with reason
     * @return Updated car
     */
    @PostMapping("/{id}/reject")
    @RateLimiter(name = "admin-approval")
    public ResponseEntity<AdminCarDto> rejectCar(
            @PathVariable @Positive Long id,
            @Valid @RequestBody CarApprovalRequestDto request) {
        
        // Explicit null check (defense in depth - in addition to DTO validation)
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        
        User admin = getAdmin();
        AdminCarDto car = carService.rejectCar(id, request.getReason(), admin);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Suspend an active car listing.
     * 
     * <p>Features:
     * <ul>
     *   <li>Rate limited: 100 suspensions per minute max</li>
     *   <li>Proper DTO validation with required reason</li>
     * </ul>
     * 
     * @param id Car ID
     * @param request Suspension details with reason
     * @return Updated car
     */
    @PostMapping("/{id}/suspend")
    @RateLimiter(name = "admin-approval")
    public ResponseEntity<AdminCarDto> suspendCar(
            @PathVariable @Positive Long id,
            @Valid @RequestBody CarSuspensionRequestDto request) {
        
        User admin = getAdmin();
        AdminCarDto car = carService.suspendCar(id, request.getReason(), admin);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Reactivate a suspended car.
     * 
     * @param id Car ID
     * @return Updated car
     */
    @PostMapping("/{id}/reactivate")
    @RateLimiter(name = "admin-approval")
    public ResponseEntity<AdminCarDto> reactivateCar(@PathVariable @Positive Long id) {
        User admin = getAdmin();
        
        AdminCarDto car = carService.reactivateCar(id, admin);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Get current admin user entity.
     */
    private User getAdmin() {
        return userRepository.findById(currentUser.id())
            .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
    }
}

