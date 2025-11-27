package org.example.rentoza.car;

import org.example.rentoza.car.dto.AvailabilitySearchRequestDTO;
import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.car.dto.CarSearchCriteria;
import org.example.rentoza.config.CachingConfig;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cars")
public class CarController {

    private static final Logger log = LoggerFactory.getLogger(CarController.class);

    private final CarService service;
    private final AvailabilityService availabilityService;
    private final UserRepository userRepo;

    public CarController(CarService service, AvailabilityService availabilityService, UserRepository userRepo) {
        this.service = service;
        this.availabilityService = availabilityService;
        this.userRepo = userRepo;
    }

    /**
     * Add a new car listing.
     * RLS-ENFORCED: Only owners can add cars (owner extracted from JWT).
     */
    @PostMapping("/add")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> addCar(
            @RequestBody CarRequestDTO dto,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            if (!principal.hasRole("OWNER")) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can list cars"));
            }

            User owner = userRepo.findByEmail(principal.getUsername())
                    .orElseThrow(() -> new RuntimeException("Owner not found: " + principal.getUsername()));

            Car saved = service.addCar(dto, owner);
            return ResponseEntity.ok(new CarResponseDTO(saved));

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<CarResponseDTO>> getAllCars() {
        return ResponseEntity.ok(service.getAllCars());
    }

    /**
     * Search cars with filters, sorting, and pagination
     * GET /api/cars/search?minPrice=50&maxPrice=150&transmission=AUTOMATIC&page=0&size=20&sort=pricePerDay,asc
     * All parameters are optional
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchCars(
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String vehicleType,
            @RequestParam(required = false) String make,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Integer minYear,
            @RequestParam(required = false) Integer maxYear,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Integer minSeats,
            @RequestParam(required = false) TransmissionType transmission,
            @RequestParam(required = false) String features,  // Comma-separated: "BLUETOOTH,USB,GPS"
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            @RequestParam(required = false) String sort
    ) {
        try {
            // Parse features from comma-separated string
            List<Feature> featureList = null;
            if (features != null && !features.isBlank()) {
                featureList = Arrays.stream(features.split(","))
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .filter(f -> !f.isEmpty())
                        .map(f -> {
                            try {
                                return Feature.valueOf(f);
                            } catch (IllegalArgumentException e) {
                                // Ignore invalid feature values
                                return null;
                            }
                        })
                        .filter(f -> f != null)
                        .collect(Collectors.toList());
            }

            // Build search criteria
            CarSearchCriteria criteria = CarSearchCriteria.builder()
                    .minPrice(minPrice)
                    .maxPrice(maxPrice)
                    .vehicleType(vehicleType)
                    .make(make)
                    .model(model)
                    .minYear(minYear)
                    .maxYear(maxYear)
                    .location(location)
                    .minSeats(minSeats)
                    .transmission(transmission)
                    .features(featureList)
                    .page(page)
                    .size(size)
                    .sort(sort)
                    .build();

            // Execute search
            Page<CarResponseDTO> results = service.searchCars(criteria);

            // Return paginated response
            return ResponseEntity.ok(Map.of(
                    "content", results.getContent(),
                    "totalElements", results.getTotalElements(),
                    "totalPages", results.getTotalPages(),
                    "currentPage", results.getNumber(),
                    "pageSize", results.getSize(),
                    "hasNext", results.hasNext(),
                    "hasPrevious", results.hasPrevious()
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Search cars by availability (location + date/time range).
     *
     * Purpose:
     * - Time-aware availability search
     * - Filters out cars with overlapping bookings
     * - Supports pagination
     *
     * Example Request:
     * GET /api/cars/availability-search
     *   ?location=beograd
     *   &startDate=2025-01-15&startTime=09:00
     *   &endDate=2025-01-17&endTime=18:00
     *   &page=0&size=20
     *
     * Security:
     * - @PreAuthorize("permitAll()") - accessible to all users (anonymous + authenticated)
     * - Returns public-safe CarResponseDTO (no license plates, no PII)
     * - Rate-limited to 60 requests/minute (configured in application properties)
     *
     * Validation:
     * - All date/time fields required
     * - End must be after start
     * - Start date cannot be in the past
     * - Minimum rental duration: 1 hour
     * - Maximum search range: 90 days
     *
     * @param location Location string (city/region, case-insensitive)
     * @param startDate Rental start date (ISO 8601: YYYY-MM-DD)
     * @param startTime Rental start time (ISO 8601: HH:mm)
     * @param endDate Rental end date (ISO 8601: YYYY-MM-DD)
     * @param endTime Rental end time (ISO 8601: HH:mm)
     * @param page Page number (0-indexed, default: 0)
     * @param size Page size (default: 20, max: 100)
     * @param sort Sort order (optional, format: "field,direction")
     * @return Paginated list of available cars
     */
    @GetMapping("/availability-search")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> searchAvailableCars(
            @RequestParam String location,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            @RequestParam(required = false) String sort
    ) {
        try {
            log.info("[AvailabilitySearch] Request: location={}, startDate={}, startTime={}, endDate={}, endTime={}, page={}, size={}",
                location, startDate, startTime, endDate, endTime, page, size);

            // Build request DTO
            AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location(location)
                .startDate(startDate)
                .startTime(startTime)
                .endDate(endDate)
                .endTime(endTime)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

            // Validate request
            request.validate();

            // Execute search
            Page<Car> results = availabilityService.searchAvailableCars(request);

            // Map to response DTOs (public-safe)
            Page<CarResponseDTO> responseDTOs = results.map(CarResponseDTO::new);

            // Return paginated response
            Map<String, Object> response = Map.of(
                "content", responseDTOs.getContent(),
                "totalElements", responseDTOs.getTotalElements(),
                "totalPages", responseDTOs.getTotalPages(),
                "currentPage", responseDTOs.getNumber(),
                "pageSize", responseDTOs.getSize(),
                "hasNext", responseDTOs.hasNext(),
                "hasPrevious", responseDTOs.hasPrevious()
            );

            log.info("[AvailabilitySearch] Success: {} results (page {}/{})",
                responseDTOs.getNumberOfElements(),
                responseDTOs.getNumber() + 1,
                responseDTOs.getTotalPages()
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("[AvailabilitySearch] Validation error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid request",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[AvailabilitySearch] Unexpected error", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Internal server error",
                "message", "An unexpected error occurred while searching for available cars"
            ));
        }
    }

    // ✅ Get cars by location
    @GetMapping("/location/{location}")
    public ResponseEntity<List<CarResponseDTO>> getByLocation(@PathVariable String location) {
        return ResponseEntity.ok(service.getCarsByLocation(location));
    }

    /**
     * Get cars by owner email.
     * RLS-ENFORCED: Owner can only view their own cars (verified at service layer).
     * SpEL expression ensures requester is the owner or admin.
     */
    @GetMapping("/owner/{email}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN') and (#email == authentication.principal.email or hasRole('ADMIN'))")
    public ResponseEntity<List<CarResponseDTO>> getByOwner(@PathVariable String email) {
        return ResponseEntity.ok(service.getCarsByOwner(email));
    }

    /**
     * Get car by ID.
     * PUBLIC: Marketplace listing endpoint (no RLS needed).
     * 
     * @throws ResourceNotFoundException if car not found (handled by GlobalExceptionHandler)
     */
    @GetMapping("/{id}")
    public ResponseEntity<CarResponseDTO> getCarById(@PathVariable Long id) {
        log.debug("[GetCarById] Fetching car with ID: {}", id);
        CarResponseDTO car = service.getCarById(id);
        log.debug("[GetCarById] Found car: {} {} ({})", car.getBrand(), car.getModel(), car.getId());
        return ResponseEntity.ok(car);
    }

    /**
     * Update car details.
     * RLS-ENFORCED: Only car owner can update (verified at service layer).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> updateCar(
            @PathVariable Long id,
            @RequestBody CarRequestDTO dto,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            if (!principal.hasRole("OWNER")) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can update cars"));
            }

            User owner = userRepo.findByEmail(principal.getUsername())
                    .orElseThrow(() -> new RuntimeException("User not found: " + principal.getUsername()));

            CarResponseDTO updated = service.updateCar(id, dto, owner);
            return ResponseEntity.ok(updated);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Toggle car availability.
     * RLS-ENFORCED: Only car owner can toggle (verified at service layer).
     */
    @PatchMapping("/{id}/availability")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> toggleAvailability(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            if (!principal.hasRole("OWNER")) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can modify car availability"));
            }

            User owner = userRepo.findByEmail(principal.getUsername())
                    .orElseThrow(() -> new RuntimeException("User not found: " + principal.getUsername()));

            boolean available = request.getOrDefault("available", true);
            CarResponseDTO updated = service.toggleAvailability(id, available, owner);
            return ResponseEntity.ok(updated);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete car endpoint - DEPRECATED
     * @deprecated This endpoint is deprecated. Use PATCH /{id}/availability instead to deactivate cars.
     * This endpoint will be removed in a future release.
     * @return HTTP 410 Gone
     */
    @Deprecated
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCar(
            @PathVariable Long id
    ) {
        return ResponseEntity.status(410).body(Map.of(
                "error", "Car deletion is no longer supported. Use PATCH /api/cars/{id}/availability to deactivate cars instead.",
                "deprecatedSince", "2025-01-10",
                "alternativeEndpoint", "PATCH /api/cars/" + id + "/availability"
        ));
    }

    /**
     * Get all available car features (static data)
     * Cache-Control: public, max-age=86400, immutable
     */
    @GetMapping("/features")
    public ResponseEntity<List<String>> getAllFeatures() {
        List<String> features = Arrays.stream(Feature.values())
                .map(Enum::name)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok()
                .cacheControl(CachingConfig.longCacheControl())
                .body(features);
    }

    /**
     * Get all available car makes (semi-static data)
     * Cache-Control: public, max-age=3600
     */
    @GetMapping("/makes")
    public ResponseEntity<List<String>> getAllMakes() {
        List<String> makes = service.getAllMakes();
        
        return ResponseEntity.ok()
                .cacheControl(CachingConfig.defaultCacheControl())
                .body(makes);
    }
}
