package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminCarDto;
import org.example.rentoza.admin.dto.CarApprovalRequestDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin Car Management Service.
 * 
 * <p>Provides car moderation capabilities:
 * <ul>
 *   <li>List all cars with admin view</li>
 *   <li>Approve car listings</li>
 *   <li>Reject car listings with reason</li>
 *   <li>Suspend car listings</li>
 * </ul>
 * 
 * <p><b>NOTE:</b> Car approval workflow uses the existing `available` field.
 * Full approval_status enum will be added in future migration.
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
    private final AdminAuditService auditService;
    
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
        List<Car> pendingCars = carRepo.findByAvailableFalse();
        return pendingCars.stream()
                .map(AdminCarDto::fromEntity)
                .toList();
    }
    
    /**
     * Approve a car listing.
     * 
     * <p>Makes the car visible in search results.
     * 
     * @param carId Car ID
     * @param admin Admin performing approval
     * @return Updated car DTO
     */
    public AdminCarDto approveCar(Long carId, User admin) {
        Car car = carRepo.findWithDetailsById(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        // Capture before state
        String beforeState = auditService.toJson(AdminCarDto.fromEntity(car));
        
        // Approve (make available)
        car.setAvailable(true);
        carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(car));
        
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
        
        log.info("Car {} approved by admin {}", carId, admin.getId());
        
        return AdminCarDto.fromEntity(car);
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
        car.setAvailable(false);
        carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(car));
        
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
        
        return AdminCarDto.fromEntity(car);
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
        car.setAvailable(false);
        carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(car));
        
        // Audit log with reason
        auditService.logAction(
            admin,
            AdminAction.CAR_REMOVED,
            ResourceType.CAR,
            carId,
            beforeState,
            afterState,
            "Suspended: " + reason
        );
        
        log.info("Car {} suspended by admin {}. Reason: {}", carId, admin.getId(), reason);
        
        return AdminCarDto.fromEntity(car);
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
        car.setAvailable(true);
        carRepo.save(car);
        
        // Capture after state
        String afterState = auditService.toJson(AdminCarDto.fromEntity(car));
        
        // Audit log
        auditService.logAction(
            admin,
            AdminAction.CAR_APPROVED,
            ResourceType.CAR,
            carId,
            beforeState,
            afterState,
            "Car listing reactivated"
        );
        
        log.info("Car {} reactivated by admin {}", carId, admin.getId());
        
        return AdminCarDto.fromEntity(car);
    }
    
    /**
     * Count available cars.
     */
    @Transactional(readOnly = true)
    public Long countAvailableCars() {
        return carRepo.countAvailableCars();
    }
}
