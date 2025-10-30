package org.example.rentoza.car;

import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cars")
@CrossOrigin(origins = "*")
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
}
