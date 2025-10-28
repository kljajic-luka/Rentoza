package org.example.rentoza.review;

import jakarta.validation.Valid;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.example.rentoza.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
@CrossOrigin(origins = "*")
public class ReviewController {

    private final ReviewService service;
    private final JwtUtil jwtUtil;

    public ReviewController(ReviewService service, JwtUtil jwtUtil) {
        this.service = service;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping
    public ResponseEntity<?> addReview(
            @RequestBody @Valid ReviewRequestDTO dto,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            // ✅ Extract JWT token from header
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid Authorization header"));
            }

            String token = authHeader.substring(7);
            String reviewerEmail = jwtUtil.getEmailFromToken(token);

            Review saved = service.addReview(dto, reviewerEmail);
            return ResponseEntity.ok(saved);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    @GetMapping("/car/{carId}")
    public ResponseEntity<List<ReviewResponseDTO>> getReviewsForCar(@PathVariable Long carId) {
        return ResponseEntity.ok(service.getReviewsForCar(carId));
    }

    @GetMapping("/car/{carId}/average")
    public ResponseEntity<Map<String, Double>> getAverageRating(@PathVariable Long carId) {
        return ResponseEntity.ok(Map.of("averageRating", service.getAverageRatingForCar(carId)));
    }
}