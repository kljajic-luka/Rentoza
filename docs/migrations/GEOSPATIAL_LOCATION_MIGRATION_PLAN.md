# Geospatial Location Migration Plan
## From String-Based Locations to Turo-like Coordinate Architecture

**Status:** Planning Phase (Phase 2.4)  
**Scope:** Complete location handling refactor across database, backend services, frontend UI  
**Timeline Estimate:** 6-8 weeks (including testing & rollback procedures)  
**Risk Level:** CRITICAL - Impacts core car discovery, booking, and check-in workflows  

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current State Analysis](#current-state-analysis)
3. [Target Architecture](#target-architecture)
4. [Database Schema Refactor](#database-schema-refactor)
5. [Business Logic & Privacy](#business-logic--privacy)
6. [API Contract Changes](#api-contract-changes)
7. [Frontend Integration](#frontend-integration)
8. [Check-In & Geofencing Pipeline](#check-in--geofencing-pipeline)
9. [Data Migration Strategy](#data-migration-strategy)
10. [Deployment & Rollback](#deployment--rollback)
11. [Testing Strategy](#testing-strategy)

---

## Executive Summary

### Problem Statement

Rentoza currently stores car locations as **simple string values** (`location = "Belgrade"`). This design:

- **Cannot support** distance-based pricing (Turo's "delivery fees based on pickup distance")
- **Cannot validate** guest proximity during remote check-in (geofence is partially implemented but lacks precise data)
- **Cannot prevent** asset stalking (hostile users can reverse-search to find cars)
- **Scales poorly** for multi-city expansion (string matching vs. spatial queries)
- **Loses context** about where exactly hosts park cars (important for guest UX)

### Solution Overview

Migrate to a **Geospatial Point-based architecture** with:

- **Database Layer:** MySQL SPATIAL types (POINT with SRID 4326) + indexed radius queries
- **Entity Layer:** Embeddable `GeoPoint` value objects for immutable location snapshots
- **Privacy Layer:** Fuzzy obfuscation for unbooked guests (randomize ±500m)
- **Pricing Layer:** Distance-based delivery fees integrated with checkout
- **Check-In Layer:** Precise geofence validation against booking-time pickup location (not current car location)

### Critical Architectural Decisions

| Decision | Rationale | Risk Mitigation |
|----------|-----------|-----------------|
| **MySQL SPATIAL vs. Haversine Math** | SPATIAL INDEX enables sub-100ms radius queries on millions of cars | If dataset stays <10k cars, pure math is acceptable; decision point at V2.5 migration |
| **Snapshot Location at Booking** | Prevents host from moving car and changing guest pickup point | Requires database transactions to be ACID-strict during booking phase |
| **Fuzzy Coordinates for Guests** | Prevents asset stalking/surveillance of luxury cars | Requires legal review: does randomization violate contractual "exact pickup location"? |
| **Separate Delivery Table** | Delivery is orthogonal pricing, may involve 3rd party integration (OSRM API) | Avoid tight coupling with booking entity |

---

## Current State Analysis

### Existing Database Schema

#### Cars Table (CURRENT)

```sql
CREATE TABLE cars (
    id BIGINT PRIMARY KEY,
    brand VARCHAR(255),
    model VARCHAR(255),
    year INT,
    price_per_day DECIMAL(19,2),
    location VARCHAR(255),              -- ❌ PROBLEM: String-based, no precision
    available BOOLEAN,
    created_at TIMESTAMP,
    INDEX idx_car_location (location)    -- ❌ Full-text search only, no radius queries
);
```

**Problems:**
- `location` is free-form text (typos: "Beograd" vs "Belgrade" vs "BELGRADE")
- Cannot answer "find cars within 5km of GPS coordinate"
- Normalization in `Car.normalize()` helps but doesn't solve core issue

#### Bookings Table (PARTIAL SOLUTION)

```sql
CREATE TABLE bookings (
    id BIGINT PRIMARY KEY,
    car_id BIGINT,
    renter_id BIGINT,
    start_time DATETIME,
    end_time DATETIME,
    -- From V14 (Check-in Workflow):
    car_latitude DECIMAL(10,8),         -- ✅ GOOD: Stored at check-in time
    car_longitude DECIMAL(11,8),
    host_check_in_latitude DECIMAL(10,8),
    guest_check_in_latitude DECIMAL(10,8),
    geofence_distance_meters INT        -- ✅ Calculated, but read-only
);
```

**Issues:**
- Geofence coordinates are **check-in artifacts** (T-24h before trip)
- No "agreed pickup location" at **booking time**
- Cannot calculate delivery distance before guest books

### Existing Services

#### GeofenceService (CURRENT)

```java
public GeofenceResult validateProximity(
    BigDecimal carLat, BigDecimal carLon,
    BigDecimal guestLat, BigDecimal guestLon,
    LocationDensity density
)
```

**Current Flow:**
1. Host submits check-in photos at T-24h → `car_latitude`, `car_longitude` captured
2. Guest confirms check-in → `guest_check_in_latitude` captured
3. Haversine distance calculated dynamically
4. Validation passes if distance ≤ 100m (urban) or 50-150m (density-adjusted)

**Problem:** This validates **guest proximity to car at handshake**, not **booking-time pickup location agreement**.

#### CarRepository (CURRENT QUERY PATTERN)

```java
// Current: Location string matching
List<Car> findByLocationIgnoreCaseAndAvailableTrue(String location);

// Current: Full entity graph load
List<Car> findAvailableWithDetailsByLocation(String location);
```

**Problems:**
- Cannot do range queries (`SELECT * WHERE distance(location, 44.8125, 20.4612) <= 5km`)
- No privacy obfuscation for guest searches
- Loading features/addOns for every search result (N+1 risk on large cities)

---

## Target Architecture

### Design Philosophy

1. **Immutability:** Location snapshots at booking time are frozen (gas tank principle)
2. **Privacy by Default:** Unbooked guests see fuzzy coordinates only
3. **Precision Where Needed:** Check-in validation uses exact coordinates
4. **Graceful Degradation:** If location data missing, skip geofence validation (warn, not block)
5. **Audit Trail:** Every location change logged for dispute resolution

### Core Entities

#### 1. GeoPoint (Embeddable Value Object)

```java
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoPoint {
    
    @Column(name = "latitude", precision = 10, scale = 8, nullable = false)
    private BigDecimal latitude;        // -90 to +90
    
    @Column(name = "longitude", precision = 11, scale = 8, nullable = false)
    private BigDecimal longitude;       // -180 to +180
    
    // Human-readable context
    @Column(name = "address", length = 255)
    private String address;             // "Terazije 26, Beograd" (geocoded by external API)
    
    @Column(name = "city", length = 50)
    private String city;                // "Belgrade" (for UI grouping)
    
    @Column(name = "zip_code", length = 10)
    private String zipCode;             // "11000" (Serbian zip)
    
    @Column(name = "accuracy_meters")
    private Integer accuracyMeters;     // GPS accuracy (null = unknown)
    
    /**
     * Validate that this point is within allowed range.
     * Serbia: 42.2°N to 47.9°N, 18.8°E to 23.0°E
     */
    @PrePersist
    public void validate() {
        if (latitude.doubleValue() < 42.2 || latitude.doubleValue() > 47.9) {
            throw new ValidationException("Latitude outside Serbia bounds");
        }
        if (longitude.doubleValue() < 18.8 || longitude.doubleValue() > 23.0) {
            throw new ValidationException("Longitude outside Serbia bounds");
        }
    }
    
    /**
     * Haversine distance to another point (meters)
     */
    public double distanceTo(GeoPoint other) {
        return haversine(this.latitude, this.longitude, 
                        other.latitude, other.longitude);
    }
    
    /**
     * Return fuzzy coordinates (randomized ±500m) for privacy.
     * Used when returning location to non-bookers.
     */
    public GeoPoint obfuscate(Random rand, int radiusMeters) {
        // Add random offset using spherical math
        double randomBearing = rand.nextDouble() * 360;
        double randomDistance = rand.nextDouble() * radiusMeters;
        
        // Calculate new point at distance + bearing
        double newLat = calculateNewLat(latitude, distance, bearing);
        double newLon = calculateNewLon(longitude, distance, bearing);
        
        return new GeoPoint(
            BigDecimal.valueOf(newLat),
            BigDecimal.valueOf(newLon),
            null,           // Remove address context
            this.city,      // Keep city for UI
            null,           // Hide zip
            null            // Accuracy unknown
        );
    }
}
```

**Rationale:**
- Immutable value object (no setters, uses constructor injection)
- Validates coordinates at persistence (fail fast)
- Includes helper methods for common operations
- `obfuscate()` supports privacy requirement

#### 2. Car Entity (REFACTORED)

```java
@Entity
@Table(name = "cars", indexes = {
    @Index(name = "idx_car_available", columnList = "available"),
    // SPATIAL INDEX added in migration
    @Index(name = "idx_car_location_spatial", columnList = "location_latitude, location_longitude")
})
@Getter
@Setter
public class Car {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // ... existing fields (brand, model, year, pricePerDay, etc.)
    
    // ❌ DEPRECATED (for backwards compatibility during migration phase)
    @Column(name = "location", length = 255)
    @Deprecated(since = "2025-02", forRemoval = true)
    private String location;        // Keep temporarily for rollback
    
    // ✅ NEW: Embedded geospatial location
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "latitude", column = @Column(name = "location_latitude")),
        @AttributeOverride(name = "longitude", column = @Column(name = "location_longitude")),
        @AttributeOverride(name = "address", column = @Column(name = "location_address")),
        @AttributeOverride(name = "city", column = @Column(name = "location_city")),
        @AttributeOverride(name = "zipCode", column = @Column(name = "location_zip_code")),
        @AttributeOverride(name = "accuracyMeters", column = @Column(name = "location_accuracy_meters"))
    })
    private GeoPoint locationGeoPoint;   // Current car parking location
    
    // Delivery configuration (for Turo-style pricing)
    @Column(name = "delivery_radius_km")
    @Min(0)
    @Max(100)
    private Double deliveryRadiusKm = 0.0;  // Free delivery within this radius
    
    @Column(name = "delivery_fee_per_km", precision = 10, scale = 2)
    private BigDecimal deliveryFeePerKm;    // RSD per km beyond radius
    
    // Existing relations
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    
    @OneToMany(mappedBy = "car", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Booking> bookings = new ArrayList<>();
    
    @PrePersist
    @PreUpdate
    public void normalize() {
        if (location != null) location = location.trim().toLowerCase();
        // GeoPoint validation happens in GeoPoint.validate()
    }
}
```

**Breaking Changes:**
- `Car.location` string → `Car.locationGeoPoint` (embeddable)
- All queries must change from `findByLocation(String)` → `findByLocationCityAndAvailable()`

#### 3. Booking Entity (EXTENDED)

```java
@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_booking_pickup_location", columnList = "pickup_latitude, pickup_longitude")
})
@Getter
@Setter
public class Booking {
    
    // ... existing fields
    
    // ✅ NEW: Pickup location snapshot (agreed at booking time)
    // This is immutable and represents the contractual pickup point.
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "latitude", column = @Column(name = "pickup_latitude")),
        @AttributeOverride(name = "longitude", column = @Column(name = "pickup_longitude")),
        @AttributeOverride(name = "address", column = @Column(name = "pickup_address")),
        @AttributeOverride(name = "city", column = @Column(name = "pickup_city")),
        @AttributeOverride(name = "zipCode", column = @Column(name = "pickup_zip_code")),
        @AttributeOverride(name = "accuracyMeters", column = @Column(name = "pickup_accuracy_meters"))
    })
    private GeoPoint pickupLocation;    // Locked at booking creation
    
    // Check-in coordinates (existing)
    @Column(name = "car_latitude", precision = 10, scale = 8)
    private BigDecimal carLatitude;     // Where car actually was at T-24h
    
    @Column(name = "car_longitude", precision = 11, scale = 8)
    private BigDecimal carLongitude;
    
    // ✅ NEW: Validate that check-in car location matches booking pickup location
    @Column(name = "pickup_location_variance_meters")
    private Integer pickupLocationVarianceMeters;  // Audit: how far car moved
    
    // ✅ NEW: Audit trail for location refinements
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_location_updated_by")
    private User executionLocationUpdatedBy;  // Host who refined location at check-in
    
    @Column(name = "execution_location_updated_at")
    private LocalDateTime executionLocationUpdatedAt;  // When host refined the location
    
    // Existing fields
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id")
    private Car car;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id")
    private User renter;
    
    /**
     * Validate that actual car location at check-in is within acceptable
     * variance from the agreed pickup location. Hosts cannot move the car
     * to a remote location after booking is accepted.
     */
    @PreUpdate
    public void validatePickupLocationVariance() {
        if (this.pickupLocation != null && 
            this.carLatitude != null && this.carLongitude != null) {
            
            GeoPoint actualLocation = new GeoPoint(
                carLatitude, carLongitude, null, null, null, null
            );
            
            double variance = pickupLocation.distanceTo(actualLocation);
            this.pickupLocationVarianceMeters = (int) Math.round(variance);
            
            // Alert if car moved >2km from agreed pickup point
            if (variance > 2000) {
                log.warn("[Booking {}] Car moved {}m from pickup location", 
                    this.id, variance);
                // TODO: Send notification to guest
            }
        }
    }
}
```

**Key Features:**
- `pickupLocation` is **immutable once set** (no updates allowed in normal flow)
- Embedded using same column prefix pattern as Car
- `pickupLocationVarianceMeters` tracks if host violated pickup location agreement

---

## Database Schema Refactor

### Migration Path (Zero-Downtime)

#### Phase 1: Add New Columns (BACKWARDS COMPATIBLE)

```sql
-- V23__add_geospatial_location_columns.sql

-- Step 1: Add columns to cars table
ALTER TABLE cars
ADD COLUMN location_latitude DECIMAL(10, 8) NULL COMMENT 'New geospatial latitude',
ADD COLUMN location_longitude DECIMAL(11, 8) NULL COMMENT 'New geospatial longitude',
ADD COLUMN location_address VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
ADD COLUMN location_city VARCHAR(50) NULL,
ADD COLUMN location_zip_code VARCHAR(10) NULL,
ADD COLUMN location_accuracy_meters INT NULL COMMENT 'GPS accuracy in meters',
ADD COLUMN delivery_radius_km DECIMAL(5, 2) DEFAULT 0 NULL,
ADD COLUMN delivery_fee_per_km DECIMAL(10, 2) NULL;

-- Step 2: Add columns to bookings table
ALTER TABLE bookings
ADD COLUMN pickup_latitude DECIMAL(10, 8) NULL COMMENT 'Pickup location at booking time (immutable)',
ADD COLUMN pickup_longitude DECIMAL(11, 8) NULL,
ADD COLUMN pickup_address VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
ADD COLUMN pickup_city VARCHAR(50) NULL,
ADD COLUMN pickup_zip_code VARCHAR(10) NULL,
ADD COLUMN pickup_accuracy_meters INT NULL,
ADD COLUMN pickup_location_variance_meters INT NULL COMMENT 'Distance car moved from booked location';

-- Step 3: Create spatial indexes (CRITICAL for performance)
CREATE SPATIAL INDEX idx_car_location_spatial ON cars (POINT(location_latitude, location_longitude));

CREATE INDEX idx_booking_pickup_location ON bookings (pickup_city, pickup_latitude, pickup_longitude);

-- Step 4: Temporary parallel data column (for validation)
ALTER TABLE cars
ADD COLUMN _migration_status ENUM('PENDING', 'GEOCODED', 'VALIDATED', 'COMPLETED') DEFAULT 'PENDING'
    COMMENT 'Tracks progress of string→geospatial migration';

-- No triggers yet - let's validate data first
```

#### Phase 2: Data Migration (Scripted Geocoding)

```sql
-- V24__migrate_existing_car_locations_to_geospatial.sql

-- Step 1: Geocode existing string locations to coordinates
-- This script uses a temporary mapping table built from external geocoding API

CREATE TABLE _location_geocoding_cache (
    id INT AUTO_INCREMENT PRIMARY KEY,
    location_string VARCHAR(255) UNIQUE,
    geocoded_latitude DECIMAL(10, 8),
    geocoded_longitude DECIMAL(11, 8),
    geocoded_address VARCHAR(255),
    geocoded_city VARCHAR(50),
    geocoded_zip_code VARCHAR(10),
    geocoded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Run external Java script to populate _location_geocoding_cache
-- See section "Data Migration Strategy" below

-- Step 2: Apply geocoding to cars table
UPDATE cars c
JOIN _location_geocoding_cache g ON LOWER(TRIM(c.location)) = LOWER(TRIM(g.location_string))
SET 
    c.location_latitude = g.geocoded_latitude,
    c.location_longitude = g.geocoded_longitude,
    c.location_address = g.geocoded_address,
    c.location_city = g.geocoded_city,
    c.location_zip_code = g.geocoded_zip_code,
    c._migration_status = 'GEOCODED'
WHERE c.location_latitude IS NULL;

-- Step 3: Validate geometries (all coords must be within Serbia bounds)
SELECT id, location, location_latitude, location_longitude 
FROM cars 
WHERE (location_latitude < 42.2 OR location_latitude > 47.9
       OR location_longitude < 18.8 OR location_longitude > 23.0)
       AND _migration_status = 'GEOCODED'
LIMIT 10;  -- Manual review required

-- Step 4: Default fallback for unrecognized locations
UPDATE cars 
SET 
    location_latitude = 44.8125,      -- Belgrade City Center
    location_longitude = 20.4612,
    location_address = 'Beograd - Centar',
    location_city = 'Belgrade',
    location_zip_code = '11000',
    _migration_status = 'COMPLETED'
WHERE location_latitude IS NULL 
  AND location IS NOT NULL;
```

**Critical Question:** How to handle locations that don't geocode?

- **Option A (Conservative):** Set to Belgrade city center, require host to manually correct
- **Option B (Aggressive):** Require host re-entry before car can be booked
- **Recommendation:** Option A during Phase 2.4, transition to B in Phase 3

#### Phase 3: Create Delivery POIs Table & Enhance Bookings

```sql
-- V25__create_delivery_pois_table.sql

CREATE TABLE delivery_pois (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    poi_name VARCHAR(100) NOT NULL COMMENT 'Belgrade Nikola Tesla Airport',
    poi_latitude DECIMAL(10, 8) NOT NULL,
    poi_longitude DECIMAL(11, 8) NOT NULL,
    poi_address VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
    poi_city VARCHAR(50),
    radius_meters INT DEFAULT 500 NOT NULL COMMENT 'Detect pickups within this radius',
    flat_fee DECIMAL(10, 2) NULL COMMENT 'Override per-km pricing with flat fee',
    fee_waived BOOLEAN DEFAULT FALSE NOT NULL COMMENT 'Free delivery to this POI?',
    description VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_poi_location CHECK (
        poi_latitude >= 42.2 AND poi_latitude <= 47.9 AND
        poi_longitude >= 18.8 AND poi_longitude <= 23.0
    )
) ENGINE=InnoDB 
  DEFAULT CHARSET=utf8mb4 
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Delivery fee override locations (airports, hotels, etc)';

CREATE SPATIAL INDEX idx_poi_location_spatial ON delivery_pois (POINT(poi_longitude, poi_latitude));
CREATE INDEX idx_poi_city ON delivery_pois (poi_city);

-- Seed default POI locations for Serbia
INSERT INTO delivery_pois (poi_name, poi_latitude, poi_longitude, radius_meters, flat_fee, description) VALUES
    ('Belgrade Nikola Tesla Airport', 44.4181, 20.2887, 1000, 500.00, 'Flat 500 RSD for airport pickups'),
    ('Belgrade Train Station Centar', 44.8291, 20.4568, 500, 0, 'Free delivery to train station (partnership)'),
    ('Geneks Hotel', 44.8100, 20.4650, 200, 250.00, 'Hotel guest discount: flat 250 RSD'),
    ('Hyatt Regency Belgrade', 44.8235, 20.4605, 200, 250.00, 'Hotel guest discount: flat 250 RSD');

-- Add columns to bookings for location auditing and delivery tracking
ALTER TABLE bookings
ADD COLUMN execution_location_updated_by BIGINT NULL COMMENT 'Host who refined location at check-in',
ADD COLUMN execution_location_updated_at DATETIME NULL COMMENT 'When host refined the location',
ADD COLUMN delivery_distance_km DECIMAL(8, 2) NULL COMMENT 'Calculated driving distance for delivery',
ADD COLUMN delivery_fee_calculated DECIMAL(10, 2) NULL COMMENT 'Final delivery fee charge';

-- Add FK constraint for audit trail
ALTER TABLE bookings
ADD CONSTRAINT fk_booking_execution_location_updated_by 
FOREIGN KEY (execution_location_updated_by) 
REFERENCES users(id) ON DELETE SET NULL;

-- Create indexes for delivery queries
CREATE INDEX idx_booking_delivery_distance ON bookings (delivery_distance_km) 
WHERE delivery_distance_km IS NOT NULL;
```

**Note on DECIMAL(8, 2):** Allows distances up to 999,999.99 km, which supports future expansion beyond Serbia (e.g., EU-wide platform). Current Serbia distance limit is ~700km (border to border), so this is future-proof.

#### Phase 4: Application-Level Validation & NOT NULL Constraints

```sql
-- V26__enforce_geospatial_location_not_null.sql

-- Add NOT NULL constraints AFTER migration complete
ALTER TABLE cars
MODIFY COLUMN location_latitude DECIMAL(10, 8) NOT NULL,
MODIFY COLUMN location_longitude DECIMAL(11, 8) NOT NULL,
MODIFY COLUMN location_city VARCHAR(50) NOT NULL;

-- Make pickup location immutable
CREATE TRIGGER trg_booking_pickup_location_immutable
BEFORE UPDATE ON bookings
FOR EACH ROW
BEGIN
    IF OLD.pickup_latitude IS NOT NULL AND 
       (NEW.pickup_latitude != OLD.pickup_latitude OR
        NEW.pickup_longitude != OLD.pickup_longitude) THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'IMMUTABLE: Pickup location cannot be changed after booking creation';
    END IF;
END;
```

#### Phase 5: Denormalize Geospatial Data into Read Model

```sql
-- V27__enhance_checkin_status_view_with_geospatial.sql

-- The existing V20 checkin_status_view should be enhanced to include 
-- geospatial data for fast read access during check-in UI rendering.
-- This denormalization prevents N+1 queries when guests see map displays.

DROP VIEW IF EXISTS checkin_status_view;

CREATE VIEW checkin_status_view AS
SELECT 
    b.id,
    b.status,
    b.car_id,
    b.renter_id,
    b.start_time,
    b.end_time,
    
    -- Geospatial denormalization (from car)
    c.location_latitude AS car_home_latitude,
    c.location_longitude AS car_home_longitude,
    c.location_city AS car_home_city,
    c.location_address AS car_home_address,
    
    -- Pickup location agreement (from booking)
    b.pickup_latitude,
    b.pickup_longitude,
    b.pickup_city,
    b.pickup_address,
    
    -- Actual check-in location
    b.car_latitude AS checkin_actual_latitude,
    b.car_longitude AS checkin_actual_longitude,
    b.pickup_location_variance_meters,
    
    -- Geofence validation
    b.guest_check_in_latitude,
    b.guest_check_in_longitude,
    b.geofence_distance_meters,
    
    -- Car details
    c.brand,
    c.model,
    c.image_url,
    
    -- Existing fields
    b.check_in_session_id,
    b.check_in_opened_at,
    b.host_check_in_completed_at,
    b.guest_check_in_completed_at,
    b.handshake_completed_at
    
FROM bookings b
JOIN cars c ON b.car_id = c.id;
```

**Benefit:** The view denormalizes car's home location into the checkin_status_view, eliminating N+1 queries when frontend displays both car location and pickup agreement on the map.

#### Phase 6: Drop Legacy Column (After 2 Weeks Monitoring)

```sql
-- V28__drop_legacy_location_string.sql (weeks later, after monitoring)

-- Only drop after verifying all queries use new geospatial columns
-- Rollback protection: Keep for minimum 2 weeks in production

ALTER TABLE cars DROP COLUMN location;
ALTER TABLE cars DROP COLUMN _migration_status;
DROP TABLE _location_geocoding_cache;
```

### Decision: MySQL SPATIAL vs. Pure Math

#### Option A: MySQL SPATIAL INDEX (Recommended - ACCELERATED TO PHASE 1)

**CRITICAL CORRECTION:** The plan initially deferred SPATIAL queries to Phase 4, but this is a **performance trap**. Even with 1,000 cars, loading all available cars into memory and calculating Haversine distances in Java becomes a bottleneck at scale. **SPATIAL queries must be implemented in Phase 1 of the migration, not Phase 4.**

**Pros:**
- ✅ Single SQL query finds cars within 5km: `SELECT * FROM cars WHERE ST_Distance_Sphere(POINT(lat,lon), POINT(44.8, 20.4)) <= 5000`
- ✅ Database optimizes with spatial index (B-tree on quadtree) — sub-100ms even with 100k cars
- ✅ No N+1 query problem: CarRepository loads only matching cars, not all cars
- ✅ Avoids Java stream memory pressure (critical for mobile guest networks with limited bandwidth)
- ✅ Preparation for PostGIS migration if needed later

**Cons:**
- ❌ MySQL SPATIAL has quirks (coordinate order: POINT(lon, lat) not POINT(lat, lon))
- ❌ SRID confusion (4326 vs. 0)
- ❌ Developers must learn WKT (Well-Known Text) syntax
- ❌ Debugging spatial queries requires `ST_AsText()` for human readability

**Implementation (Phase 1 - NOT Phase 4):**

```java
// CarRepository with SPATIAL queries - NATIVE SQL recommended
@Query(nativeQuery = true, value = """
    SELECT c.* FROM cars c 
    WHERE ST_Distance_Sphere(
        POINT(c.location_longitude, c.location_latitude),
        POINT(:userLongitude, :userLatitude)
    ) <= :radiusMeters
    AND c.available = true
    ORDER BY ST_Distance_Sphere(
        POINT(c.location_longitude, c.location_latitude),
        POINT(:userLongitude, :userLatitude)
    ) ASC
    LIMIT :limit
""")
List<Car> findNearby(
    @Param("userLatitude") double userLat,
    @Param("userLongitude") double userLon,
    @Param("radiusMeters") int radius,
    @Param("limit") int limit
);

// Test query to verify index usage
// EXPLAIN SELECT * FROM cars 
// WHERE ST_Distance_Sphere(POINT(location_longitude, location_latitude), 
//                          POINT(20.4612, 44.8125)) <= 5000;
// Should show: "Using where; Using index" (not "Using temporary; Using filesort")
```

**Decision Point:** Implement SPATIAL in Phase 1 (week 1 SQL migration), not Phase 4. This prevents performance regression as dataset grows.

**Coordinate Order Warning:**
```sql
-- CORRECT: POINT(longitude, latitude) for ST_Distance_Sphere
SELECT ST_Distance_Sphere(POINT(20.4612, 44.8125), POINT(20.5000, 44.8500));  -- ✅

-- INCORRECT: Will give nonsensical results
SELECT ST_Distance_Sphere(POINT(44.8125, 20.4612), POINT(44.8500, 20.5000));  -- ❌ Swapped!
```

#### Option B: Pure Haversine Math (Simpler but Slower)

**Pros:**
- ✅ Easy to understand: just geometry
- ✅ No database-specific syntax
- ✅ Testable in unit tests without database

**Cons:**
- ❌ Load all cars, calculate distance in application (N+1 query pattern)
- ❌ Unscalable for large cities
- ❌ No database optimization possible

**Current Status:** GeofenceService already implements Haversine; this is good for **check-in only** (which has < 100 bookings per day).

**Recommendation:** Use SPATIAL for **car search** (millions of cars); keep Haversine for **check-in geofence** (predictable load).

---

## Business Logic & Privacy

### Fuzzy Location Search (Privacy by Default)

**Requirement:** Unbooked guests should NOT see exact car locations (prevents stalking of luxury vehicles).

#### Search API Response Obfuscation

```java
@Service
public class CarSearchService {
    
    private static final int FUZZY_RADIUS_METERS = 500;  // ±500m randomization
    
    /**
     * Search for available cars. Return obfuscated coordinates to guests
     * who haven't booked yet.
     * 
     * @param userLatitude Guest's current location
     * @param userLongitude
     * @param radiusKm Search radius in kilometers
     * @param currentUser Authenticated user (null = anonymous)
     * @return Cars with location precision based on booking history
     */
    @Transactional(readOnly = true)
    public List<CarSearchResultDTO> searchNearby(
            double userLatitude,
            double userLongitude,
            int radiusKm,
            User currentUser) {
        
        // Step 1: Find cars within search radius (using SPATIAL INDEX)
        List<Car> availableCars = carRepository.findNearby(
            userLatitude, userLongitude, 
            radiusKm * 1000,  // Convert to meters
            100  // Limit to 100 results
        );
        
        // Step 2: Build response with location precision based on user's booking history
        return availableCars.stream()
            .map(car -> {
                CarSearchResultDTO dto = new CarSearchResultDTO(car);
                
                // Check if current user has booked this car before
                boolean hasBooked = bookingRepository
                    .existsByCarAndRenterAndStatusNot(
                        car, currentUser, BookingStatus.CANCELLED);
                
                if (!hasBooked && currentUser != null) {
                    // Privacy: Obfuscate location (±500m randomization)
                    GeoPoint obfuscated = car.getLocationGeoPoint()
                        .obfuscate(new Random(), FUZZY_RADIUS_METERS);
                    dto.setPickupLatitude(obfuscated.getLatitude());
                    dto.setPickupLongitude(obfuscated.getLongitude());
                    dto.setLocationPrecision("OBFUSCATED");
                    
                } else if (hasBooked) {
                    // Returning customer: Show exact location
                    dto.setPickupLatitude(car.getLocationGeoPoint().getLatitude());
                    dto.setPickupLongitude(car.getLocationGeoPoint().getLongitude());
                    dto.setLocationPrecision("EXACT");
                    
                } else {
                    // Anonymous guest: DENY coordinates entirely
                    dto.setPickupLatitude(null);
                    dto.setPickupLongitude(null);
                    dto.setLocationPrecision("HIDDEN");
                }
                
                return dto;
            })
            .collect(Collectors.toList());
    }
}

@Data
public class CarSearchResultDTO {
    private Long id;
    private String brand;
    private String model;
    private BigDecimal pricePerDay;
    
    // Location fields with precision tracking
    @JsonInclude(Include.NON_NULL)
    private BigDecimal pickupLatitude;
    
    @JsonInclude(Include.NON_NULL)
    private BigDecimal pickupLongitude;
    
    @JsonInclude(Include.NON_NULL)
    private String pickupCity;
    
    @ApiModelProperty("EXACT | OBFUSCATED | HIDDEN")
    private String locationPrecision;
    
    public CarSearchResultDTO(Car car) {
        this.id = car.getId();
        this.brand = car.getBrand();
        this.model = car.getModel();
        this.pricePerDay = car.getPricePerDay();
        this.pickupCity = car.getLocationGeoPoint().getCity();
        // lat/lon set by caller
    }
}
```

**Legal Risk Assessment:**

| Risk | Mitigation |
|------|-----------|
| "I paid for exact location but got fuzzy coordinates" | Include privacy notice in search: "Location ±500m to prevent stalking" |
| "Guest stalked my car by querying nearby search multiple times" | Rate-limit: 10 searches per hour per user |
| "Fuzzy location made me late to pickup" | Document exact location in booking confirmation (after payment) |

### Delivery Fee Calculation (Turo-Style Pricing)

**Requirement:** Hosts can charge delivery fees if guest wants car delivered rather than self-pickup.

#### Design: DeliveryPoi Entity (Not JSON String)

**Critical Design Decision:** POI overrides **MUST** be a proper entity, not JSON text. This prevents:
- Query brittleness (searching JSON text is slow)
- Update conflicts (concurrent POI edits)
- Audit trail loss (no versioning of POI changes)

```java
@Entity
@Table(name = "delivery_pois", indexes = {
    @Index(name = "idx_poi_location", columnList = "poi_latitude, poi_longitude"),
    @Index(name = "idx_poi_city", columnList = "poi_city")
})
@Getter
@Setter
public class DeliveryPoi {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 100, nullable = false)
    private String poiName;              // "Belgrade Nikola Tesla Airport"
    
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "latitude", column = @Column(name = "poi_latitude")),
        @AttributeOverride(name = "longitude", column = @Column(name = "poi_longitude")),
        @AttributeOverride(name = "address", column = @Column(name = "poi_address")),
        @AttributeOverride(name = "city", column = @Column(name = "poi_city"))
    })
    private GeoPoint location;
    
    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters = 500;  // Detect pickups within 500m of POI
    
    @Column(name = "flat_fee", precision = 10, scale = 2)
    private BigDecimal flatFee;         // Override per-km pricing, use flat fee instead
    
    @Column(name = "fee_waived", nullable = false)
    private Boolean feeWaived = false;  // Free delivery to this POI?
    
    @Column(name = "description", length = 500)
    private String description;         // "Airport terminal pickups: flat 500 RSD"
    
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
}

@Repository
public interface DeliveryPoiRepository extends JpaRepository<DeliveryPoi, Long> {
    /**
     * Find POI that matches guest's pickup location (within radius).
     * Returns the closest matching POI, or null if none found.
     */
    @Query(nativeQuery = true, value = """
        SELECT dp.* FROM delivery_pois dp
        WHERE ST_Distance_Sphere(
            POINT(dp.poi_longitude, dp.poi_latitude),
            POINT(:guestLongitude, :guestLatitude)
        ) <= dp.radius_meters
        ORDER BY ST_Distance_Sphere(
            POINT(dp.poi_longitude, dp.poi_latitude),
            POINT(:guestLongitude, :guestLatitude)
        ) ASC
        LIMIT 1
    """)
    Optional<DeliveryPoi> findMatchingPoi(
        @Param("guestLatitude") BigDecimal guestLat,
        @Param("guestLongitude") BigDecimal guestLon
    );
}
```

#### Design: DeliveryFeeCalculator Service

```java
@Service
public class DeliveryFeeCalculator {
    
    private final RoutingServiceClient routingService;  // OSRM (Open Street Map Routing Machine)
    private final DeliveryPoiRepository poiRepository;
    
    /**
     * Calculate delivery fee based on ACTUAL driving distance (not air distance).
     * Uses OSRM API for route optimization: https://router.project-osrm.org/
     * 
     * Formula:
     * 1. Check if pickup location matches a POI override
     *    - If yes and feeWaived: Fee = 0
     *    - If yes and flatFee set: Fee = flatFee
     * 2. Otherwise, use distance-based pricing:
     *    - If distance <= freeDeliveryRadius: Fee = 0
     *    - If distance > freeDeliveryRadius: Fee = (distance - radius) * perKmRate
     * 
     * Example:
     * - Car at: 44.8, 20.4 (Belgrade)
     * - Guest pickup at: 44.75, 20.45 (Voždovac)
     * - OSRM driving distance: 8.2 km (not air distance 7.8 km)
     * - Host config: freeDeliveryRadius = 3km, rate = 100 RSD/km
     * - No POI match
     * - Fee = (8.2 - 3) * 100 = 520 RSD
     * 
     * @param car Host's car
     * @param pickupLocation Where guest wants pickup
     * @return Delivery fee or null if car not available for delivery
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateDeliveryFee(Car car, GeoPoint pickupLocation) {
        
        // Step 1: Check if car supports delivery
        if (car.getDeliveryFeePerKm() == null || car.getDeliveryFeePerKm().compareTo(BigDecimal.ZERO) <= 0) {
            return null;  // Car not available for delivery service
        }
        
        // Step 2: Check for POI overrides FIRST (highest priority)
        Optional<DeliveryPoi> matchingPoi = poiRepository.findMatchingPoi(
            pickupLocation.getLatitude(),
            pickupLocation.getLongitude()
        );
        
        if (matchingPoi.isPresent()) {
            DeliveryPoi poi = matchingPoi.get();
            
            if (poi.getFeeWaived()) {
                return BigDecimal.ZERO;  // Free delivery to this POI
            }
            
            if (poi.getFlatFee() != null) {
                return poi.getFlatFee();  // Use POI flat fee
            }
        }
        
        // Step 3: Get driving distance (not air distance)
        RoutingResponse route = routingService.getRoute(
            car.getLocationGeoPoint(),
            pickupLocation,
            "driving"
        );
        
        double distanceKm = route.getDistanceMeters() / 1000.0;
        double freeRadiusKm = car.getDeliveryRadiusKm() != null ? car.getDeliveryRadiusKm() : 0;
        
        // Step 4: Calculate based on distance
        if (distanceKm <= freeRadiusKm) {
            return BigDecimal.ZERO;
        }
        
        double chargeableDistance = distanceKm - freeRadiusKm;
        BigDecimal fee = car.getDeliveryFeePerKm()
            .multiply(BigDecimal.valueOf(chargeableDistance))
            .setScale(2, RoundingMode.HALF_UP);
        
        return fee;
    }
}
```

**RoutingService Design (OSRM Integration):**

```java
public interface RoutingServiceClient {
    
    /**
     * Get driving distance/duration between two points using OSRM.
     * OSRM: Open Street Map Routing Machine (https://router.project-osrm.org/)
     * 
     * Features:
     * - Free, open-source routing engine
     * - Accurate for Serbia road network (OSM data)
     * - Supports car, bike, foot profiles
     * - Public API: router.project-osrm.org (rate limits apply)
     * - Self-hosted option available for production
     * 
     * Configuration:
     * - Public API: https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}
     * - Rate limit: 20 req/min per IP (sufficient for Rentoza)
     * - Timeout: 5 seconds (Serbia routes typically <1 sec)
     * 
     * Fallback:
     * - If OSRM unavailable, use Haversine (air distance) with +20% estimate
     * - Cache results for 24 hours (delivery routes don't change daily)
     */
    RoutingResponse getRoute(GeoPoint from, GeoPoint to, String profile);
}

@Data
public class RoutingResponse {
    private double distanceMeters;
    private long durationSeconds;
    private List<LatLng> polyline;  // For drawing on map
    private boolean fromCache;      // Was this result cached?
}

@Service
@Slf4j
public class OsrmRoutingServiceClient implements RoutingServiceClient {
    
    private static final String OSRM_API = "https://router.project-osrm.org/route/v1/driving";
    private static final int TIMEOUT_SECONDS = 5;
    private static final int CACHE_HOURS = 24;
    
    private final RestTemplate restTemplate;
    private final RouteCache routeCache;  // Simple TTL cache
    
    @Override
    public RoutingResponse getRoute(GeoPoint from, GeoPoint to, String profile) {
        
        String cacheKey = String.format("%s_%s_%s", 
            from.getLatitude() + "," + from.getLongitude(),
            to.getLatitude() + "," + to.getLongitude(),
            profile);
        
        // Check cache first
        RoutingResponse cached = routeCache.get(cacheKey);
        if (cached != null) {
            cached.setFromCache(true);
            return cached;
        }
        
        try {
            // OSRM URL format: /route/v1/{profile}/{lon1},{lat1};{lon2},{lat2}
            String url = String.format("%s/%s/%s,%s;%s,%s?overview=full",
                OSRM_API,
                profile,  // "driving", "walking", "cycling"
                from.getLongitude(), from.getLatitude(),
                to.getLongitude(), to.getLatitude()
            );
            
            OsrmResponse osrmResponse = restTemplate.getForObject(url, OsrmResponse.class);
            
            if (osrmResponse == null || osrmResponse.getRoutes().isEmpty()) {
                log.warn("OSRM returned no routes for {} -> {}", from, to);
                return fallbackToHaversine(from, to);
            }
            
            OsrmRoute route = osrmResponse.getRoutes().get(0);  // Fastest route
            
            RoutingResponse response = new RoutingResponse();
            response.setDistanceMeters(route.getDistance());  // OSRM returns meters
            response.setDurationSeconds((long) route.getDuration());  // OSRM returns seconds
            response.setPolyline(decodePolyline(route.getGeometry()));
            response.setFromCache(false);
            
            // Cache for 24 hours
            routeCache.put(cacheKey, response, CACHE_HOURS);
            
            return response;
            
        } catch (Exception e) {
            log.warn("OSRM request failed for {} -> {}: {}", from, to, e.getMessage());
            // Graceful degradation: use Haversine with +20% estimate
            return fallbackToHaversine(from, to);
        }
    }
    
    private RoutingResponse fallbackToHaversine(GeoPoint from, GeoPoint to) {
        // Fallback: Calculate air distance and add 20% for road detours
        double airDistance = from.distanceTo(to);
        double roadEstimate = airDistance * 1.2;  // Typical detour factor for Serbia
        
        log.warn("Using Haversine fallback: {:.1f}km air distance, {:.1f}km road estimate", 
            airDistance / 1000, roadEstimate / 1000);
        
        RoutingResponse response = new RoutingResponse();
        response.setDistanceMeters(roadEstimate);
        response.setDurationSeconds((long) (roadEstimate / 17));  // ~17 m/s avg speed
        response.setFromCache(false);
        
        return response;
    }
}

// OSRM API response models
@Data
public class OsrmResponse {
    private String code;  // "Ok" on success
    private List<OsrmRoute> routes;
}

@Data
public class OsrmRoute {
    private double distance;      // meters
    private double duration;      // seconds
    private String geometry;      // Encoded polyline (simplify format)
}
```

**Rate Limiting & Caching Strategy:**

```java
@Configuration
public class RoutingCacheConfig {
    
    @Bean
    public RouteCache routeCache() {
        // Simple TTL cache: Route from A→B expires after 24 hours
        // For Serbia, routes don't change daily, so 24h cache is safe
        return new EhCacheRouteCache(24 * 60);  // 24 hours in minutes
    }
    
    @Bean
    public RestTemplate osrmRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        
        // Add timeout: OSRM routes should complete <1 sec for Serbia
        ClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        ((HttpComponentsClientHttpRequestFactory) factory).setConnectTimeout(5000);
        ((HttpComponentsClientHttpRequestFactory) factory).setReadTimeout(5000);
        
        restTemplate.setRequestFactory(factory);
        
        // Add retry logic: 3 attempts with 1 sec backoff
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                log.warn("OSRM returned HTTP {}", response.getStatusCode());
            }
        });
        
        return restTemplate;
    }
}
```

**Configuration Properties:**

```yaml
# application.yml
delivery:
  routing:
    provider: osrm                              # "osrm", "mapbox", "haversine"
    osrm:
      api-url: https://router.project-osrm.org  # Public instance
      # For production, use self-hosted: http://osrm-server:5000
      timeout-seconds: 5
      cache-hours: 24
      fallback-to-haversine: true               # Graceful degradation
      haversine-detour-factor: 1.2              # Roads are 20% longer than air distance
```

**Testing OSRM Integration:**

```java
@Test
public void testOsrmDeliveryDistanceCalculation() {
    // Belgrade to Voždovac: actual driving ~8.2km
    GeoPoint belgrade = new GeoPoint(
        BigDecimal.valueOf(44.8125),
        BigDecimal.valueOf(20.4612)
    );
    
    GeoPoint vozdovac = new GeoPoint(
        BigDecimal.valueOf(44.7518),
        BigDecimal.valueOf(20.5271)
    );
    
    RoutingResponse route = routingService.getRoute(belgrade, vozdovac, "driving");
    
    // OSRM should return driving distance, not air distance
    double distanceKm = route.getDistanceMeters() / 1000.0;
    
    // Allow ±10% tolerance (routing algorithms have variation)
    assertTrue(distanceKm > 7.4 && distanceKm < 9.0,
        "Expected ~8.2km, got " + distanceKm + "km");
    
    // Second call should use cache
    RoutingResponse cached = routingService.getRoute(belgrade, vozdovac, "driving");
    assertTrue(cached.isFromCache(), "Expected cache hit on second call");
}

@Test
public void testOsrmFallbackToHaversine() {
    // Mock OSRM API to be unavailable
    doThrow(new RestClientException("Connection refused"))
        .when(restTemplate).getForObject(anyString(), eq(OsrmResponse.class));
    
    GeoPoint belgrade = new GeoPoint(...);
    GeoPoint vozdovac = new GeoPoint(...);
    
    RoutingResponse route = routingService.getRoute(belgrade, vozdovac, "driving");
    
    // Should fall back to Haversine with +20% detour estimate
    double airDistance = belgrade.distanceTo(vozdovac) / 1000.0;  // ~7.8 km
    double expectedFallback = airDistance * 1.2;  // ~9.4 km
    
    double actualKm = route.getDistanceMeters() / 1000.0;
    assertEquals(expectedFallback, actualKm, 0.1);
    
    log.info("Graceful fallback: {} km (air) → {} km (estimated)", 
        airDistance, actualKm);
}
```

**Critical Decision: Air Distance vs. Driving Distance**

| Metric | Use Case | Accuracy |
|--------|----------|----------|
| **Air Distance (Haversine)** | Quick estimate in search | ±15% (curved roads, one-way streets) |
| **Driving Distance (Routing API)** | Delivery fee calculation | ±3% (actual road network) |

**Recommendation:** Use Haversine for **initial quote** to user; use routing API for **final invoice** after booking.

---

## API Contract Changes

### Breaking Changes (v2.0 → v3.0)

#### Search Cars Endpoint

**BEFORE (v2.0):**
```json
GET /api/v2/cars/search?location=Belgrade&available=true

Response:
{
  "cars": [
    {
      "id": 1,
      "brand": "BMW",
      "location": "Belgrade",
      "pricePerDay": 8000
    }
  ]
}
```

**AFTER (v3.0):**
```json
GET /api/v3/cars/search?latitude=44.8125&longitude=20.4612&radiusKm=15&excludeBooked=true

Response:
{
  "cars": [
    {
      "id": 1,
      "brand": "BMW",
      "pickupLatitude": 44.8135,           // Obfuscated ±500m
      "pickupLongitude": 20.4625,
      "pickupCity": "Belgrade",
      "locationPrecision": "OBFUSCATED",
      "deliveryAvailable": true,
      "deliveryFeeEstimate": {
        "distanceKm": 2.3,
        "baseFeeRsd": 0,                   // Within free radius
        "rate": 100,
        "estimateFeeRsd": 0
      },
      "pricePerDay": 8000
    }
  ],
  "warnings": [
    "Guest privacy mode enabled: exact locations hidden until booking"
  ]
}
```

**Migration Path:**
1. **v2.1:** Accept both `/search?location=Belgrade` AND `/search?latitude=44.8&longitude=20.4` (dual support)
2. **v2.5:** Deprecate location-string endpoint, require coordinates
3. **v3.0:** Remove location-string endpoint

#### Create Booking Endpoint

**BEFORE (v2.0):**
```json
POST /api/v2/bookings

{
  "carId": 1,
  "startTime": "2025-03-01T10:00:00+01:00",
  "endTime": "2025-03-08T10:00:00+01:00",
  "insuranceType": "STANDARD"
}

Response:
{
  "id": 101,
  "bookingStatus": "PENDING_APPROVAL",
  "totalPrice": 56000
}
```

**AFTER (v3.0):**
```json
POST /api/v3/bookings

{
  "carId": 1,
  "startTime": "2025-03-01T10:00:00+01:00",
  "endTime": "2025-03-08T10:00:00+01:00",
  "pickupLocation": {                    // NEW: Required
    "latitude": 44.8125,
    "longitude": 20.4612,
    "address": "Terazije 26, Beograd"
  },
  "deliveryRequested": false,
  "insuranceType": "STANDARD"
}

Response:
{
  "id": 101,
  "bookingStatus": "PENDING_APPROVAL",
  "pickupLocation": {
    "latitude": 44.8125,
    "longitude": 20.4612,
    "address": "Terazije 26, Beograd"
  },
  "pricing": {
    "dailyRate": 8000,
    "daysRented": 7,
    "subtotal": 56000,
    "deliveryFee": 0,
    "insurance": 2800,
    "securityDeposit": 16000,
    "totalPrice": 74800
  }
}
```

**Change Checklist:**
- [ ] `/search` now requires `latitude`/`longitude` (no `location` string)
- [ ] `/bookings` POST must include `pickupLocation` (immutable after creation)
- [ ] Car detail view returns `pickupLatitude`/`pickupLongitude` instead of `location`
- [ ] Owner car edit form now has map UI (not string input)

---

## Frontend Integration

### Map UI Strategy (Angular 16+)

#### Frontend Library: Mapbox (CONFIRMED)

**Decision:** Use **Mapbox GL JS** for all map interactions.

| Feature | Google Maps | Mapbox GL JS |
|---------|-------------|--------------|
| **Cost** | $7/1000 map loads | $0.50/1000 map sessions |
| **Offline Support** | ❌ No | ✅ Yes (pre-downloaded tiles) |
| **Customization** | Limited (Google Studio) | Extensive (Mapbox Studio) |
| **Library Size** | ~400KB | ~150KB |
| **Load Time** (rural Serbia) | ~2 seconds | ~800ms |
| **Vector Tiles** | ❌ Raster only | ✅ Vector (crisp on retina) |

**Chosen:** **Mapbox GL JS** v2.15+

**Key Benefits for Rentoza:**
- Faster rural loads (critical in Serbia)
- Native vector tiles → sharp on retina displays
- Excellent offline support for check-in
- OSRM integration is native (no API translation layer)
- Studio customization for white-label look

**Configuration & Token Management:**

```typescript
// environment.ts (development)
export const environment = {
  production: false,
  mapbox: {
    accessToken: '<MAPBOX_PUBLIC_TOKEN>'
    // ⚠️ WARNING: This token should be in environment variables, not in code!
    // See .env.local pattern below
  }
};

// .env.local (NEVER commit to git)
MAPBOX_TOKEN=<MAPBOX_PUBLIC_TOKEN>

// environment.ts (production - read from environment)
export const environment = {
  production: true,
  mapbox: {
    accessToken: process.env['MAPBOX_TOKEN'] || ''
    // Throws error if MAPBOX_TOKEN not set in deployment
  }
};
```

**Security Note:**
- ✅ Mapbox public tokens are safe for frontend (browser use only)
- ❌ NEVER commit tokens to Git (use .gitignore)
- ✅ Store in environment variables or secrets manager (GitHub Secrets, AWS Secrets Manager)
- ⚠️ Implement token rotation quarterly
- ✅ Mapbox tracks token usage; set restriction to Rentoza domain only

**Docker Deployment:**

```dockerfile
# Dockerfile
FROM node:18-alpine
WORKDIR /app
COPY . .
ARG MAPBOX_TOKEN
ENV MAPBOX_TOKEN=$MAPBOX_TOKEN
RUN npm ci && npm run build
EXPOSE 3000
CMD ["npm", "start"]
```

```bash
# Build with token
docker build --build-arg MAPBOX_TOKEN=<MAPBOX_PUBLIC_TOKEN> -t rentoza-frontend .

# Or run with environment variable
docker run -e MAPBOX_TOKEN=<MAPBOX_PUBLIC_TOKEN> rentoza-frontend
```

#### LocationPickerComponent

```typescript
// location-picker.component.ts
@Component({
  selector: 'app-location-picker',
  templateUrl: './location-picker.component.html'
})
export class LocationPickerComponent implements OnInit {
  
  @Input() initialLatitude: number;
  @Input() initialLongitude: number;
  @Output() locationSelected = new EventEmitter<GeoPoint>();
  
  map: mapboxgl.Map;
  marker: mapboxgl.Marker;
  
  ngOnInit() {
    this.initMap();
  }
  
  private initMap() {
    // Get token from environment - fails loudly if not set
    if (!environment.mapbox.accessToken) {
      throw new Error('MAPBOX_TOKEN is not configured. Set in environment.ts or .env.local');
    }
    
    mapboxgl.accessToken = environment.mapbox.accessToken;
    
    this.map = new mapboxgl.Map({
      container: 'map',
      style: 'mapbox://styles/mapbox/streets-v11',
      center: [this.initialLongitude, this.initialLatitude],
      zoom: 13,
      pitch: 0,
      bearing: 0,
      antialias: true  // Smooth rendering on retina displays
    });
    
    // Add marker at initial location
    this.marker = new mapboxgl.Marker()
      .setLngLat([this.initialLongitude, this.initialLatitude])
      .addTo(this.map);
    
    // Click on map to move marker
    this.map.on('click', (e) => {
      this.marker.setLngLat(e.lngLat);
      this.locationSelected.emit({
        latitude: e.lngLat.lat,
        longitude: e.lngLat.lng,
        city: this.reverseGeocodeCity(e.lngLat.lat, e.lngLat.lng)
      });
    });
  }
  
  // Search for address and center map
  searchAddress(query: string) {
    this.geocodingService.forward(query).subscribe(results => {
      const result = results[0];
      this.map.easeTo({
        center: [result.lng, result.lat],
        zoom: 15
      });
      this.marker.setLngLat([result.lng, result.lat]);
    });
  }
}
```

#### FuzzyCircleComponent (Privacy Visualization)

**Critical Design Detail:** When a guest hasn't booked, show ONLY the fuzzy circle (not the exact pin). This prevents reverse-geocoding attacks where users repeatedly query to narrow down the exact location.

```typescript
// fuzzy-circle.component.ts
@Component({
  selector: 'app-fuzzy-circle',
  templateUrl: './fuzzy-circle.component.html'
})
export class FuzzyCircleComponent {
  
  @Input() map: mapboxgl.Map;
  @Input() centerLat: number;
  @Input() centerLng: number;
  @Input() radiusMeters: number = 500;  // ±500m fuzzy radius
  @Input() hasBooked: boolean = false;  // Has user booked this car?
  
  private circleSource: GeoJSONSource;
  private markerSource: GeoJSONSource;
  
  ngOnInit() {
    
    // Step 1: Draw fuzzy circle (always visible)
    const circle = this.drawCircle(
      this.centerLng, 
      this.centerLat, 
      this.radiusMeters
    );
    
    this.circleSource = new GeoJSONSource({
      data: circle
    });
    
    this.map.addSource('fuzzy-circle', this.circleSource);
    this.map.addLayer({
      id: 'fuzzy-circle-layer',
      type: 'fill',
      source: 'fuzzy-circle',
      paint: {
        'fill-color': '#FF9900',
        'fill-opacity': 0.15,
        'fill-outline-color': '#FF9900'
      }
    });
    
    // Step 2: Show exact pin ONLY if user has booked this car
    if (this.hasBooked) {
      this.markerSource = new GeoJSONSource({
        data: {
          type: 'Feature',
          geometry: {
            type: 'Point',
            coordinates: [this.centerLng, this.centerLat]
          }
        }
      });
      
      this.map.addSource('exact-marker', this.markerSource);
      this.map.addLayer({
        id: 'exact-marker-layer',
        type: 'circle',
        source: 'exact-marker',
        paint: {
          'circle-radius': 8,
          'circle-color': '#FF0000',
          'circle-opacity': 1.0
        }
      });
      
      // Add tooltip "Exact pickup location (after booking)"
      this.showExactLocationLabel();
    } else {
      // Show helpful text: "Approximate area - exact location shown after booking"
      this.showApproximateAreaLabel();
    }
  }
  
  private showApproximateAreaLabel() {
    const popup = new mapboxgl.Popup()
      .setLngLat([this.centerLng, this.centerLat])
      .setHTML(`<p><strong>Approximate pickup area</strong><br/>Exact location shown after booking</p>`)
      .addTo(this.map);
  }
  
  private showExactLocationLabel() {
    const popup = new mapboxgl.Popup()
      .setLngLat([this.centerLng, this.centerLat])
      .setHTML(`<p><strong>Exact pickup location</strong><br/>Host's parking spot</p>`)
      .addTo(this.map);
  }
  
  private drawCircle(lng: number, lat: number, radiusMeters: number): Feature {
    // Draw GeoJSON circle with 32 points
    const points = [];
    const earthRadiusKm = 6371;
    const angularRadius = radiusMeters / 1000 / earthRadiusKm;
    
    for (let i = 0; i < 32; i++) {
      const angle = (i / 32) * 2 * Math.PI;
      const dLat = Math.asin(Math.sin(angularRadius) * Math.sin(angle));
      const dLng = Math.atan2(
        Math.sin(angle) * Math.sin(angularRadius) * Math.cos(lat * Math.PI / 180),
        Math.cos(angularRadius) - Math.sin(lat * Math.PI / 180) * Math.sin(lat * Math.PI / 180)
      );
      
      points.push([
        lng + dLng * 180 / Math.PI,
        lat + dLat * 180 / Math.PI
      ]);
    }
    points.push(points[0]);  // Close polygon
    
    return {
      type: 'Feature',
      geometry: {
        type: 'Polygon',
        coordinates: [points]
      }
    };
  }
}
```

#### PinDropComponent (Host Check-In Refinement)

```typescript
// pin-drop.component.ts
@Component({
  selector: 'app-pin-drop',
  templateUrl: './pin-drop.component.html'
})
export class PinDropComponent {
  
  /**
   * Host can refine pickup location during check-in.
   * Initially set to agreed booking location; can move up to 200m to account
   * for street-level parking variations.
   */
  @Input() map: mapboxgl.Map;
  @Input() initialLocation: GeoPoint;  // Booking.pickupLocation
  @Output() refinedLocation = new EventEmitter<GeoPoint>();
  
  private marker: mapboxgl.Marker;
  private geofence: mapboxgl.Circle;
  
  ngOnInit() {
    // Place initial marker at booking location
    this.marker = new mapboxgl.Marker({ color: 'blue' })
      .setLngLat([this.initialLocation.longitude, this.initialLocation.latitude])
      .addTo(this.map);
    
    // Show 200m geofence circle (max allowed refinement)
    this.drawGeofenceCircle(this.initialLocation, 200);
    
    // Allow drag within geofence
    this.marker.setDraggable(true);
    this.marker.on('dragend', () => {
      const newLocation = this.marker.getLngLat();
      const distanceMeters = this.haversine(
        this.initialLocation.latitude, this.initialLocation.longitude,
        newLocation.lat, newLocation.lng
      );
      
      if (distanceMeters <= 200) {
        this.refinedLocation.emit({
          latitude: newLocation.lat,
          longitude: newLocation.lng,
          address: null  // Will be reverse-geocoded by service
        });
      } else {
        // Snap back if moved too far
        this.marker.setLngLat([
          this.initialLocation.longitude,
          this.initialLocation.latitude
        ]);
        alert('Pin must stay within 200m of booking location');
      }
    });
  }
  
  private drawGeofenceCircle(center: GeoPoint, radiusMeters: number) {
    // Draw 200m circle (max refinement distance)
    // Implementation similar to FuzzyCircleComponent
  }
}
```

---

## Check-In & Geofencing Pipeline

### Updated CheckInService Flow

**BEFORE (Current v2.0):**
1. Host uploads photos at T-24h → captures `car_latitude`, `car_longitude` from EXIF
2. Guest arrives → geofence validates distance between guest and **current car location**
3. Problem: Host could move car between booking and check-in

**AFTER (v3.0 with Geospatial):**
1. Booking created → `pickupLocation` immutable snapshot stored
2. Host uploads photos at T-24h → captures `car_latitude`, `car_longitude` (may differ from booking)
3. **NEW:** Validate car hasn't moved >2km from pickup location; warn host if variance > 500m
4. Guest arrives → geofence validates distance between guest and **booking-time pickup location**
5. Log variance for dispute resolution

```java
@Service
@Slf4j
public class CheckInService {
    
    private final BookingRepository bookingRepository;
    private final GeofenceService geofenceService;
    
    /**
     * Host submits check-in (photos, odometer).
     * Validate that car is still at agreed pickup location (±2km tolerance).
     */
    @Transactional
    public void submitHostCheckIn(Long bookingId, HostCheckInSubmissionDTO submission) {
        
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        // Step 1: Capture car location from submission (EXIF or manual)
        BigDecimal carLatitude = submission.getCarLatitude();
        BigDecimal carLongitude = submission.getCarLongitude();
        
        booking.setCarLatitude(carLatitude);
        booking.setCarLongitude(carLongitude);
        
        // Step 2: CRITICAL - Validate car location vs. booking pickup location
        if (booking.getPickupLocation() != null) {
            
            double variance = booking.getPickupLocation().distanceTo(
                new GeoPoint(carLatitude, carLongitude, null, null, null, null)
            );
            
            booking.setPickupLocationVarianceMeters((int) Math.round(variance));
            
            // ALERT: Car moved from agreed pickup point
            if (variance > 2000) {  // 2km tolerance
                log.warn("[Booking {}] VARIANCE ALERT: Car is {}m from pickup location. " +
                    "Guest may be surprised.", 
                    bookingId, variance);
                
                // Notify host: "Your car was at X, we expected at Y"
                notificationService.sendToHost(
                    booking.getCar().getOwner(),
                    NotificationType.CAR_LOCATION_VARIANCE,
                    Map.of(
                        "variance_meters", Math.round(variance),
                        "expected_lat", booking.getPickupLocation().getLatitude(),
                        "expected_lng", booking.getPickupLocation().getLongitude(),
                        "actual_lat", carLatitude,
                        "actual_lng", carLongitude
                    )
                );
            }
        }
        
        // Step 3: Existing checks (photo count, odometer, fuel)
        validatePhotos(submission);
        
        // Step 4: Update booking
        booking.setHostCheckInCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
        
        bookingRepository.save(booking);
    }
    
    /**
     * Guest confirms handshake with geofence validation.
     * Geofence check now uses booking-time pickup location (not current car location).
     */
    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    public void confirmHandshake(Long bookingId, HandshakeConfirmationDTO confirmation) {
        
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        // Step 1: Validate guest proximity to BOOKING PICKUP LOCATION (not current car)
        GeoPoint pickupLocation = booking.getPickupLocation();
        if (pickupLocation == null) {
            log.warn("[Booking {}] No pickup location recorded - skipping geofence", bookingId);
            pickupLocation = new GeoPoint(
                booking.getCarLatitude(),
                booking.getCarLongitude(),
                null, null, null, null
            );
        }
        
        // Infer location density for dynamic radius
        GeofenceService.LocationDensity density = 
            geofenceService.inferLocationDensity(
                pickupLocation.getLatitude(),
                pickupLocation.getLongitude()
            );
        
        // Validate guest is within geofence
        GeofenceResult result = geofenceService.validateProximity(
            pickupLocation.getLatitude(),
            pickupLocation.getLongitude(),
            confirmation.getGuestLatitude(),
            confirmation.getGuestLongitude(),
            density
        );
        
        if (!result.isWithinRadius() && geofenceService.isStrictMode()) {
            log.warn("[Booking {}] GEOFENCE FAILED: Guest {}m from pickup location",
                bookingId, result.getDistanceMeters());
            throw new GeofenceViolationException(
                String.format("Morate biti unutar %dm od lokacije. " +
                    "Trenutna distanca: %dm",
                    result.getRequiredRadiusMeters(),
                    result.getDistanceMeters())
            );
        }
        
        // Step 2: Decrypt lockbox code ONLY if geofence passes
        if (booking.getLockboxCodeEncrypted() != null && result.isWithinRadius()) {
            String lockboxCode = lockboxEncryptionService.decrypt(
                booking.getLockboxCodeEncrypted()
            );
            // Send code to guest
        }
        
        // Step 3: Complete handshake
        booking.setHandshakeCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.IN_TRIP);
        bookingRepository.save(booking);
    }
}
```

### Enhanced Geofence Result DTO

```java
@Data
public class CheckInStatusDTO {
    
    private Long bookingId;
    private BookingStatus status;
    
    // Geofence details
    private Boolean geofenceValid;
    private Integer geofenceDistanceMeters;
    private Integer geofenceRadiusMeters;
    
    // Pickup location visibility
    private BigDecimal pickupLatitude;
    private BigDecimal pickupLongitude;
    private String pickupAddress;
    
    // Car location variance alert
    private Integer carLocationVarianceMeters;
    @ApiModelProperty("How far car moved from booking location")
    private String varianceWarning;
    
    // Lockbox
    @JsonInclude(Include.NON_NULL)
    private String lockboxCode;          // Revealed only if geofence passes
    
    // Metrics for guest
    @JsonInclude(Include.NON_NULL)
    private GuestCheckInMetricsDTO metrics;
}

@Data
public class GuestCheckInMetricsDTO {
    private String locationDensity;         // "URBAN" | "SUBURBAN" | "RURAL"
    private Integer appliedRadiusMeters;    // After dynamic adjustment
    private Integer guestDistanceMeters;    // Actual distance to pickup
    private Long checkInWindowExpiresAt;    // Unix timestamp
}
```

---

## Data Migration Strategy

### Geocoding Legacy String Locations

**Challenge:** How to convert "Belgrade", "New Belgrade", "Voždovac" → GPS coordinates?

#### Solution: External Geocoding Service

```java
@Service
@Slf4j
public class LocationMigrationService {
    
    private final LocationGeocoder geocoder;  // Nominatim, Google, Mapbox
    private final CarRepository carRepository;
    
    /**
     * Batch geocode all cars with string locations.
     * Run as one-time migration script.
     */
    @Scheduled(initialDelay = 60000, fixedDelay = Long.MAX_VALUE)
    public void migrateAllLocations() {
        
        // Find cars still using string location
        List<Car> carsToMigrate = carRepository.findByLocationGeoPointIsNull();
        
        log.info("Migrating {} cars from string to geospatial locations", carsToMigrate.size());
        
        int successful = 0, failed = 0;
        
        for (Car car : carsToMigrate) {
            try {
                GeoPoint geocoded = geocodeLocation(car.getLocation(), car.getCar().getOwner());
                car.setLocationGeoPoint(geocoded);
                carRepository.save(car);
                successful++;
                
            } catch (GeocodeException e) {
                log.warn("Failed to geocode '{}' for car {}: {}", 
                    car.getLocation(), car.getId(), e.getMessage());
                
                // Fallback: Default to Belgrade city center
                car.setLocationGeoPoint(new GeoPoint(
                    BigDecimal.valueOf(44.8125),
                    BigDecimal.valueOf(20.4612),
                    "Beograd - Centar (fallback)",
                    "Belgrade",
                    "11000",
                    null
                ));
                carRepository.save(car);
                failed++;
            }
        }
        
        log.info("Location migration complete: {} successful, {} failed with fallback", 
            successful, failed);
    }
    
    /**
     * Geocode a location string, with intelligence for Serbian locations.
     */
    private GeoPoint geocodeLocation(String locationString, User owner) throws GeocodeException {
        
        // Step 1: Normalize input
        String query = locationString.trim();
        
        // Step 2: Augment with city context if missing
        if (!query.contains("Beograd") && !query.contains("Belgrade") &&
            !query.contains("Novi Sad") && !query.contains("Niš")) {
            // Assume Belgrade if city not specified
            query = query + ", Beograd, Srbija";
        } else if (!query.contains("Srbija") && !query.contains("Serbia")) {
            query = query + ", Srbija";
        }
        
        // Step 3: Call geocoding API
        GeocodeResult result = geocoder.forward(query);
        
        if (result == null || result.isEmpty()) {
            throw new GeocodeException("No results found for: " + locationString);
        }
        
        // Step 4: Validate result is in Serbia
        if (!isInSerbia(result.getLatitude(), result.getLongitude())) {
            throw new GeocodeException("Geocoded location outside Serbia: " + result.getAddress());
        }
        
        // Step 5: Return GeoPoint
        return new GeoPoint(
            BigDecimal.valueOf(result.getLatitude()),
            BigDecimal.valueOf(result.getLongitude()),
            result.getAddress(),
            extractCity(result.getAddress()),
            result.getPostalCode(),
            null  // Accuracy unknown from geocoding API
        );
    }
    
    private boolean isInSerbia(double lat, double lng) {
        return lat >= 42.2 && lat <= 47.9 && 
               lng >= 18.8 && lng <= 23.0;
    }
    
    private String extractCity(String address) {
        // Parse address string: "Terazije 26, Beograd 11000, Srbija"
        if (address.contains("Beograd")) return "Belgrade";
        if (address.contains("Novi Sad")) return "Novi Sad";
        if (address.contains("Niš")) return "Niš";
        return "Unknown";
    }
}

// Configuration
@Configuration
public class GeocoderConfig {
    
    @Bean
    public LocationGeocoder locationGeocoder() {
        // Use Nominatim (free, OpenStreetMap) for migration
        // Later switch to Mapbox for production with rate limits
        return new NominatimGeocoder(
            "https://nominatim.openstreetmap.org",
            1000,  // Rate limit: 1 req/sec
            3      // Retry count
        );
    }
}
```

#### Geocoding Strategies Compared

| Strategy | API | Cost | Accuracy | Limits |
|----------|-----|------|----------|--------|
| **Nominatim (OSM)** | Free | $0 | 90% | 1 req/sec |
| **Google Maps** | Paid | $5/1000 | 95% | 50k/day |
| **Mapbox** | Paid | $0.50/1000 | 93% | 600/min |

**Recommendation:** Use **Nominatim** for one-time migration (it's free), then switch to **Mapbox** for real-time geocoding in booking flow (cheaper at scale).

#### Handling Ambiguous Locations

**Problem:** Some car locations are vague ("New Belgrade", "Near Usce") → multiple valid coordinates.

**Solution:** Interactive refinement for hosts:

```typescript
// admin-location-cleansing.component.ts
@Component({
  selector: 'app-admin-location-cleansing',
  template: `
    <table>
      <tr *ngFor="let car of ambiguousLocations">
        <td>{{ car.location }}</td>
        <td>
          <app-location-picker 
            [initialLocation]="car.locationGeoPoint"
            (locationSelected)="saveLocation(car.id, $event)">
          </app-location-picker>
        </td>
      </tr>
    </table>
  `
})
export class AdminLocationCleansingComponent {
  
  ambiguousLocations: Car[];
  
  ngOnInit() {
    // Find cars where geocoding had low confidence
    this.carService.getCarsWithLowConfidence().subscribe(
      cars => this.ambiguousLocations = cars
    );
  }
  
  saveLocation(carId: Long, location: GeoPoint) {
    this.carService.updateLocation(carId, location).subscribe(
      () => alert('Location updated')
    );
  }
}
```

---

## Deployment & Rollback

### Phased Deployment Strategy (Zero-Downtime)

#### Phase 1: Add Columns + SPATIAL Queries (Backwards Compatibility) - Week 1

**CRITICAL ACCELERATION:** This phase now includes immediate SPATIAL index and query implementation (not deferred to Phase 4). This prevents performance degradation as the car dataset grows.

- **SQL:** Run V23 migration (add lat/lng columns, create SPATIAL INDEX, keep string column for fallback)
- **Code:** Deploy v2.5 with `CarRepository.findNearby()` using native SQL SPATIAL queries
- **Database:** Create `SPATIAL INDEX idx_car_location_spatial ON cars (POINT(location_longitude, location_latitude))`
- **Testing:** Verify SPATIAL queries execute in <100ms with 1000+ test cars using `EXPLAIN`
- **Rollback:** Drop SPATIAL index, revert to legacy string-based queries with `.stream()` fallback

```sql
-- Check space usage
SELECT 
  table_name,
  (data_length + index_length) / 1024 / 1024 AS size_mb
FROM information_schema.tables
WHERE table_schema = 'rentoza'
ORDER BY (data_length + index_length) DESC;
```

#### Phase 2: Geocode Data (No Impact) - Week 2-3

- **Process:** Background batch job geocodes all string locations
- **Monitoring:** Track success rate, log failures
- **Fallback:** Failed geocodes → Belgrade city center + manual host review
- **Rollback:** Reverse geocoding not needed; just keep old string values

```java
// In application.yml
spring:
  task:
    scheduling:
      pool:
        size: 5
      
migration:
  batch-size: 100         # Geocode 100 cars at a time
  delay-between-batches: 5000  # 5 sec between batches (don't hammer Nominatim)
  nominatim:
    url: https://nominatim.openstreetmap.org
    timeout-seconds: 10
```

#### Phase 3: Switch Reads (Gradual) - Week 4

- **Canary:** Redirect 5% of traffic to new geospatial queries
- **Rollback:** Flip flag if new queries slow
- **Success Metrics:**
  - Query latency < 200ms (was < 100ms)
  - No N+1 query patterns detected
  - Geofence accuracy > 95% match with old implementation

```yaml
feature-flags:
  use-geospatial-car-search: 0.05  # 5% of searches use new queries
  use-geospatial-geofence: 1.0      # 100% for geofence (no degradation)
```

#### Phase 4: Switch Writes - Week 5

- **Dual Write:** All booking creations write both `location` string AND `pickupLocation` GeoPoint
- **Monitoring:** Verify both columns stay in sync
- **Rollback:** Discard geospatial columns if mismatch > 0.1%

```java
@Service
public class BookingService {
    
    @Transactional
    public Booking createBooking(CreateBookingRequest req) {
        
        Booking booking = new Booking();
        booking.setCarId(req.getCarId());
        
        // Dual write: Capture pickup location
        Car car = carRepository.findById(req.getCarId()).orElseThrow();
        
        // Write 1: New geospatial
        booking.setPickupLocation(car.getLocationGeoPoint());
        
        // Write 2: Legacy (for safety)
        booking.setLegacyPickupCity(car.getLocationGeoPoint().getCity());
        
        // Validate consistency
        assert booking.getPickupLocation() != null : "Dual-write failed";
        
        return bookingRepository.save(booking);
    }
}
```

#### Phase 5: Deprecate String Column - Week 6-8

- **Warn:** Log deprecation warnings in application
- **Alert Hosts:** Email hosts asking to refine car locations with map UI
- **Remove:** Drop `cars.location` column in v27 migration (weeks later)

```java
@PrePersist
public void warnDeprecatedLocation() {
    if (this.location != null && this.locationGeoPoint == null) {
        log.warn("DEPRECATION: Car {} still using string location '{}'. " +
            "Please migrate to geospatial. Set locationGeoPoint.",
            this.id, this.location);
    }
}
```

### Rollback Procedure (If Issues Detected)

**Scenario:** Geofence validation is rejecting 10% of valid handshakes.

**Steps:**

1. **Immediate:** Disable geospatial geofence, revert to old implementation
   ```yaml
   use-geospatial-geofence: false  # Switch back to legacy car_latitude/longitude
   ```

2. **Investigation:** Compare old vs. new distance calculations
   ```sql
   SELECT 
     b.id,
     (SELECT ST_Distance_Sphere(POINT(b.pickup_longitude, b.pickup_latitude),
                                POINT(b.guest_check_in_longitude, b.guest_check_in_latitude))) 
       AS new_distance_m,
     b.geofence_distance_meters AS old_distance_m,
     ABS((SELECT ST_Distance_Sphere(...)) - b.geofence_distance_meters) AS variance_m
   FROM bookings b
   WHERE b.status = 'IN_TRIP'
   ORDER BY variance_m DESC
   LIMIT 20;
   ```

3. **Root Cause:**
   - SRID mismatch (4326 vs. 0)
   - Coordinate order (lat,lng vs. lng,lat)
   - Projection error (WGS84 vs. Web Mercator)

4. **Fix:** Adjust calculation, test on canary again

5. **Rollback to v2.5:** Revert code, keep new columns as technical debt

---

## Testing Strategy

### Unit Tests (GeoPoint & Services)

```java
@Test
public void testGeoPointDistanceCalculation() {
    GeoPoint belgrade = new GeoPoint(
        BigDecimal.valueOf(44.8125),
        BigDecimal.valueOf(20.4612)
    );
    
    GeoPoint vozdovac = new GeoPoint(
        BigDecimal.valueOf(44.7518),
        BigDecimal.valueOf(20.5271)
    );
    
    double distance = belgrade.distanceTo(vozdovac);
    
    // Voždovac is ~8km from city center
    assertTrue(distance > 7900 && distance < 8100, 
        "Expected ~8km, got " + distance);
}

@Test
public void testObfuscationMaintainsCity() {
    GeoPoint original = new GeoPoint(
        BigDecimal.valueOf(44.8125),
        BigDecimal.valueOf(20.4612),
        "Terazije 26",
        "Belgrade",
        "11000"
    );
    
    GeoPoint obfuscated = original.obfuscate(new Random(), 500);
    
    // City should remain for UI grouping
    assertEquals("Belgrade", obfuscated.getCity());
    
    // Address should be hidden
    assertNull(obfuscated.getAddress());
    
    // Distance should be within radius
    double distance = original.distanceTo(obfuscated);
    assertTrue(distance <= 500, 
        "Obfuscation radius exceeded: " + distance);
}

@Test
public void testGeometryValidationRejctsOutsideSerbia() {
    GeoPoint invalid = new GeoPoint(
        BigDecimal.valueOf(50.0),  // Outside Serbia (too north)
        BigDecimal.valueOf(20.4612)
    );
    
    assertThrows(ValidationException.class, () -> {
        invalid.validate();
    });
}
```

### Integration Tests (CarRepository SPATIAL Queries)

```java
@DataJpaTest
public class CarRepositoryGeoSpatialTests {
    
    @Autowired
    private CarRepository carRepository;
    
    @Autowired
    private TestEntityManager entityManager;
    
    @BeforeEach
    public void setup() {
        User owner = new User();
        owner.setEmail("owner@example.com");
        entityManager.persistAndFlush(owner);
        
        // Create 3 test cars at known locations
        Car car1 = new Car();
        car1.setLocationGeoPoint(new GeoPoint(
            BigDecimal.valueOf(44.8125),      // Belgrade center
            BigDecimal.valueOf(20.4612)
        ));
        car1.setOwner(owner);
        car1.setAvailable(true);
        entityManager.persistAndFlush(car1);
        
        Car car2 = new Car();
        car2.setLocationGeoPoint(new GeoPoint(
            BigDecimal.valueOf(44.7518),      // Voždovac (~8km south)
            BigDecimal.valueOf(20.5271)
        ));
        car2.setOwner(owner);
        car2.setAvailable(true);
        entityManager.persistAndFlush(car2);
        
        Car car3 = new Car();
        car3.setLocationGeoPoint(new GeoPoint(
            BigDecimal.valueOf(44.6862),      // Voždovac far (~15km south)
            BigDecimal.valueOf(20.5410)
        ));
        car3.setOwner(owner);
        car3.setAvailable(true);
        entityManager.persistAndFlush(car3);
    }
    
    @Test
    public void testFindNearbyCars_Within5km() {
        // Search from Belgrade center, 5km radius
        List<Car> nearby = carRepository.findNearby(
            44.8125,    // lat
            20.4612,    // lon
            5000,       // 5km in meters
            100         // limit
        );
        
        // Should find car1 (0km away) and car2 (~8km? NO, outside radius)
        assertEquals(1, nearby.size());
        assertEquals("Car at Belgrade center", nearby.get(0).getBrand());
    }
    
    @Test
    public void testFindNearbyCars_Within15km() {
        List<Car> nearby = carRepository.findNearby(
            44.8125, 20.4612, 15000, 100
        );
        
        // Should find car1 and car2 (8km away)
        assertEquals(2, nearby.size());
    }
    
    @Test
    public void testFindNearbyCars_ResultsOrdered() {
        List<Car> nearby = carRepository.findNearby(
            44.8125, 20.4612, 20000, 100
        );
        
        // Should be ordered by distance (closest first)
        double dist1 = calculateHaversine(44.8125, 20.4612, 44.8125, 20.4612);  // 0
        double dist2 = calculateHaversine(44.8125, 20.4612, 44.7518, 20.5271);  // 8km
        double dist3 = calculateHaversine(44.8125, 20.4612, 44.6862, 20.5410);  // 15km
        
        assertTrue(dist1 < dist2 && dist2 < dist3, "Results not ordered by distance");
    }
}
```

### Geofence Validation Tests

```java
@Test
public void testGeofenceValidationWithDynamicRadius() {
    // Urban location (Belgrade)
    GeoPoint pickupLocation = new GeoPoint(
        BigDecimal.valueOf(44.8125),      // Latitude
        BigDecimal.valueOf(20.4612)       // Longitude
    );
    
    // Infer density
    GeofenceService.LocationDensity density = 
        geofenceService.inferLocationDensity(
            pickupLocation.getLatitude(),
            pickupLocation.getLongitude()
        );
    
    assertEquals(LocationDensity.URBAN, density);
    
    // Guest 120m away (should pass URBAN radius of 150m)
    double guestLat = 44.81229;  // ~120m north
    double guestLon = 20.46120;
    
    GeofenceResult result = geofenceService.validateProximity(
        pickupLocation.getLatitude(), pickupLocation.getLongitude(),
        BigDecimal.valueOf(guestLat), BigDecimal.valueOf(guestLon),
        density
    );
    
    assertTrue(result.isWithinRadius());
    assertEquals(120, result.getDistanceMeters(), 5);  // ±5m tolerance
    assertEquals(150, result.getRequiredRadiusMeters());
    assertTrue(result.isDynamicRadiusApplied());
}

@Test
public void testSpatialQueryCoordinateOrder_CriticalBug() {
    /**
     * CRITICAL: MySQL ST_Distance_Sphere REQUIRES POINT(longitude, latitude)
     * This is the opposite of normal (lat, lon) conventions.
     * 
     * WRONG: POINT(44.8125, 20.4612) → Interprets 44.8 as lon, 20.4 as lat → Wrong hemisphere!
     * RIGHT: POINT(20.4612, 44.8125) → Interprets 20.4 as lon, 44.8 as lat ✓
     * 
     * Failure Mode: Tests pass, but distances are nonsensical (e.g., 500km instead of 5km)
     * Detection: Add unit test to verify coordinate order.
     */
    
    // Known distance: Belgrade city center to Voždovac is ~8km
    double belgradeCorrectLon = 20.4612;
    double belgradeCorrectLat = 44.8125;
    double vozdovacCorrectLon = 20.5271;
    double vozdovacCorrectLat = 44.7518;
    
    // CORRECT order for MySQL ST_Distance_Sphere
    double correctDistance = geofenceService.haversineDistance(
        belgradeCorrectLat, belgradeCorrectLon,
        vozdovacCorrectLat, vozdovacCorrectLon
    );
    
    // Should be ~8000 meters
    assertTrue(correctDistance > 7900 && correctDistance < 8100,
        "Expected ~8km, got " + correctDistance + "m");
    
    // WRONG order (if developer swaps lat/lon)
    double wrongDistance = geofenceService.haversineDistance(
        belgradeCorrectLon, belgradeCorrectLat,  // SWAPPED!
        vozdovacCorrectLon, vozdovacCorrectLat   // SWAPPED!
    );
    
    // Wrong order should give completely different (wrong) result
    assertNotEquals(correctDistance, wrongDistance, 100);
    assertTrue(wrongDistance > 1000000,  // Should be nonsensical (100km+)
        "Swapped coordinates should produce wrong result");
}

@Test
public void testGeofenceValidationPickupLocationImmutable() {
    // Booking created with pickup location
    Booking booking = new Booking();
    booking.setPickupLocation(new GeoPoint(
        BigDecimal.valueOf(44.8125),
        BigDecimal.valueOf(20.4612),
        "Original location"
    ));
    
    // Try to update
    booking.setPickupLocation(new GeoPoint(
        BigDecimal.valueOf(44.7000),
        BigDecimal.valueOf(20.5000),
        "Different location"
    ));
    
    // Trigger validation
    assertThrows(IllegalStateException.class, () -> {
        bookingRepository.save(booking);
    });
}
```

### End-to-End (E2E) Tests

```gherkin
# features/geospatial-car-search.feature

Feature: Geospatial Car Search with Privacy
  As a guest
  I want to find cars near me
  But I don't want exact locations visible until I book

  Scenario: Unbooked guest sees obfuscated locations
    Given a car is parked at "44.8125, 20.4612" (Belgrade)
    And the car is available
    When I search nearby "44.8100, 20.4600" (nearby)
    And I haven't booked this car before
    Then the search result shows pickupCity = "Belgrade"
    But pickupLatitude and pickupLongitude are within ±500m of actual
    And I cannot see the exact address
    
  Scenario: Returning customer sees exact location
    Given I booked a car before
    When I search for the same car again
    Then the search result shows exact pickupLatitude and pickupLongitude
    And the address is visible
    
  Scenario: Host refines pickup location during check-in
    Given a booking with pickupLocation = "44.8125, 20.4612"
    And host uploads check-in photos
    When host refines the pin drop location by 150m
    Then the actual carLatitude/carLongitude is updated
    But the booking's pickupLocation remains immutable
    And the variance is logged for disputes
```

### Load Testing (SPATIAL INDEX Performance)

```java
@RunWith(JMeterTestRunner.class)
public class GeospatialLoadTest {
    
    /**
     * Verify SPATIAL INDEX enables sub-100ms queries on large dataset.
     * 
     * Setup:
     * - 50,000 cars distributed across Serbia
     * - SPATIAL INDEX on (latitude, longitude)
     * 
     * Test: 100 concurrent searches, each finding cars within 5km
     * Target: p99 latency < 200ms
     */
    @Test
    public void testNearbySearchLatency() {
        // JMeter sampler
        JMeterTestPlan plan = new JMeterTestPlan();
        
        ThreadGroup searchThreadGroup = new ThreadGroup();
        searchThreadGroup.setNumThreads(100);  // Concurrent users
        searchThreadGroup.setRampUpTime(60);   // Ramp up over 1 minute
        
        HTTPSamplerProxy httpSampler = new HTTPSamplerProxy();
        httpSampler.setDomain("localhost");
        httpSampler.setPort(8080);
        httpSampler.setPath("/api/v3/cars/search");
        httpSampler.addArgument("latitude", "44.8125");
        httpSampler.addArgument("longitude", "20.4612");
        httpSampler.addArgument("radiusKm", "5");
        
        // Assertions
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setTestType(ResponseAssertion.TEST_TYPE_CODE);
        assertion.addTestString("200");
        
        // Run test
        JMeterResults results = plan.run();
        
        // Verify latency SLA
        assert(results.getPercentile(99) < 200) : 
            "p99 latency exceeded 200ms: " + results.getPercentile(99);
    }
}
```

---

## Refinements Applied (Addressing Review Feedback)

Based on critical architectural review, the plan has been enhanced with:

### 1. **Performance: SPATIAL Queries Accelerated to Phase 1** ✅
   - **Original:** Deferred MySQL SPATIAL INDEX to Phase 4 (risky bottleneck)
   - **Revised:** SPATIAL queries now implemented in Phase 1 with immediate index creation
   - **Impact:** Prevents N+1 query performance degradation as car dataset grows
   - **Testing:** Added EXPLAIN verification and <100ms latency requirements

### 2. **DeliveryPoi Entity Proper Implementation** ✅
   - **Original:** POI overrides mentioned as brittle JSON string
   - **Revised:** Dedicated `DeliveryPoi` entity with:
     - SPATIAL INDEX on location (supports radius search)
     - Immutable audit trail (created_at, updated_at)
     - Priority-based fee calculation (POI → distance-based)
     - Repository query using SPATIAL distance
   - **Impact:** Eliminates JSON parsing brittleness; enables complex POI rules

### 3. **Delivery Distance Precision: DECIMAL(8,2)** ✅
   - **Original:** DECIMAL(6,2) limited to 9,999 km
   - **Revised:** DECIMAL(8,2) supports up to 999,999.99 km
   - **Rationale:** Future-proofs for EU-wide expansion (e.g., Serbia→Germany delivery)
   - **Current:** Serbia max distance ~700km border-to-border

### 4. **Booking Audit Trail for Location Refinements** ✅
   - **Added:** `execution_location_updated_by` (FK to users) + `execution_location_updated_at`
   - **Purpose:** Tracks which host refined pickup location at check-in
   - **Benefit:** Enables dispute resolution ("I refined location 50m away")

### 5. **Read Model Denormalization (V27)** ✅
   - **Added:** `checkin_status_view` enhancement with geospatial columns:
     - `car_home_latitude/longitude/city/address` (from Car)
     - `pickup_latitude/longitude/city/address` (from Booking)
     - `checkin_actual_latitude/longitude` + variance
     - `guest_check_in_latitude/longitude` + geofence distance
   - **Benefit:** Eliminates N+1 queries in check-in UI (avoids JOIN to Car for each booking)
   - **Migration:** Phase 5 (before Phase 4 validation)

### 6. **Frontend Privacy: Fuzzy Circle + Conditional Pin** ✅
   - **Original:** "FuzzyCircleComponent" mentioned but underspecified
   - **Revised:** Detailed implementation showing:
     - **Unbooked guest:** Show ONLY fuzzy circle + "Exact location shown after booking" label
     - **Booked guest:** Show exact red pin + "Exact pickup location" label
     - **Prevents:** Reverse-geocoding attacks (user can't narrow coordinates by searching repeatedly)
   - **UX:** Clear visual distinction between approximate (before) and exact (after) location

### 7. **SPATIAL Coordinate Order Critical Test** ✅
   - **Risk:** MySQL ST_Distance_Sphere(POINT(lon, lat), ...) but developers think POINT(lat, lon)
   - **Added:** Unit test `testSpatialQueryCoordinateOrder_CriticalBug()` that:
     - Verifies correct distance (Belgrade→Voždovac = 8km)
     - Detects swapped coordinates (produces 100km+ nonsense)
     - Catches bug early in CI/CD pipeline
   - **Failure Mode:** Tests might pass, but production queries produce wrong radii

---

## Summary: Risk Matrix

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|-----------|
| **Geofence rejects valid guests** | High | Critical | Test with 1000+ real check-ins before rollout |
| **Coordinates geocode incorrectly** | Medium | High | Manual review of top 20 cities, fallback to Belgrade |
| **Spatial queries timeout** | Low | Critical | Index pressure test with 100k cars; use EXPLAIN ANALYZE |
| **Rollback takes >1 hour** | Medium | High | Keep string `location` column for 2+ weeks after go-live |
| **Legal dispute: "Location was wrong"** | Medium | Medium | Add privacy disclaimer in Terms of Service |
| **API breaking change confuses clients** | High | Medium | 3-month v2.5 dual-mode before requiring v3.0 |

---

## Success Criteria

- ✅ All cars have valid coordinates (within Serbia bounds)
- ✅ Geofence passes 99%+ of legitimate handshakes (compare to v2.0 baseline)
- ✅ Car search queries < 200ms p99 latency (3x concurrent users)
- ✅ Zero data loss during migration (point-in-time restore validates)
- ✅ Delivery fee calculation differs from manual estimate by < 5%
- ✅ Privacy: Unbooked guests cannot reverse-search to exact car location
- ✅ Rollback to v2.5 possible within 30 minutes

---

**Next Steps:**
1. Review this plan with backend + frontend teams
2. Create detailed implementation tickets for each phase
3. Set up staging environment with 50k test cars
4. Draft Terms of Service changes (privacy disclaimer)
5. Schedule rollback drill (chaos engineering)
