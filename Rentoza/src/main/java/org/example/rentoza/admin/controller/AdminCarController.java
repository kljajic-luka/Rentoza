package org.example.rentoza.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminCarDto;
import org.example.rentoza.admin.dto.CarApprovalRequestDto;
import org.example.rentoza.admin.service.AdminCarService;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin Car Management Controller.
 * 
 * <p>Provides endpoints for car moderation:
 * <ul>
 *   <li>GET /api/admin/cars - List all cars</li>
 *   <li>GET /api/admin/cars/{id} - Car detail</li>
 *   <li>GET /api/admin/cars/pending - Pending approvals</li>
 *   <li>POST /api/admin/cars/{id}/approve - Approve car</li>
 *   <li>POST /api/admin/cars/{id}/reject - Reject car</li>
 *   <li>POST /api/admin/cars/{id}/suspend - Suspend car</li>
 *   <li>POST /api/admin/cars/{id}/reactivate - Reactivate car</li>
 * </ul>
 * 
 * @see AdminCarService
 */
@RestController
@RequestMapping("/api/admin/cars")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminCarController {
    
    private final AdminCarService carService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final HateoasAssembler hateoasAssembler;
    
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
    public ResponseEntity<AdminCarDto> getCarDetail(@PathVariable Long id) {
        AdminCarDto car = carService.getCarDetail(id);
        
        log.debug("Admin {} viewed car detail for carId={}", currentUser.id(), id);
        
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
     * @param id Car ID
     * @return Updated car
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<AdminCarDto> approveCar(@PathVariable Long id) {
        User admin = getAdmin();
        
        AdminCarDto car = carService.approveCar(id, admin);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Reject a car listing.
     * 
     * @param id Car ID
     * @param request Rejection details
     * @return Updated car
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<AdminCarDto> rejectCar(
            @PathVariable Long id,
            @Valid @RequestBody CarApprovalRequestDto request) {
        
        User admin = getAdmin();
        
        AdminCarDto car = carService.rejectCar(id, request.getReason(), admin);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Suspend an active car listing.
     * 
     * @param id Car ID
     * @param body Request with reason
     * @return Updated car
     */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<AdminCarDto> suspendCar(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        
        String reason = body.getOrDefault("reason", "Policy violation");
        User admin = getAdmin();
        
        AdminCarDto car = carService.suspendCar(id, reason, admin);
        
        return ResponseEntity.ok(car);
    }
    
    /**
     * Reactivate a suspended car.
     * 
     * @param id Car ID
     * @return Updated car
     */
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<AdminCarDto> reactivateCar(@PathVariable Long id) {
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
