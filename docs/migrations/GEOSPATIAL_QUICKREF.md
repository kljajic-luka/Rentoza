# Geospatial Migration - Quick Reference

**Document:** `GEOSPATIAL_LOCATION_MIGRATION_PLAN.md` (2,391 lines)  
**Status:** Phase 2.4 Planning  
**Timeline:** 6-8 weeks including testing

## Core Problem

- ❌ Cars stored as string locations (`location = "Belgrade"`)
- ❌ Cannot calculate delivery fees (no distance data, no routing)
- ❌ Cannot validate guest proximity (geofence only partial)
- ❌ Cannot prevent asset stalking (no privacy controls)

## Solution Overview

✅ **GeoPoint** embeddable entity (immutable latitude/longitude with validation)  
✅ **Pickup Location Snapshot** frozen at booking time (prevents host moving car)  
✅ **Fuzzy Obfuscation** (±500m for non-bookers, prevents reverse-geocoding)  
✅ **DeliveryPoi** entity (not JSON) for airport/hotel fee overrides  
✅ **SPATIAL INDEX** (Phase 1, not Phase 4) for sub-100ms radius queries  
✅ **OSRM Routing** for accurate driving distance (not air distance Haversine)  
✅ **Mapbox GL JS** for frontend maps (faster, cheaper, offline support)  

## Database Changes (Phases)

| Phase | Week | Action | Risk |
|-------|------|--------|------|
| **1** | 1 | Add geospatial columns + SPATIAL INDEX | Low (backwards compatible) |
| **2** | 2-3 | Geocode legacy string locations (Nominatim API) | Medium (fallback to Belgrade) |
| **3** | 3 | Create `delivery_pois` table, add booking audit fields | Low (new tables) |
| **4** | 4 | Validation triggers, NOT NULL constraints | High (schema lock) |
| **5** | 4-5 | Denormalize geospatial into `checkin_status_view` | Low (read model only) |
| **6** | 6+ | Drop legacy `location` string column | Low (after 2-week monitoring) |

## Entity Changes

### Car Entity

```java
@Embedded GeoPoint locationGeoPoint;        // Replaces location string
Double deliveryRadiusKm;                     // Free delivery radius
BigDecimal deliveryFeePerKm;                // Fee beyond radius
```

### Booking Entity

```java
@Embedded GeoPoint pickupLocation;          // IMMUTABLE snapshot at booking time
Integer pickupLocationVarianceMeters;       // How far car moved
User executionLocationUpdatedBy;            // Who refined location at check-in
```

### New Entity

```java
// DeliveryPoi.java
@Entity
public class DeliveryPoi {
    String poiName;                         // "Belgrade Airport"
    @Embedded GeoPoint location;            // With SPATIAL INDEX
    Integer radiusMeters;                   // Detection radius
    BigDecimal flatFee;                     // Override per-km pricing
    Boolean feeWaived;                      // Free delivery?
}
```

## External Service Integrations

### OSRM (Open Street Map Routing Machine)

**What:** Free, open-source routing engine for accurate driving distances  
**API:** https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}  
**Rate Limit:** 20 req/min per IP (sufficient for Rentoza)  
**Timeout:** 5 seconds (Serbia routes typically <1 sec)  
**Fallback:** Haversine air distance with +20% detour estimate if OSRM unavailable  
**Caching:** 24-hour TTL (routes don't change daily)  

**Usage:** Delivery fee calculation = (driving_distance - free_radius) * per_km_rate

### Mapbox GL JS v2.15+

**What:** Frontend map library with vector tiles, offline support  
**Token:** `<MAPBOX_PUBLIC_TOKEN>`  
**Cost:** $0.50 per 1,000 map sessions (vs $7 for Google Maps)  
**Offline:** Pre-download tiles for rural Serbia areas  
**Security:** Store token in .env.local, never in git  

**Usage:** LocationPicker, FuzzyCircle, PinDrop components + read model visualization

---

## Critical Architecture Decisions

### 1. SPATIAL Queries in Phase 1 (NOT Phase 4)

**Decision:** Implement `CarRepository.findNearby()` with native SQL SPATIAL queries immediately.

```sql
-- CORRECT: POINT(longitude, latitude) required!
SELECT * FROM cars 
WHERE ST_Distance_Sphere(
    POINT(location_longitude, location_latitude),
    POINT(:userLongitude, :userLatitude)
) <= :radiusMeters;
```

**Why:** Prevents N+1 performance bottleneck as car dataset grows beyond 1,000 cars.

### 2. Pickup Location is Immutable

**Decision:** Once booking is created, `Booking.pickupLocation` cannot be changed.

```sql
CREATE TRIGGER trg_booking_pickup_location_immutable
BEFORE UPDATE ON bookings
FOR EACH ROW
BEGIN
    IF OLD.pickup_latitude IS NOT NULL AND 
       (NEW.pickup_latitude != OLD.pickup_latitude OR NEW.pickup_longitude != OLD.pickup_longitude)
    THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'IMMUTABLE: Pickup location cannot be changed after booking';
    END IF;
END;
```

**Why:** Ensures guest can't be surprised by moved pickup location after booking.

### 3. Fuzzy Obfuscation for Privacy

**Decision:** Unbooked guests see ±500m randomized coordinates + fuzzy circle on map.

```typescript
// Unbooked guest: Only fuzzy circle + label
if (!hasBooked) {
    // Show circle, hide exact pin
    showApproximateAreaLabel();
} else {
    // Show exact red pin + label
    showExactLocationLabel();
}
```

**Why:** Prevents luxury car stalking (user can't reverse-geocode by querying repeatedly).

### 4. Coordinate Order: POINT(lon, lat) NOT (lat, lon)

**Critical Bug Risk:** MySQL ST_Distance_Sphere requires POINT(longitude, latitude), opposite of convention.

```java
// ✅ CORRECT
haversineDistance(lat1, lon1, lat2, lon2)  // lat, lon order

// ❌ WRONG in SQL
POINT(44.8125, 20.4612)  // Wrong: interprets as POINT(lon, lat)

// ✅ CORRECT in SQL
POINT(20.4612, 44.8125)  // Correct: POINT(lon, lat)
```

**Test:** `testSpatialQueryCoordinateOrder_CriticalBug()` verifies this.

## Migration Data Flow

### Current Car Search (v2.0)
```
User clicks "Find cars"
  → Query: SELECT * FROM cars WHERE LOWER(location) = 'belgrade'
  → Loads ALL matching cars into memory
  → Stream.filter(car -> distance(...) <= 5000)
  ❌ N+1 problem: Haversine math on every car
```

### New Car Search (v3.0)
```
User clicks "Find cars" → Sends lat/lon + radius
  → Query: SELECT * FROM cars 
           WHERE ST_Distance_Sphere(POINT(lon, lat), ...) <= 5000
  ✅ Database optimization: SPATIAL INDEX filters cars
  ✅ Returns only relevant cars (~100ms)
  → Obfuscate coordinates if guest hasn't booked
  → Return: {pickupCity, pickupLatitude (fuzzed), fuzzyRadius}
```

## Rollback Procedures

### Immediate Rollback (If Geofence Rejects 10%+ of Valid Handshakes)

```yaml
# application.yml
use-geospatial-geofence: false  # Revert to old car_latitude/longitude
```

### Investigation
```sql
SELECT b.id, 
  ST_Distance_Sphere(POINT(b.pickup_longitude, b.pickup_latitude),
                     POINT(b.guest_check_in_longitude, b.guest_check_in_latitude)) AS new_dist,
  b.geofence_distance_meters AS old_dist
FROM bookings b
WHERE b.status = 'IN_TRIP'
ORDER BY ABS(new_dist - old_dist) DESC
LIMIT 20;
```

### Root Causes
- SRID mismatch (4326 vs 0)
- Coordinate order swapped (lat, lon vs lon, lat)
- Projection error (WGS84 vs Web Mercator)

## Success Criteria

- ✅ All cars have valid coordinates (within Serbia bounds: 42.2-47.9°N, 18.8-23.0°E)
- ✅ Geofence passes 99%+ of legitimate handshakes (compare to v2.0 baseline)
- ✅ Car search queries < 200ms p99 latency (100 concurrent)
- ✅ Zero data loss during migration (point-in-time restore validates)
- ✅ Delivery fee differs from manual estimate < 5%
- ✅ Unbooked guests cannot reverse-search exact car location
- ✅ Rollback to v2.5 possible within 30 minutes

## Key Files to Review

1. **GeoPoint.java** - Embeddable value object, immutable, validates Serbia bounds
2. **Car.java** - Add `locationGeoPoint`, deprecate `location` string
3. **Booking.java** - Add `pickupLocation` (immutable), `pickupLocationVarianceMeters`, audit fields
4. **DeliveryPoi.java** - New entity for POI overrides (airport, hotel flat fees)
5. **CarRepository.java** - New `findNearby()` method with SPATIAL queries
6. **DeliveryPoiRepository.java** - New `findMatchingPoi()` with SPATIAL distance
7. **CheckInService.java** - Update to validate pickup location variance
8. **DeliveryFeeCalculator.java** - POI check first, then distance-based calculation

## Testing Checklist

- [ ] Unit: GeoPoint distance calculations ±5m accuracy
- [ ] Unit: Coordinate order verification (Belgrade→Voždovac = 8km)
- [ ] Integration: SPATIAL queries < 100ms with 1,000+ cars
- [ ] Integration: CarRepository.findNearby() orders by distance
- [ ] E2E: Unbooked guest sees fuzzy coordinates
- [ ] E2E: Booked guest sees exact coordinates
- [ ] E2E: Geofence rejects guests >100m away (urban), >50m away (rural)
- [ ] Load: 100 concurrent searches, p99 < 200ms
- [ ] Rollback: Feature flag disables geofence in <30 sec

---

**See full plan:** `GEOSPATIAL_LOCATION_MIGRATION_PLAN.md`
