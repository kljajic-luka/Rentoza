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

    // TODO: Long-term: Switch to cloud storage URLs (S3, Cloudinary, etc.)
    // Currently supports Base64-encoded images for MVP
    @Column(name = "image_url", columnDefinition = "LONGTEXT")
    private String imageUrl;

    @Column(nullable = false)
    private boolean available = true;

    // ========== NEW PRODUCTION-READY FIELDS ==========

    @Column(length = 1000, columnDefinition = "VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    private String description;

    @NotNull
    @Min(2)
    @Max(9)
    @Column(nullable = false)
    private Integer seats = 5;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FuelType fuelType = FuelType.BENZIN;

    @Min(0)
    @Max(50)
    @Column(name = "fuel_consumption")
    private Double fuelConsumption; // liters per 100km

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransmissionType transmissionType = TransmissionType.MANUAL;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "car_features", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "feature")
    @Enumerated(EnumType.STRING)
    private List<Feature> features = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "car_add_ons", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "add_on")
    private List<String> addOns = new ArrayList<>(); // Custom add-ons like "Dečije sedište", "Zimske gume"

    @Column(name = "cancellation_policy", length = 20)
    @Enumerated(EnumType.STRING)
    private CancellationPolicy cancellationPolicy = CancellationPolicy.FLEXIBLE;

    @Column(name = "min_rental_days")
    @Min(1)
    private Integer minRentalDays = 1;

    @Column(name = "max_rental_days")
    @Min(1)
    private Integer maxRentalDays = 30;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // TODO: Long-term: Switch to cloud storage URLs (S3, Cloudinary, etc.)
    // Currently supports Base64-encoded images for MVP (up to 10 images)
    @ElementCollection
    @CollectionTable(name = "car_images", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "image_url", columnDefinition = "LONGTEXT")
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