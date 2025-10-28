package org.example.rentoza.review;

import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    @Query("SELECT r FROM Review r JOIN FETCH r.reviewer WHERE r.car.id = :carId")
    List<Review> findByCarId(@Param("carId") Long carId);
    List<Review> findByReviewerEmailIgnoreCase(String email);
    boolean existsByCarIdAndReviewerEmailIgnoreCase(Long carId, String reviewerEmail);
    boolean existsByCarAndReviewer(Car car, User reviewer);
}