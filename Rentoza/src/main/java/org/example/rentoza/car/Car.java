package org.example.rentoza.car;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.example.rentoza.user.User;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.review.Review;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
@Entity
@Table(
        name = "cars",
        indexes = {
                @Index(name = "idx_car_location", columnList = "location"),
                @Index(name = "idx_car_available", columnList = "available")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Car {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String brand;

    @NotBlank
    @Column(nullable = false)
    private String model;

    @NotNull
    @Min(1950)
    @Max(2050)
    @Column(nullable = false)
    private Integer year;

    @NotNull
    @Min(10)
    @Column(nullable = false)
    private Double pricePerDay;

    @NotBlank
    @Column(nullable = false)
    private String location;

    private String imageUrl;

    @Column(nullable = false)
    private boolean available = true;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "car_images", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "image_url")
    private List<String> imageUrls = new ArrayList<>();

    // 🔗 Relations
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @OneToMany(mappedBy = "car", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Booking> bookings = new ArrayList<>();

    @OneToMany(mappedBy = "car", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Review> reviews = new ArrayList<>();

    @PrePersist
    @PreUpdate
    public void normalize() {
        if (location != null) location = location.trim().toLowerCase();
        if (brand != null) brand = brand.trim();
        if (model != null) model = model.trim();
    }
}