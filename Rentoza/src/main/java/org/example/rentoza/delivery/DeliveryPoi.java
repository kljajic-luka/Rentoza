package org.example.rentoza.delivery;

import jakarta.persistence.*;
import org.example.rentoza.common.GeoPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DeliveryPoi represents a Point of Interest with special delivery fee rules.
 * 
 * POIs are used to define fixed or minimum delivery fees for specific locations
 * such as airports, train stations, or city centers. When a user requests delivery
 * to a location near a POI, the POI's fee rules override the standard per-km calculation.
 * 
 * EXAMPLE USE CASES:
 * - Belgrade Airport (BEG): Fixed €25 delivery fee regardless of car location
 * - Novi Sad Railway Station: Minimum €10 delivery fee
 * - Downtown Belgrade: €15 surcharge during peak hours
 * 
 * SPATIAL BEHAVIOR:
 * - POIs have a configurable radius (default 2km)
 * - Delivery requests within the radius trigger POI fee rules
 * - Multiple overlapping POIs: highest fee wins (business-friendly)
 * 
 * @see DeliveryFeeCalculator
 * @since 2.4.0 (Geospatial Location Migration)
 */
@Entity
@Table(name = "delivery_pois", indexes = {
        @Index(name = "idx_delivery_poi_active", columnList = "active"),
        @Index(name = "idx_delivery_poi_type", columnList = "poi_type")
})
public class DeliveryPoi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Human-readable name for the POI (e.g., "Belgrade Nikola Tesla Airport")
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Short code for the POI (e.g., "BEG", "NS-RAIL")
     */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /**
     * Geospatial location of the POI center
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "latitude", nullable = false)),
            @AttributeOverride(name = "longitude", column = @Column(name = "longitude", nullable = false))
    })
    private GeoPoint location;

    /**
     * Radius in kilometers within which this POI's rules apply
     */
    @Column(name = "radius_km", nullable = false)
    private Double radiusKm = 2.0;

    /**
     * Type of POI for categorization and reporting
     */
    @Column(name = "poi_type", nullable = false, length = 30)
    private PoiType poiType;

    /**
     * Fixed delivery fee (if set, overrides per-km calculation entirely)
     */
    @Column(name = "fixed_fee", precision = 10, scale = 2)
    private BigDecimal fixedFee;

    /**
     * Minimum delivery fee (if per-km calculation is lower, this amount is used)
     */
    @Column(name = "minimum_fee", precision = 10, scale = 2)
    private BigDecimal minimumFee;

    /**
     * Additional surcharge on top of calculated fee
     */
    @Column(name = "surcharge", precision = 10, scale = 2)
    private BigDecimal surcharge;

    /**
     * Whether this POI is currently active
     */
    @Column(nullable = false)
    private Boolean active = true;

    /**
     * Optional notes for administrators
     */
    @Column(length = 500)
    private String notes;

    /**
     * Priority for overlapping POIs (higher = takes precedence)
     */
    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ========== LIFECYCLE CALLBACKS ==========

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ========== CONSTRUCTORS ==========

    public DeliveryPoi() {
    }

    public DeliveryPoi(String name, String code, GeoPoint location, PoiType poiType) {
        this.name = name;
        this.code = code;
        this.location = location;
        this.poiType = poiType;
    }

    // ========== BUSINESS METHODS ==========

    /**
     * Check if a given location falls within this POI's radius
     */
    public boolean containsLocation(GeoPoint point) {
        if (point == null || location == null) {
            return false;
        }
        double distance = location.distanceTo(point);
        return distance <= radiusKm;
    }

    /**
     * Check if a given location falls within this POI's radius
     */
    public boolean containsLocation(double latitude, double longitude) {
        return containsLocation(GeoPoint.of(latitude, longitude));
    }

    /**
     * Calculate the effective delivery fee based on this POI's rules.
     * 
     * @param calculatedFee The per-km calculated fee (before POI override)
     * @return The final fee after applying POI rules
     */
    public BigDecimal calculateEffectiveFee(BigDecimal calculatedFee) {
        // Fixed fee takes absolute precedence
        if (fixedFee != null && fixedFee.compareTo(BigDecimal.ZERO) > 0) {
            return fixedFee;
        }

        BigDecimal effectiveFee = calculatedFee != null ? calculatedFee : BigDecimal.ZERO;

        // Apply surcharge
        if (surcharge != null && surcharge.compareTo(BigDecimal.ZERO) > 0) {
            effectiveFee = effectiveFee.add(surcharge);
        }

        // Apply minimum fee
        if (minimumFee != null && effectiveFee.compareTo(minimumFee) < 0) {
            effectiveFee = minimumFee;
        }

        return effectiveFee;
    }

    /**
     * Check if this POI uses a fixed fee (no distance calculation needed)
     */
    public boolean hasFixedFee() {
        return fixedFee != null && fixedFee.compareTo(BigDecimal.ZERO) > 0;
    }

    // ========== GETTERS AND SETTERS ==========

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public GeoPoint getLocation() {
        return location;
    }

    public void setLocation(GeoPoint location) {
        this.location = location;
    }

    public Double getRadiusKm() {
        return radiusKm;
    }

    public void setRadiusKm(Double radiusKm) {
        this.radiusKm = radiusKm;
    }

    public PoiType getPoiType() {
        return poiType;
    }

    public void setPoiType(PoiType poiType) {
        this.poiType = poiType;
    }

    public BigDecimal getFixedFee() {
        return fixedFee;
    }

    public void setFixedFee(BigDecimal fixedFee) {
        this.fixedFee = fixedFee;
    }

    public BigDecimal getMinimumFee() {
        return minimumFee;
    }

    public void setMinimumFee(BigDecimal minimumFee) {
        this.minimumFee = minimumFee;
    }

    public BigDecimal getSurcharge() {
        return surcharge;
    }

    public void setSurcharge(BigDecimal surcharge) {
        this.surcharge = surcharge;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // ========== INNER ENUM ==========

    /**
     * Types of Points of Interest for delivery fee calculation
     */
    public enum PoiType {
        AIRPORT("Airport"),
        TRAIN_STATION("Train Station"),
        BUS_STATION("Bus Station"),
        HOTEL_ZONE("Hotel Zone"),
        CITY_CENTER("City Center"),
        SHOPPING_MALL("Shopping Mall"),
        BUSINESS_DISTRICT("Business District"),
        TOURIST_ATTRACTION("Tourist Attraction"),
        PORT("Port/Marina"),
        OTHER("Other");

        private final String displayName;

        PoiType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
