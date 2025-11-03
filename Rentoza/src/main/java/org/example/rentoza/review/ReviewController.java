package org.example.rentoza.review;

import jakarta.validation.Valid;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.example.rentoza.review.dto.RenterReviewRequestDTO;
import org.example.rentoza.review.dto.OwnerReviewRequestDTO;
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

    @GetMapping("/recent")
    public ResponseEntity<List<ReviewResponseDTO>> getRecentReviews() {
        return ResponseEntity.ok(service.getRecentReviews());
    }

    @GetMapping("/car/{carId}/average")
    public ResponseEntity<Map<String, Double>> getAverageRating(@PathVariable Long carId) {
        return ResponseEntity.ok(Map.of("averageRating", service.getAverageRatingForCar(carId)));
    }

    /**
     * POST /api/reviews/from-renter - Secure renter review submission
     * Creates a review from renter to owner after completing a booking.
     * Validates authentication, booking ownership, completion status, and prevents duplicates.
     */
    @PostMapping("/from-renter")
    public ResponseEntity<?> createRenterReview(
            @RequestBody @Valid RenterReviewRequestDTO dto,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            // Extract JWT token from header
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid Authorization header"));
            }

            String token = authHeader.substring(7);
            String renterEmail = jwtUtil.getEmailFromToken(token);

            // Create review with full security validation
            Review saved = service.createRenterReview(dto, renterEmail);

            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "rating", saved.getRating(),
                    "message", "Review successfully submitted"
            ));

        } catch (RuntimeException e) {
            // Handle specific error cases
            String message = e.getMessage();
            if (message.contains("Unauthorized") || message.contains("not found")) {
                return ResponseEntity.status(403).body(Map.of("error", message));
            } else if (message.contains("already reviewed")) {
                return ResponseEntity.status(409).body(Map.of("error", message));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", message));
            }
        }
    }

    /**
     * POST /api/reviews/from-owner - Secure owner review submission
     * Creates a review from owner to renter after completing a booking.
     * Validates authentication, booking ownership, completion status, and prevents duplicates.
     */
    @PostMapping("/from-owner")
    public ResponseEntity<?> createOwnerReview(
            @RequestBody @Valid OwnerReviewRequestDTO dto,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            // Extract JWT token from header
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid Authorization header"));
            }

            String token = authHeader.substring(7);
            String ownerEmail = jwtUtil.getEmailFromToken(token);

            // Create review with full security validation
            Review saved = service.createOwnerReview(dto, ownerEmail);

            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "rating", saved.getRating(),
                    "message", "Review successfully submitted"
            ));

        } catch (RuntimeException e) {
            // Handle specific error cases
            String message = e.getMessage();
            if (message.contains("Unauthorized") || message.contains("not found")) {
                return ResponseEntity.status(403).body(Map.of("error", message));
            } else if (message.contains("already reviewed")) {
                return ResponseEntity.status(409).body(Map.of("error", message));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", message));
            }
        }
    }
}
