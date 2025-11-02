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

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewDirection direction = ReviewDirection.FROM_USER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id")
    @JsonIgnore
    private Car car;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    @JsonIgnore
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewee_id")
    @JsonIgnore
    private User reviewee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    @JsonIgnore
    private Booking booking;
}
