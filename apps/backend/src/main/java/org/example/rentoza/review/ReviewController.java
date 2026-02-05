package org.example.rentoza.review;

import jakarta.validation.Valid;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.example.rentoza.review.dto.RenterReviewRequestDTO;
import org.example.rentoza.review.dto.OwnerReviewRequestDTO;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService service;
    
    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> addReview(
            @RequestBody @Valid ReviewRequestDTO dto,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            Review saved = service.addReview(dto, principal.getUsername());
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
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Create review with full security validation
            Review saved = service.createRenterReview(dto, principal.getUsername());

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
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Create review with full security validation
            Review saved = service.createOwnerReview(dto, principal.getUsername());

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
     * GET /api/reviews/received/{email} - Get reviews received by owner
     * Returns all reviews where the owner is the reviewee (reviews from renters)
     * Requires authentication to prevent anonymous scraping
     */
    @GetMapping("/received/{email}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getReceivedReviews(
            @PathVariable String email,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            return ResponseEntity.ok(service.getReviewsReceivedByEmail(email));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }

    /**
     * GET /api/reviews/from-owner/{email} - Get reviews given by owner
     * Returns all reviews where the owner is the reviewer (reviews to renters)
     * Requires authentication to prevent anonymous scraping
     */
    @GetMapping("/from-owner/{email}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getReviewsFromOwner(
            @PathVariable String email,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            return ResponseEntity.ok(service.getReviewsGivenByOwner(email));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }
}
