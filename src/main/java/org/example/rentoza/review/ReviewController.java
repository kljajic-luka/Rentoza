package org.example.rentoza.review;

import jakarta.validation.Valid;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
@CrossOrigin(origins = "*")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> addReview(@RequestBody @Valid ReviewRequestDTO dto) {
        try {
            Review saved = service.addReview(dto);

            return ResponseEntity.ok(new ReviewResponseDTO(
                    saved.getId(),
                    saved.getRating(),
                    saved.getComment(),
                    saved.getReviewer().getEmail(),
                    saved.getCar().getId(),
                    saved.getCreatedAt()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/car/{carId}")
    public ResponseEntity<List<Review>> getReviewsForCar(@PathVariable Long carId) {
        return ResponseEntity.ok(service.getReviewsForCar(carId));
    }

    @GetMapping("/car/{carId}/average")
    public ResponseEntity<Map<String, Double>> getAverageRating(@PathVariable Long carId) {
        return ResponseEntity.ok(Map.of("averageRating", service.getAverageRatingForCar(carId)));
    }
}