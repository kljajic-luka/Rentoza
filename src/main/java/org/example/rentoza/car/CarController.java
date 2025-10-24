package org.example.rentoza.car;

import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
            // ✅ Check token
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid token"));
            }

            String token = authHeader.substring(7);
            String email = jwtUtil.getEmailFromToken(token);

            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Owner not found: " + email));

            // ✅ Map DTO → Entity
            Car car = new Car();
            car.setBrand(dto.getBrand());
            car.setModel(dto.getModel());
            car.setYear(dto.getYear());
            car.setPricePerDay(dto.getPricePerDay());
            car.setLocation(dto.getLocation());
            car.setImageUrl(dto.getImageUrl());
            car.setImageUrls(dto.getImageUrls());
            car.setOwner(owner);

            Car saved = service.addCar(car, owner.getEmail());
            return ResponseEntity.ok(saved);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<Car>> getAllCars() {
        return ResponseEntity.ok(service.getAllCars());
    }

    @GetMapping("/location/{location}")
    public ResponseEntity<List<Car>> getByLocation(@PathVariable String location) {
        return ResponseEntity.ok(service.getCarsByLocation(location));
    }

    @GetMapping("/owner/{email}")
    public ResponseEntity<List<Car>> getByOwner(@PathVariable String email) {
        return ResponseEntity.ok(service.getCarsByOwner(email));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCarById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getCarById(id));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}