package org.example.rentoza.admin.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminCarDto;
import org.example.rentoza.admin.dto.AdminCarReviewDetailDto;
import org.example.rentoza.admin.dto.CarApprovalRequestDto;
import org.example.rentoza.admin.dto.DocumentReviewDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.CarDocument;
import org.example.rentoza.car.CarDocumentService;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin Car Management Service.
 * 
 * <p>Provides car moderation capabilities with enterprise-grade features:
 * <ul>
 *   <li>List all cars with admin view</li>
 *   <li>Approve car listings with metrics tracking</li>
 *   <li>Reject car listings with reason and audit</li>
 *   <li>Suspend car listings with MDC context</li>
 * </ul>
 * 
 * <p><b>Observability:</b>
 * <ul>
 *   <li>Micrometer metrics: car.approvals, car.rejections, car.suspensions</li>
 *   <li>MDC logging: carId, adminId, action for correlation</li>
 * </ul>
 * 
 * @see AdminCarDto
 * @see CarApprovalRequestDto
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AdminCarService {
    
    private final CarRepository carRepo;
    private final CarDocumentService documentService;
    private final AdminAuditService auditService;
    private final MeterRegistry meterRegistry;
    
    /**
     * List all cars with pagination.
     * 
     * @param pageable Pagination parameters
     * @return Paginated car list
     */
    @Transactional(readOnly = true)
    public Page<AdminCarDto> listCars(Pageable pageable) {
        Page<Car> cars = carRepo.findAll(pageable);
        return cars.map(AdminCarDto::fromEntity);
    }
    
    /**
     * Get car detail by ID.
     * 
     * @param carId Car ID
     * @return Car detail DTO
     * @throws ResourceNotFoundException if car not found
     */
    @Transactional(readOnly = true)
    public AdminCarDto getCarDetail(Long carId) {
        Car car = carRepo.findWithDetailsById(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        AdminCarDto dto = AdminCarDto.fromEntity(car);
        
        // Add booking count
        dto.setTotalBookings(car.getBookings() != null ? car.getBookings().size() : 0);
        
        return dto;
    }
    
    /**
     * List cars pending approval (currently unavailable cars added recently).
     * 
     * <p><b>NOTE:</b> This is a simplified implementation using `available=false`.
     * Full approval_status will be added in future.
     * 
     * @param pageable Pagination
     * @return Pending cars
     */
    @Transactional(readOnly = true)
    public List<AdminCarDto> getPendingCars() {
        List<Car> pendingCars = carRepo.findByApprovalStatus(org.example.rentoza.car.ApprovalStatus.PENDING.name());
        return pendingCars.stream()
                .map(AdminCarDto::fromEntity)
                .toList();
    }
    
    /**
     * Approve a car listing.
     * 
     * <p>Makes the car visible in search results.
     * <p>Includes observability: Micrometer timer + MDC context.
     * 
     * @param carId Car ID
     * @param admin Admin performing approval
     * @return Updated car DTO
     */
    public AdminCarDto approveCar(Long carId, User admin) {
        Timer.Sample timer = Timer.start(meterRegistry);
        
        // Set MDC context for structured logging
        MDC.put("carId", carId.toString());
        MDC.put("adminId", admin.getId().toString());
        MDC.put("action", "CAR_APPROVED");
        
        try {
            Car car = carRepo.findWithDetailsById(carId)
                .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
            
            // Capture before state
            String beforeState = auditService.toJson(AdminCarDto.fromEntity(car));
            
            // Approve (make available)
            car.setApprovalStatus(org.example.rentoza.car.ApprovalStatus.APPROVED);
            car.setApprovedBy(admin);
            car.setApprovedAt(java.time.Instant.now());
            car.setAvailable(true);
            car.setRejectionReason(null);
            
            Car saved = carRepo.save(car);
            
            // Capture after state
            String afterState = auditService.toJson(AdminCarDto.fromEntity(saved));
            
            // Audit log
            auditService.logAction(
                admin,
                AdminAction.CAR_APPROVED,
                ResourceType.CAR,
                carId,
                beforeState,
                afterState,
                "Car listing approved"
            );
            
            // Increment success counter
            meterRegistry.counter("car.approvals", "status", "success").increment();
            
            log.info("Car approval completed successfully");
            
            return AdminCarDto.fromEntity(saved);
        } catch (Exception e) {
            meterRegistry.counter("car.approvals", "status", "failed").increment();
            throw e;
        } finally {
            timer.stop(Timer.builder("car.approval.duration")
                    .tag("action", "approve")
                    .register(meterRegistry));
            MDC.clear();
        }
    }
    
    /**
     * Reject a car listing.
     * 
     * <p>Keeps the car invisible in search results.
     * 
     * @param carId Car ID
     * @param reason Rejection reason
     * @param admin Admin performing rejection
     * @return Updated car DTO
     */
    public AdminCarDto rejectCar(Long carId, String reason, User admin) {
        Car car = carRepo.findWithDetailsById(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        
        // Capture before state
        String beforeState = auditService.toJson(AdminCarDto.fromEntity(car));
        
        // Reject (keep unavailable)
        car.setApprovalStatus(org.example.rentoza.car.ApprovalStatus.REJECTED);
        car.setApprovedBy(admin);
        car.setApprovedAt(java.time.Instant.now());
        car.setRejectionReason(reason.trim());
        car.setAvailable(false);
        
        Car saved = carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(saved));
        
        // Audit log with reason
        auditService.logAction(
            admin,
            AdminAction.CAR_REJECTED,
            ResourceType.CAR,
            carId,
            beforeState,
            afterState,
            reason
        );
        
        log.info("Car {} rejected by admin {}. Reason: {}", carId, admin.getId(), reason);
        
        return AdminCarDto.fromEntity(saved);
    }
    
    /**
     * Suspend an active car listing.
     * 
     * <p>Removes car from search results.
     * Usually for policy violations.
     * 
     * @param carId Car ID
     * @param reason Suspension reason
     * @param admin Admin performing suspension
     * @return Updated car DTO
     */
    public AdminCarDto suspendCar(Long carId, String reason, User admin) {
        Car car = carRepo.findWithDetailsById(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Suspension reason is required");
        }
        
        // Capture before state
        String beforeState = auditService.toJson(AdminCarDto.fromEntity(car));
        
        // Suspend (make unavailable)
        car.setApprovalStatus(org.example.rentoza.car.ApprovalStatus.SUSPENDED);
        car.setRejectionReason(reason.trim());
        car.setAvailable(false);
        
        Car saved = carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(saved));
        
        // Audit log with reason
        auditService.logAction(
            admin,
            org.example.rentoza.admin.entity.AdminAction.CAR_SUSPENDED,
            ResourceType.CAR,
            carId,
            beforeState,
            afterState,
            "Suspended: " + reason
        );
        
        log.info("Car {} suspended by admin {}. Reason: {}", carId, admin.getId(), reason);
        
        return AdminCarDto.fromEntity(saved);
    }
    
    /**
     * Reactivate a suspended car.
     * 
     * @param carId Car ID
     * @param admin Admin performing reactivation
     * @return Updated car DTO
     */
    public AdminCarDto reactivateCar(Long carId, User admin) {
        Car car = carRepo.findWithDetailsById(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        // Capture before state
        String beforeState = auditService.toJson(AdminCarDto.fromEntity(car));
        
        // Reactivate
        car.setApprovalStatus(org.example.rentoza.car.ApprovalStatus.APPROVED);
        car.setRejectionReason(null);
        car.setAvailable(true);
        
        Car saved = carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(saved));
        
        // Audit log
        auditService.logAction(
            admin,
            org.example.rentoza.admin.entity.AdminAction.CAR_REACTIVATED,
            ResourceType.CAR,
            carId,
            beforeState,
            afterState,
            "Car listing reactivated"
        );
        
        log.info("Car {} reactivated by admin {}", carId, admin.getId());
        
        return AdminCarDto.fromEntity(saved);
    }
    
    /**
     * Count available cars.
     */
    @Transactional(readOnly = true)
    public Long countAvailableCars() {
        return carRepo.countAvailableCars();
    }
    
    /**
     * Get car review details for admin document verification workflow.
     * 
     * Includes:
     * - Car overview with photos
     * - All documents with verification status
     * - Owner identity verification status
     * - Pre-calculated approval state
     * 
     * @param carId Car ID
     * @return AdminCarReviewDetailDto with full review data
     * @throws ResourceNotFoundException if car not found
     */
    @Transactional(readOnly = true)
    public AdminCarReviewDetailDto getCarReviewDetail(Long carId) {
        Car car = carRepo.findWithDetailsById(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        log.debug("Admin retrieved car review details for carId={}", carId);

        // Fetch documents with verifier eagerly loaded to avoid N+1 and lazy-proxy issues.
        List<CarDocument> docs = documentService.getDocumentsForCarWithVerifiedBy(carId);
        List<DocumentReviewDto> docDtos = docs.stream()
            .map(DocumentReviewDto::fromEntity)
            .toList();

        return AdminCarReviewDetailDto.fromEntity(car, docDtos);
    }
}
