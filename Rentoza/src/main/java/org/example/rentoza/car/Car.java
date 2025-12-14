package org.example.rentoza.car;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.user.User;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.review.Review;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
@Entity
@Table(
        name = "cars",
        indexes = {
                @Index(name = "idx_car_location", columnList = "location"),
                @Index(name = "idx_car_available", columnList = "available"),
                @Index(name = "idx_car_location_city_available", columnList = "location_city, available")
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

    /**
     * Version for optimistic locking.
     * Prevents concurrent modification race conditions in admin approval workflow.
     */
    @Version
    @Column(name = "version")
    private Long version;

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

    /**
     * Daily rental price in Serbian Dinar (RSD).
     * Uses BigDecimal for financial precision - prevents floating-point rounding errors.
     * 
     * Validation: Minimum 10 RSD (enforced at service layer, not JPA).
     * Column: DECIMAL(19, 2) - supports large values with 2 decimal places.
     */
    @NotNull
    @Column(name = "price_per_day", nullable = false, precision = 19, scale = 2)
    private BigDecimal pricePerDay = BigDecimal.ZERO;

    /**
     * @deprecated Legacy string-based location field.
     * 
     * <p>As of 2025-02, Rentoza uses geospatial coordinates via {@link #locationGeoPoint}
     * for precise car positioning, radius-based search, and delivery fee calculation.
     * 
     * <p><b>Migration Status:</b> Kept temporarily for backward compatibility.
     * Will be removed after V28 migration (2 weeks post-deployment monitoring).
     * 
     * @see #locationGeoPoint
     */
    @Deprecated(since = "2025-02", forRemoval = true)
    @NotBlank
    @Column(nullable = false)
    private String location;

    // ========== GEOSPATIAL LOCATION (Phase 2.4: Turo-style Architecture) ==========

    /**
     * Precise geospatial location of the car (parking position).
     * 
     * <p>Embedded GeoPoint contains:
     * <ul>
     *   <li>latitude/longitude (DECIMAL precision)</li>
     *   <li>Human-readable address (from geocoding)</li>
     *   <li>City name (for UI grouping)</li>
     *   <li>ZIP code</li>
     * </ul>
     * 
     * <p><b>Usage:</b>
     * <ul>
     *   <li>Radius-based car search (SPATIAL INDEX)</li>
     *   <li>Delivery distance calculation</li>
     *   <li>Check-in geofence validation</li>
     *   <li>Privacy obfuscation for unbooked guests</li>
     * </ul>
     * 
     * @see org.example.rentoza.common.GeoPoint
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "location_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "location_longitude")),
            @AttributeOverride(name = "address", column = @Column(name = "location_address")),
            @AttributeOverride(name = "city", column = @Column(name = "location_city")),
            @AttributeOverride(name = "zipCode", column = @Column(name = "location_zip_code")),
            @AttributeOverride(name = "accuracyMeters", column = @Column(name = "location_accuracy_meters"))
    })
    private GeoPoint locationGeoPoint;

    // ========== DELIVERY PRICING (Turo-style) ==========

    /**
     * Free delivery radius in kilometers.
     * 
     * <p>If guest pickup location is within this radius from the car,
     * no delivery fee is charged. Beyond this, per-km rate applies.
     * 
     * <p><b>Default:</b> 0 (no free delivery, all distance charged)
     * <p><b>Example:</b> 5.0 = first 5km free, charge beyond
     */
    @Column(name = "delivery_radius_km")
    @Min(0)
    @Max(100)
    private Double deliveryRadiusKm = 0.0;

    /**
     * Delivery fee per kilometer beyond free radius (in RSD).
     * 
     * <p>If null or 0, car does not offer delivery service
     * (guest must pick up at car location).
     * 
     * <p><b>Example:</b> 100.00 = charge 100 RSD per km beyond free radius
     */
    @Column(name = "delivery_fee_per_km", precision = 10, scale = 2)
    private BigDecimal deliveryFeePerKm;

    // TODO: Long-term: Switch to cloud storage URLs (S3, Cloudinary, etc.)
    // Currently supports Base64-encoded images for MVP
    @Lob
    @Column(name = "image_url")
    private String imageUrl;

    @Column(nullable = false)
    private boolean available = true;

    // ========== APPROVAL WORKFLOW FIELDS ==========

    /**
     * @deprecated Use {@link #listingStatus} instead.
     * Kept for backward compatibility during V30 migration.
     */
    @Deprecated(since = "2025-01", forRemoval = true)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, columnDefinition = "VARCHAR(20)")
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    /**
     * Car listing lifecycle status (Serbian compliance).
     * DRAFT → PENDING_APPROVAL → APPROVED (or REJECTED/SUSPENDED)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "listing_status", nullable = false, length = 50)
    private ListingStatus listingStatus = ListingStatus.PENDING_APPROVAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // ========== DOCUMENT TRACKING FIELDS (Serbian Compliance) ==========

    /**
     * Date vehicle registration expires.
     */
    @Column(name = "registration_expiry_date")
    private java.time.LocalDate registrationExpiryDate;

    /**
     * Date technical inspection was performed.
     * Used to calculate expiry (date + 6 months).
     */
    @Column(name = "technical_inspection_date")
    private java.time.LocalDate technicalInspectionDate;

    /**
     * Technical inspection expiry date (= date + 6 months).
     * CRITICAL: Rent-a-car vehicles require 6-month inspection.
     */
    @Column(name = "technical_inspection_expiry_date")
    private java.time.LocalDate technicalInspectionExpiryDate;

    /**
     * Insurance policy expiration date.
     */
    @Column(name = "insurance_expiry_date")
    private java.time.LocalDate insuranceExpiryDate;

    /**
     * When documents were last verified by admin.
     */
    @Column(name = "documents_verified_at")
    private java.time.LocalDateTime documentsVerifiedAt;

    /**
     * Admin who verified documents.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documents_verified_by")
    private User documentsVerifiedBy;

    /**
     * Uploaded documents for this car.
     */
    @OneToMany(mappedBy = "car", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<CarDocument> documents = new ArrayList<>();

    // ========== NEW PRODUCTION-READY FIELDS ==========

    @Column(name = "license_plate", length = 20)
    private String licensePlate;

    @Column(length = 1000)
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

    /**
     * Car features - loaded LAZILY for performance.
     * 
     * PERFORMANCE: Collections are NOT loaded until explicitly accessed.
     * For list views (search results), we skip loading features.
     * For detail views, use @EntityGraph or JOIN FETCH.
     * 
     * N+1 PREVENTION:
     * - CarRepository.findWithDetailsById() uses @EntityGraph for single-car views
     * - List queries do NOT fetch features (intentional)
     * 
     * TYPE: Set<Feature> to prevent MultipleBagFetchException when eagerly
     * loading multiple collections. Sets use different fetch strategy than Lists.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "car_features", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "feature")
    @Enumerated(EnumType.STRING)
    private Set<Feature> features = new HashSet<>();

    /**
     * Custom add-ons - loaded LAZILY for performance.
     * Examples: "Dečije sedište", "Zimske gume"
     * 
     * PERFORMANCE: Same lazy loading strategy as features.
     * 
     * TYPE: Set<String> to prevent MultipleBagFetchException when eagerly
     * loading multiple collections alongside features.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "car_add_ons", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "add_on")
    private Set<String> addOns = new HashSet<>();

    /**
     * @deprecated Legacy field from owner-selected cancellation policy model.
     * 
     * <p>As of 2024-01, Rentoza uses a platform-standard Turo-style cancellation
     * policy with time-based rules instead of owner-selected tiers.
     * 
     * <p><b>Migration Status:</b>
     * <ul>
     *   <li>New bookings: Ignore this field, use {@code CancellationPolicyService}</li>
     *   <li>Existing bookings: Legacy data preserved for historical reference</li>
     *   <li>Add-Car Wizard: Field removed from UI (Phase 3)</li>
     * </ul>
     * 
     * <p>This field is retained for backward compatibility with existing car
     * listings. It will be fully removed in a future major version.
     * 
     * @see org.example.rentoza.booking.cancellation.CancellationRecord
     */
    @Deprecated(since = "2024-01", forRemoval = false)
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
    @Lob
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
        // GeoPoint validation handled by GeoPoint.validate()
        if (locationGeoPoint != null) {
            locationGeoPoint.validate();
        }
    }

    // ========== GEOSPATIAL HELPER METHODS ==========

    /**
     * Check if car has valid geospatial coordinates.
     */
    public boolean hasGeoLocation() {
        return locationGeoPoint != null && locationGeoPoint.hasCoordinates();
    }

    /**
     * Check if car offers delivery service.
     */
    public boolean offersDelivery() {
        return deliveryFeePerKm != null && deliveryFeePerKm.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Get effective city name (from GeoPoint if available, else legacy location).
     */
    public String getEffectiveCity() {
        if (locationGeoPoint != null && locationGeoPoint.getCity() != null) {
            return locationGeoPoint.getCity();
        }
        return location;
    }

    // ========== DOCUMENT COMPLIANCE HELPER METHODS ==========

    /**
     * Check if technical inspection is expired (> 6 months old).
     */
    public boolean isTechnicalInspectionExpired() {
        return technicalInspectionExpiryDate != null && 
               technicalInspectionExpiryDate.isBefore(java.time.LocalDate.now());
    }

    /**
     * Check if registration is expired.
     */
    public boolean isRegistrationExpired() {
        return registrationExpiryDate != null && 
               registrationExpiryDate.isBefore(java.time.LocalDate.now());
    }

    /**
     * Check if insurance is expired.
     */
    public boolean isInsuranceExpired() {
        return insuranceExpiryDate != null && 
               insuranceExpiryDate.isBefore(java.time.LocalDate.now());
    }

    /**
     * Check if car is legally rentable (all documents current + approved).
     */
    public boolean isLegallyRentable() {
        return listingStatus == ListingStatus.APPROVED &&
               !isTechnicalInspectionExpired() &&
               !isRegistrationExpired() &&
               !isInsuranceExpired();
    }

    /**
     * Days until technical inspection expires (negative if expired).
     */
    public long getDaysUntilTechInspectionExpiry() {
        if (technicalInspectionExpiryDate == null) return -1;
        return java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.now(), 
            technicalInspectionExpiryDate
        );
    }
}