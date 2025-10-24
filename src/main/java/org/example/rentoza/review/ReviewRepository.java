package org.example.rentoza.review;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByCarId(Long carId);
    List<Review> findByReviewerEmailIgnoreCase(String email);
    boolean existsByCarIdAndReviewerEmailIgnoreCase(Long carId, String reviewerEmail);
}