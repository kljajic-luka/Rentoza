package org.example.rentoza.review;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    @Query("""
            SELECT DISTINCT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.car c
            WHERE c.id = :carId
              AND r.direction = :direction
            ORDER BY r.createdAt DESC
            """)
    List<Review> findByCarIdAndDirection(
            @Param("carId") Long carId,
            @Param("direction") ReviewDirection direction
    );

    @Query("""
            SELECT DISTINCT r
            FROM Review r
            JOIN FETCH r.reviewer
            WHERE r.reviewee.id = :userId
              AND r.direction = :direction
            ORDER BY r.createdAt DESC
            """)
    List<Review> findByRevieweeIdAndDirectionOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            @Param("direction") ReviewDirection direction
    );

    @Query("""
            SELECT AVG(r.rating)
            FROM Review r
            WHERE r.reviewee.id = :userId
              AND r.direction = :direction
            """)
    Double findAverageRatingForRevieweeAndDirection(
            @Param("userId") Long userId,
            @Param("direction") ReviewDirection direction
    );

    @Query("""
            SELECT DISTINCT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.car c
            WHERE r.direction = :direction
            ORDER BY r.createdAt DESC
            """)
    List<Review> findRecentReviews(
            @Param("direction") ReviewDirection direction,
            org.springframework.data.domain.Pageable pageable
    );

    List<Review> findByReviewerEmailIgnoreCase(String email);
    boolean existsByCarIdAndReviewerEmailIgnoreCase(Long carId, String reviewerEmail);
    boolean existsByCarAndReviewerAndDirection(Car car, User reviewer, ReviewDirection direction);
    boolean existsByBookingAndDirection(Booking booking, ReviewDirection direction);
}
