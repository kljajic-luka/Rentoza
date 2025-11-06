package org.example.rentoza.car;

import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.car.dto.CarSearchCriteria;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cars")
public class CarController {

    private final CarService service;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepo;

    public CarController(CarService service, JwtUtil jwtUtil, UserRepository userRepo) {
        this.service = service;
        this.jwtUtil = jwtUtil;
        this.userRepo = userRepo;
    }

    @PostMapping("/add")
    public ResponseEntity<?> addCar(
            @RequestBody CarRequestDTO dto,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid token"));
            }

            String token = authHeader.substring(7);
            String email = jwtUtil.getEmailFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);
            if (!"OWNER".equalsIgnoreCase(role)) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can list cars"));
            }

            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Owner not found: " + email));

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

    // ✅ Get cars by location
    @GetMapping("/location/{location}")
    public ResponseEntity<List<CarResponseDTO>> getByLocation(@PathVariable String location) {
        return ResponseEntity.ok(service.getCarsByLocation(location));
    }

    // ✅ Get cars by owner email
    @GetMapping("/owner/{email}")
    public ResponseEntity<List<CarResponseDTO>> getByOwner(@PathVariable String email) {
        return ResponseEntity.ok(service.getCarsByOwner(email));
    }

    // ✅ Get car by ID
    @GetMapping("/{id}")
    public ResponseEntity<?> getCarById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getCarById(id));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ Update car (owner only)
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCar(
            @PathVariable Long id,
            @RequestBody CarRequestDTO dto,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid token"));
            }

            String token = authHeader.substring(7);
            String email = jwtUtil.getEmailFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);
            if (!"OWNER".equalsIgnoreCase(role)) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can update cars"));
            }

            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found: " + email));

            CarResponseDTO updated = service.updateCar(id, dto, owner);
            return ResponseEntity.ok(updated);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ Toggle car availability (owner only)
    @PatchMapping("/{id}/availability")
    public ResponseEntity<?> toggleAvailability(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid token"));
            }

            String token = authHeader.substring(7);
            String email = jwtUtil.getEmailFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);
            if (!"OWNER".equalsIgnoreCase(role)) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can modify car availability"));
            }

            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found: " + email));

            boolean available = request.getOrDefault("available", true);
            CarResponseDTO updated = service.toggleAvailability(id, available, owner);
            return ResponseEntity.ok(updated);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ Delete car (owner only)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCar(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid token"));
            }

            String token = authHeader.substring(7);
            String email = jwtUtil.getEmailFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);
            if (!"OWNER".equalsIgnoreCase(role)) {
                return ResponseEntity.status(403).body(Map.of("error", "Only owners can delete cars"));
            }

            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found: " + email));

            service.deleteCar(id, owner);
            return ResponseEntity.ok(Map.of("message", "Car deleted successfully"));

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
