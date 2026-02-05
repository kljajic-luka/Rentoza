package org.example.rentoza.review;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;

import java.time.Instant;

@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Min(1)
    @Max(5)
    private int rating;

    @Size(max = 500)
    @Column(length = 500, columnDefinition = "VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    private String comment;

    // Category-based ratings (1-5) - for detailed renter reviews
    @Min(1)
    @Max(5)
    private Integer cleanlinessRating;

    @Min(1)
    @Max(5)
    private Integer maintenanceRating;

    @Min(1)
    @Max(5)
    private Integer communicationRating;

    @Min(1)
    @Max(5)
    private Integer convenienceRating;

    @Min(1)
    @Max(5)
    private Integer accuracyRating;

    // Owner review ratings (1-5) - for reviewing renters
    @Min(1)
    @Max(5)
    private Integer timelinessRating;

    @Min(1)
    @Max(5)
    private Integer respectForRulesRating;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 20)
    private ReviewDirection direction = ReviewDirection.FROM_USER;

    // P0-4 FIX: Review MUST reference a car
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id", nullable = false)
    @JsonIgnore
    private Car car;

    // P0-4 FIX: Review MUST have a reviewer
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    @JsonIgnore
    private User reviewer;

    // Reviewee can be null for car-only reviews
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewee_id")
    @JsonIgnore
    private User reviewee;

    // P0-4 FIX: Review MUST be tied to a booking
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    @JsonIgnore
    private Booking booking;
}
