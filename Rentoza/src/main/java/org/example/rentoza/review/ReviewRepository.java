package org.example.rentoza.review;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    @Query("""
            SELECT DISTINCT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
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
            SELECT AVG(r.rating)
            FROM Review r
            WHERE r.direction = :direction
            """)
    Double findAverageRatingByDirection(@Param("direction") ReviewDirection direction);

    @Query("""
            SELECT DISTINCT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
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
    
    // ========== FAKE REVIEW DETECTION (Issue 3.2) ==========
    
    /**
     * Count reviews submitted by a user in the last N hours.
     * Used for velocity checking to detect suspicious review patterns.
     * 
     * @param reviewerId The reviewer's ID
     * @param since The cutoff time
     * @return Number of reviews submitted since the cutoff
     */
    @Query("""
            SELECT COUNT(r)
            FROM Review r
            WHERE r.reviewer.id = :reviewerId
              AND r.createdAt >= :since
            """)
    long countReviewsByReviewerSince(
            @Param("reviewerId") Long reviewerId,
            @Param("since") Instant since
    );

    // P1-6 FIX: Added JOIN FETCH to prevent N+1 queries
    @Query("""
            SELECT DISTINCT r FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            JOIN FETCH r.car
            JOIN FETCH r.booking
            WHERE r.booking.id IN :bookingIds 
              AND r.direction = :direction
            """)
    List<Review> findByBookingIdInAndDirection(@Param("bookingIds") List<Long> bookingIds, @Param("direction") ReviewDirection direction);

    @Query("""
            SELECT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            JOIN FETCH r.car
            WHERE r.reviewee = :user
            ORDER BY r.createdAt DESC
            """)
    List<Review> findByReviewee(@Param("user") User user);

    @Query("""
            SELECT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            JOIN FETCH r.car
            WHERE r.reviewer = :user
              AND r.direction = :direction
            ORDER BY r.createdAt DESC
            """)
    List<Review> findByReviewerAndDirection(@Param("user") User user, @Param("direction") ReviewDirection direction);

    // ========== RLS-ENFORCED QUERIES (Enterprise Security Enhancement) ==========

    /**
     * Find reviews received by a user with ownership verification.
     * Returns reviews only if the authenticated user is the reviewee OR an admin.
     * Prevents User A from viewing User B's private review history.
     * 
     * @param revieweeEmail Reviewee's email
     * @param requesterId Authenticated user's ID
     * @param direction Review direction (FROM_USER or FROM_OWNER)
     * @return List of reviews (empty if requester is not the reviewee)
     */
    @Query("""
            SELECT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            JOIN FETCH r.car
            WHERE r.reviewee.email = :revieweeEmail
              AND r.reviewee.id = :requesterId
              AND r.direction = :direction
            ORDER BY r.createdAt DESC
            """)
    List<Review> findByRevieweeEmailForUser(
            @Param("revieweeEmail") String revieweeEmail,
            @Param("requesterId") Long requesterId,
            @Param("direction") ReviewDirection direction
    );

    /**
     * Find reviews given by a user with ownership verification.
     * Returns reviews only if the authenticated user is the reviewer OR an admin.
     * 
     * @param reviewerEmail Reviewer's email
     * @param requesterId Authenticated user's ID
     * @param direction Review direction (FROM_USER or FROM_OWNER)
     * @return List of reviews given by the user (empty if requester is not the reviewer)
     */
    @Query("""
            SELECT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            JOIN FETCH r.car
            WHERE r.reviewer.email = :reviewerEmail
              AND r.reviewer.id = :requesterId
              AND r.direction = :direction
            ORDER BY r.createdAt DESC
            """)
    List<Review> findByReviewerEmailForUser(
            @Param("reviewerEmail") String reviewerEmail,
            @Param("requesterId") Long requesterId,
            @Param("direction") ReviewDirection direction
    );

    // ========== ADMIN MANAGEMENT QUERIES ==========

    /**
     * Anonymize reviews given by a user.
     * Sets reviewer to null for GDPR compliance on user deletion.
     * Review content is preserved but no longer linked to the deleted user.
     * 
     * @param reviewerId ID of the reviewer being deleted
     * @return Number of reviews anonymized
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Review r SET r.reviewer = NULL WHERE r.reviewer.id = :reviewerId")
    int anonymizeReviewsByReviewerId(@Param("reviewerId") Long reviewerId);

    /**
     * Anonymize reviews received by a user.
     * Sets reviewee to null for GDPR compliance on user deletion.
     * Review content is preserved but no longer linked to the deleted user.
     * 
     * @param revieweeId ID of the reviewee being deleted
     * @return Number of reviews anonymized
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Review r SET r.reviewee = NULL WHERE r.reviewee.id = :revieweeId")
    int anonymizeReviewsByRevieweeId(@Param("revieweeId") Long revieweeId);
    
    // ========== MUTUAL REVIEW VISIBILITY (Issue 3.1) ==========
    
    /**
     * Check if the other party has submitted their review for a booking.
     * Used for mutual review visibility - reviews are hidden until BOTH parties submit.
     * 
     * @param bookingId The booking ID
     * @param direction The direction to check for (opposite of the viewer's review)
     * @return true if the other party has submitted their review
     */
    @Query("""
            SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
            FROM Review r
            WHERE r.booking.id = :bookingId
              AND r.direction = :direction
            """)
    boolean existsOtherReview(
            @Param("bookingId") Long bookingId,
            @Param("direction") ReviewDirection direction
    );
    
    /**
     * Find both reviews for a booking (if they exist).
     * Used to check mutual review completion.
     * 
     * @param bookingId The booking ID
     * @return List of 0-2 reviews (FROM_USER and/or FROM_OWNER)
     */
    @Query("""
            SELECT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            WHERE r.booking.id = :bookingId
            ORDER BY r.direction
            """)
    List<Review> findByBookingId(@Param("bookingId") Long bookingId);
    
    /**
     * Find reviews for a car that should be visible.
     * A review is visible if:
     * 1. Both parties have submitted reviews for the same booking, OR
     * 2. 7 days have passed since the review was created (timeout)
     * 
     * @param carId The car ID
     * @param direction The review direction (FROM_USER for car reviews)
     * @param visibilityTimeout Reviews older than this are always visible
     * @return List of visible reviews
     */
    @Query("""
            SELECT DISTINCT r
            FROM Review r
            JOIN FETCH r.reviewer
            JOIN FETCH r.reviewee
            JOIN FETCH r.car c
            WHERE c.id = :carId
              AND r.direction = :direction
              AND (
                  r.createdAt < :visibilityTimeout
                  OR EXISTS (
                      SELECT 1 FROM Review otherReview 
                      WHERE otherReview.booking.id = r.booking.id 
                        AND otherReview.direction = 'FROM_OWNER'
                  )
              )
            ORDER BY r.createdAt DESC
            """)
    List<Review> findVisibleByCarIdAndDirection(
            @Param("carId") Long carId,
            @Param("direction") ReviewDirection direction,
            @Param("visibilityTimeout") java.time.Instant visibilityTimeout
    );
}

