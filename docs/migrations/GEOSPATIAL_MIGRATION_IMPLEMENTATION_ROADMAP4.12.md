# Geospatial Migration Implementation Roadmap

**Document Version**: 1.0  
**Status**: Active Implementation Phase  
**Target Completion**: Phase 4 (Search & Discovery)

---

## Executive Summary

This document provides a detailed implementation plan for bridging the gap between the current booking flow and the target geospatial-enabled architecture. The plan is divided into **4 implementation phases**, each building upon the previous, with a focus on enterprise-grade security, precise location handling, and robust state management.

### Key Principles
- **Location Immutability**: Once a location is agreed upon at booking, it's locked for verification purposes
- **Distance Precision**: Use Haversine distance calculations for all geofence validations
- **Audit Trail**: Every location-related event is recorded with timestamps and actor roles
- **Graceful Degradation**: Fallback to in-person handoff if GPS/geofence checks fail

---

## Phase 1: Booking Creation & Delivery Logic

### Objective
Capture pickup location snapshot at booking time and calculate delivery fees based on distance from car's home location to the agreed pickup point.

### 1.1 BookingRequestDTO Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/dto/BookingRequestDTO.java`

```java
public class BookingRequestDTO {
    // ... existing fields ...
    
    // ========== GEOSPATIAL FIELDS (Phase 2.4) ==========
    
    /**
     * Requested pickup latitude (from guest's address input or map selection).
     * 
     * Precision: 8 decimal places (~1.1 mm accuracy)
     * Null if guest is picking up at car's home location.
     */
    private Double pickupLatitude;
    
    /**
     * Requested pickup longitude.
     */
    private Double pickupLongitude;
    
    /**
     * Human-readable pickup address (for UI display and fallback).
     * 
     * Examples:
     * - "Beograd, Srbija"
     * - "Nikola Tesla Airport Terminal 2, Beograd"
     * - "123 Kralja Milana St, 11000 Beograd"
     */
    private String pickupAddress;
    
    /**
     * City name (for location density inference).
     */
    private String pickupCity;
    
    /**
     * Postal/ZIP code (optional, for logistics).
     */
    private String pickupZipCode;
}
```

**Validation Rules**:
- If `pickupLatitude` and `pickupLongitude` are provided, `pickupAddress` is required
- Coordinates must be within Serbia's bounding box (41.8°N, 19.8°E to 46.2°N, 26.8°E)
- Both pickup coordinates must be supplied together (no nulls)

---

### 1.2 DeliveryFeeCalculator Service

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/DeliveryFeeCalculator.java` (New/Modify)

```java
@Service
@Slf4j
public class DeliveryFeeCalculator {

    private final RoutingService routingService;
    private final GeocodingService geocodingService;
    
    @Value("${app.delivery.base-fee-rsd:500}")
    private BigDecimal baseFeeRsd;
    
    @Value("${app.delivery.per-km-rsd:50}")
    private BigDecimal perKmRsd;
    
    @Value("${app.delivery.free-radius-km:2}")
    private BigDecimal freeRadiusKm;

    /**
     * Calculate delivery distance and fee from car location to requested pickup point.
     * 
     * Algorithm:
     * 1. If pickup == car home location: No delivery, fee = 0
     * 2. Query OSRM for driving distance (respects one-way streets, traffic patterns)
     * 3. Calculate fee: BASE + MAX(0, distance - FREE_RADIUS) * PER_KM
     * 4. Return both distance and fee for booking snapshot
     * 
     * @param car Car entity with home location
     * @param pickupLat Guest's requested pickup latitude
     * @param pickupLon Guest's requested pickup longitude
     * @param pickupAddress Human-readable address for logging
     * @return DeliveryFeeResult with distance and calculated fee
     */
    @Transactional(readOnly = true)
    public DeliveryFeeResult calculateDeliveryFee(
            Car car,
            Double pickupLat,
            Double pickupLon,
            String pickupAddress) {
        
        // Validate inputs
        if (car.getHomeLatitude() == null || car.getHomeLongitude() == null) {
            log.warn("Car {} has no home location, cannot calculate delivery fee", car.getId());
            return DeliveryFeeResult.noDelivery();
        }
        
        if (pickupLat == null || pickupLon == null) {
            log.debug("Booking has no custom pickup location, using car home as pickup");
            return DeliveryFeeResult.noDelivery();
        }
        
        // Check if pickup is essentially the same as car home (within 100m)
        GeoPoint carHome = new GeoPoint(car.getHomeLatitude(), car.getHomeLongitude());
        GeoPoint requestedPickup = new GeoPoint(pickupLat, pickupLon);
        double haversineDistance = carHome.distanceTo(requestedPickup); // meters
        
        if (haversineDistance < 100) {
            log.debug("Pickup location within 100m of car home, no delivery fee");
            return DeliveryFeeResult.noDelivery();
        }
        
        try {
            // Query OSRM for driving distance
            RoutingService.RoutingResult routing = routingService.getDrivingDistance(
                    car.getHomeLatitude(),
                    car.getHomeLongitude(),
                    pickupLat,
                    pickupLon
            );
            
            BigDecimal distanceKm = BigDecimal.valueOf(routing.distanceMeters() / 1000.0)
                    .setScale(2, RoundingMode.HALF_UP);
            
            // Apply fee structure
            BigDecimal feeableDistance = distanceKm.subtract(freeRadiusKm);
            if (feeableDistance.compareTo(BigDecimal.ZERO) <= 0) {
                return new DeliveryFeeResult(distanceKm, BigDecimal.ZERO);
            }
            
            BigDecimal deliveryFee = baseFeeRsd
                    .add(feeableDistance.multiply(perKmRsd))
                    .setScale(2, RoundingMode.HALF_UP);
            
            log.info("Delivery fee calculated for car {}: {} km, {} RSD",
                    car.getId(), distanceKm, deliveryFee);
            
            return new DeliveryFeeResult(distanceKm, deliveryFee);
        } catch (Exception e) {
            log.error("Failed to calculate delivery fee for car {} to {}: {}",
                    car.getId(), pickupAddress, e.getMessage());
            // Fallback: estimate based on Haversine distance
            BigDecimal estimatedKm = BigDecimal.valueOf(haversineDistance / 1000.0);
            BigDecimal estimatedFee = baseFeeRsd.add(
                    estimatedKm.multiply(perKmRsd)
            ).setScale(2, RoundingMode.HALF_UP);
            return new DeliveryFeeResult(estimatedKm, estimatedFee);
        }
    }

    public record DeliveryFeeResult(
            BigDecimal distanceKm,
            BigDecimal feeBsd
    ) {
        public static DeliveryFeeResult noDelivery() {
            return new DeliveryFeeResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        
        public boolean hasDelivery() {
            return distanceKm.compareTo(BigDecimal.ZERO) > 0;
        }
    }
}
```

---

### 1.3 BookingService.createBooking() Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/BookingService.java`

**Modification Point**: Lines 156-261 (After price calculation, before `repo.save()`)

```java
@Transactional
public Booking createBooking(BookingRequestDTO dto, String renterEmail) {
    // ... existing validation and checks ...
    
    // ========================================================================
    // GEOSPATIAL SNAPSHOT (Phase 2.4: Immutable Pickup Location)
    // ========================================================================
    // Lock the agreed pickup location at booking creation time.
    // This location is used for:
    // 1. Delivery fee calculation and display
    // 2. Host location variance verification at check-in
    // 3. Geofence validation reference point at handshake
    // 4. Checkout return location validation
    //
    // CRITICAL: This must happen BEFORE calculating delivery fee,
    // and BEFORE the booking is persisted.
    
    GeoPoint pickupLocation = null;
    BigDecimal deliveryDistanceKm = BigDecimal.ZERO;
    BigDecimal deliveryFeeCalculated = BigDecimal.ZERO;
    
    if (dto.getPickupLatitude() != null && dto.getPickupLongitude() != null) {
        // Guest has specified a custom pickup location
        pickupLocation = new GeoPoint(
                BigDecimal.valueOf(dto.getPickupLatitude()),
                BigDecimal.valueOf(dto.getPickupLongitude()),
                dto.getPickupAddress(),
                dto.getPickupCity(),
                dto.getPickupZipCode()
        );
        
        // Calculate delivery fee using the DeliveryFeeCalculator
        DeliveryFeeCalculator.DeliveryFeeResult deliveryResult = 
                deliveryFeeCalculator.calculateDeliveryFee(
                    car,
                    dto.getPickupLatitude(),
                    dto.getPickupLongitude(),
                    dto.getPickupAddress()
                );
        
        deliveryDistanceKm = deliveryResult.distanceKm();
        deliveryFeeCalculated = deliveryResult.feeBsd();
        
        log.debug("Pickup location snapshot created: {}, delivery: {} km, {} RSD",
                dto.getPickupAddress(), deliveryDistanceKm, deliveryFeeCalculated);
    } else {
        // No custom pickup location specified, use car's home location
        if (car.getHomeLatitude() != null && car.getHomeLongitude() != null) {
            pickupLocation = new GeoPoint(
                    car.getHomeLatitude(),
                    car.getHomeLongitude(),
                    car.getLocation(),
                    null,
                    null
            );
        }
        log.debug("Using car home location as pickup for booking");
    }
    
    // Snapshot the location and delivery fee in the booking
    booking.setPickupLocation(pickupLocation);
    booking.setDeliveryDistanceKm(deliveryDistanceKm);
    booking.setDeliveryFeeCalculated(deliveryFeeCalculated);
    
    // Include delivery fee in total price if applicable
    if (deliveryFeeCalculated.compareTo(BigDecimal.ZERO) > 0) {
        booking.setTotalPrice(
                booking.getTotalPrice().add(deliveryFeeCalculated)
                    .setScale(2, RoundingMode.HALF_UP)
        );
        log.info("Total price updated with delivery fee: {} RSD", booking.getTotalPrice());
    }
    
    // ... rest of the method continues ...
    Booking savedBooking = repo.save(booking);
}
```

---

### 1.4 Database Schema Validation

Verify the Booking entity has the required columns (already defined in `Booking.java` lines 272-348):

- ✅ `pickup_latitude` (DECIMAL 10,8)
- ✅ `pickup_longitude` (DECIMAL 11,8)
- ✅ `pickup_address` (VARCHAR)
- ✅ `pickup_city` (VARCHAR)
- ✅ `pickup_zip_code` (VARCHAR)
- ✅ `delivery_distance_km` (DECIMAL 8,2)
- ✅ `delivery_fee_calculated` (DECIMAL 10,2)
- ✅ `pickup_location_variance_meters` (INT)

---

## Phase 2: Check-in Logic Upgrades

### Objective
Validate that the car is at the agreed pickup location and verify the guest's geofence compliance for remote handoff.

### 2.1 Location Variance Check (Host Submission)

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/checkin/CheckInService.java`

**Modification Point**: After line 162 in `completeHostCheckIn()` method

```java
@Transactional
public CheckInStatusDTO completeHostCheckIn(HostCheckInSubmissionDTO dto, Long userId) {
    // ... existing validation ...
    
    // ========================================================================
    // LOCATION VARIANCE CHECK (Phase 2.5: Host Pickup Verification)
    // ========================================================================
    // Compare actual car coordinates (from EXIF or manual entry)
    // against the agreed pickupLocation snapshot.
    //
    // Purpose:
    // 1. Detect if host moved the car after booking was made
    // 2. Calculate variance for audit trail and dispute resolution
    // 3. Warn if variance > 500m, block if variance > 2km
    //
    // This check happens AFTER the host submits photos with EXIF data,
    // giving the system actual GPS coordinates to verify.
    
    if (booking.hasPickupLocation() && 
        dto.getCarLatitude() != null && dto.getCarLongitude() != null) {
        
        // Store car's actual location at check-in
        booking.setCarLatitude(BigDecimal.valueOf(dto.getCarLatitude()));
        booking.setCarLongitude(BigDecimal.valueOf(dto.getCarLongitude()));
        
        // Calculate variance using Haversine formula
        Integer varianceMeters = booking.calculatePickupLocationVariance();
        booking.setPickupLocationVarianceMeters(varianceMeters);
        
        log.info("Location variance calculated for booking {}: {} meters",
                booking.getId(), varianceMeters);
        
        // Check thresholds
        if (booking.hasBlockingLocationVariance()) {
            // > 2km variance: BLOCK check-in, require manual resolution
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.LOCATION_VARIANCE_BLOCKING,
                userId,
                CheckInActorRole.HOST,
                Map.of(
                    "varianceMeters", varianceMeters,
                    "threshold", 2000,
                    "action", "BLOCKED"
                )
            );
            
            log.warn("Location variance BLOCKING for booking {}: {} meters > 2km",
                    booking.getId(), varianceMeters);
            
            throw new IllegalStateException(
                "Vozilo se nalazi " + (varianceMeters / 1000) + "km od dogovorene lokacije. " +
                "Molimo pomaknite vozilo na dogovoreno mesto ili kontaktirajte podršku."
            );
        }
        
        if (booking.hasSignificantLocationVariance()) {
            // 500m - 2km variance: WARN and record
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.LOCATION_VARIANCE_WARNING,
                userId,
                CheckInActorRole.HOST,
                Map.of(
                    "varianceMeters", varianceMeters,
                    "threshold", 500,
                    "action", "WARNING"
                )
            );
            
            log.warn("Location variance WARNING for booking {}: {} meters", 
                    booking.getId(), varianceMeters);
            
            // Send notification to guest
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.LOCATION_VARIANCE_WARNING)
                    .message(String.format(
                        "Vozilo se nalazi %.1f km od dogovorene lokacije. Razgovarajte sa domaćinom.",
                        varianceMeters / 1000.0))
                    .relatedEntityId("booking-" + booking.getId())
                    .build());
        } else {
            // < 500m: Normal variance, no action
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.LOCATION_VARIANCE_OK,
                userId,
                CheckInActorRole.HOST,
                Map.of("varianceMeters", varianceMeters)
            );
        }
    }
    
    // ... rest of method continues ...
}
```

---

### 2.2 Dynamic Geofence Validation (Guest Handshake)

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/checkin/GeofenceService.java`

**Enhancement**: Implement dynamic radius based on location density

```java
@Service
@Slf4j
public class GeofenceService {
    
    private static final int URBAN_RADIUS_METERS = 150;   // Belgrade, Novi Sad (GPS multipath)
    private static final int SUBURBAN_RADIUS_METERS = 100; // Smaller cities
    private static final int RURAL_RADIUS_METERS = 50;     // Open areas (better GPS)
    
    private final MapService mapService; // Nominatim or similar for location density
    
    /**
     * Infer location density to adjust geofence validation radius.
     * 
     * <p>Urban areas have higher GPS multipath error due to tall buildings,
     * so we use larger radius. Rural areas have better GPS accuracy.
     * 
     * @param latitude Car's latitude
     * @param longitude Car's longitude
     * @return LocationDensity enum (URBAN, SUBURBAN, RURAL)
     */
    public LocationDensity inferLocationDensity(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            return LocationDensity.SUBURBAN; // Default fallback
        }
        
        try {
            // Query map service for area classification
            // Nominatim's "address" field can indicate urban vs rural
            MapService.PlaceInfo placeInfo = mapService.reverseGeocode(
                    latitude.doubleValue(),
                    longitude.doubleValue()
            );
            
            // Heuristic: If "address.city" is present, likely urban/suburban
            // If only "village" is present, likely rural
            String address = placeInfo.address();
            if (address.contains("城市") || address.contains("grad") ||
                address.contains("Belgrade") || address.contains("Novi Sad")) {
                return LocationDensity.URBAN;
            } else if (address.contains("村") || address.contains("selo")) {
                return LocationDensity.RURAL;
            }
            return LocationDensity.SUBURBAN;
        } catch (Exception e) {
            log.warn("Failed to infer location density at ({}, {}): {}",
                    latitude, longitude, e.getMessage());
            return LocationDensity.SUBURBAN;
        }
    }
    
    /**
     * Validate if guest is within required proximity to car (with dynamic radius).
     */
    public GeofenceResult validateProximity(
            BigDecimal carLat,
            BigDecimal carLon,
            BigDecimal guestLat,
            BigDecimal guestLon,
            LocationDensity density) {
        
        if (carLat == null || carLon == null || guestLat == null || guestLon == null) {
            return GeofenceResult.locationUnavailable();
        }
        
        GeoPoint car = new GeoPoint(carLat, carLon);
        GeoPoint guest = new GeoPoint(guestLat, guestLon);
        
        double distanceMeters = car.distanceTo(guest);
        int requiredRadius = getRadiusForDensity(density);
        
        return new GeofenceResult(
                (int) Math.round(distanceMeters),
                requiredRadius,
                distanceMeters <= requiredRadius,
                density,
                true // dynamic radius applied
        );
    }
    
    private int getRadiusForDensity(LocationDensity density) {
        return switch (density) {
            case URBAN -> URBAN_RADIUS_METERS;
            case SUBURBAN -> SUBURBAN_RADIUS_METERS;
            case RURAL -> RURAL_RADIUS_METERS;
        };
    }
    
    public int getDefaultRadiusMeters() {
        return SUBURBAN_RADIUS_METERS;
    }

    public record GeofenceResult(
            int distanceMeters,
            int requiredRadiusMeters,
            boolean valid,
            LocationDensity density,
            boolean isDynamicRadiusApplied
    ) {
        public boolean shouldBlock() {
            return !valid;
        }
        
        public String getReason() {
            return String.format(
                "Gost je %d metara od vozila (dozvoljeno: %d m, zona: %s)",
                distanceMeters,
                requiredRadiusMeters,
                density != null ? density.name() : "UNKNOWN"
            );
        }
        
        public static GeofenceResult locationUnavailable() {
            return new GeofenceResult(0, 100, false, null, false);
        }
    }

    public enum LocationDensity {
        URBAN,
        SUBURBAN,
        RURAL
    }
}
```

**Enhancement to CheckInService.confirmHandshake()**: Already implemented (lines 378-422), validates against `pickupLocation` instead of current car location.

---

### 2.3 HostCheckInSubmissionDTO Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/checkin/dto/HostCheckInSubmissionDTO.java`

Ensure DTO has location fields (usually auto-filled from EXIF):

```java
public class HostCheckInSubmissionDTO {
    // ... existing fields ...
    
    /**
     * Car's GPS latitude (from photo EXIF or manual entry).
     * Required for location variance check.
     */
    private Double carLatitude;
    
    /**
     * Car's GPS longitude.
     */
    private Double carLongitude;
    
    /**
     * Host's location when submitting check-in (for audit trail).
     */
    private Double hostLatitude;
    private Double hostLongitude;
    
    /**
     * Lockbox code for remote handoff (encrypted before storage).
     */
    private String lockboxCode;
}
```

---

## Phase 3: ID Verification

### Objective
Replace the simple checkbox with actual photo upload and liveness verification (mocked for now).

### 3.1 IdVerificationSubmitDTO

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/checkin/verification/dto/IdVerificationSubmitDTO.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdVerificationSubmitDTO {
    
    /**
     * Booking ID for this verification.
     */
    private Long bookingId;
    
    /**
     * Base64-encoded ID front photo or multipart file.
     * Size: < 5MB, format: JPEG/PNG
     */
    private String idFrontPhoto;
    
    /**
     * Base64-encoded ID back photo.
     * Size: < 5MB, format: JPEG/PNG
     */
    private String idBackPhoto;
    
    /**
     * Base64-encoded selfie for liveness verification.
     * Size: < 5MB, format: JPEG/PNG
     * Required: must show face, natural lighting, not cropped
     */
    private String selfiePhoto;
    
    /**
     * Document type (e.g., "PASSPORT", "NATIONAL_ID", "DRIVER_LICENSE").
     */
    private String documentType;
    
    /**
     * Country code where ID was issued (e.g., "RS" for Serbia).
     */
    private String issueCountry;
}
```

---

### 3.2 IdVerificationService Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/checkin/verification/IdVerificationService.java`

Replace the checkbox validation with actual photo processing:

```java
@Service
@Slf4j
@Transactional
public class IdVerificationService {
    
    private final IdVerificationRepository idVerRepository;
    private final CheckInEventService eventService;
    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;
    private final FileStorageService fileStorageService;
    private final IdVerificationProvider idVerificationProvider; // Inject mock provider
    
    /**
     * Submit ID verification photos for guest at check-in.
     * 
     * Process:
     * 1. Validate photo uploads (size, format, quality)
     * 2. Store photos in encrypted S3/cloud storage
     * 3. Call mock verification provider (liveness + ID match)
     * 4. Create audit record with result
     * 5. Notify guest of result
     * 
     * @param dto Submission with photos
     * @param bookingId Booking ID
     * @param guestId Guest user ID
     * @return Verification status
     */
    @Transactional
    public IdVerificationStatusDTO submitIdVerification(
            IdVerificationSubmitDTO dto,
            Long bookingId,
            Long guestId) {
        
        // Fetch booking and validate guest
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        if (!booking.getRenter().getId().equals(guestId)) {
            throw new AccessDeniedException("Guest cannot verify for another booking");
        }
        
        // Validate photo uploads
        validatePhotoUploads(dto);
        
        // Store photos in encrypted storage
        String idFrontUrl = fileStorageService.uploadEncrypted(
                "id-verification/" + bookingId + "/id_front",
                Base64.getDecoder().decode(dto.getIdFrontPhoto())
        );
        
        String idBackUrl = fileStorageService.uploadEncrypted(
                "id-verification/" + bookingId + "/id_back",
                Base64.getDecoder().decode(dto.getIdBackPhoto())
        );
        
        String selfieUrl = fileStorageService.uploadEncrypted(
                "id-verification/" + bookingId + "/selfie",
                Base64.getDecoder().decode(dto.getSelfiePhoto())
        );
        
        // Call mock verification provider
        IdVerificationProvider.VerificationResult verificationResult = 
                idVerificationProvider.verifyIdAndLiveness(
                    idFrontUrl,
                    idBackUrl,
                    selfieUrl,
                    booking.getRenter()
                );
        
        // Create verification record
        CheckInIdVerification verification = new CheckInIdVerification();
        verification.setBooking(booking);
        verification.setGuest(booking.getRenter());
        verification.setStatus(IdVerificationStatus.fromResult(verificationResult));
        verification.setDocumentType(dto.getDocumentType());
        verification.setIssueCountry(dto.getIssueCountry());
        verification.setIdFrontPhotoUrl(idFrontUrl);
        verification.setIdBackPhotoUrl(idBackUrl);
        verification.setSelfiePhotoUrl(selfieUrl);
        verification.setSubmittedAt(LocalDateTime.now());
        
        if (verificationResult.isSuccess()) {
            verification.setVerifiedAt(LocalDateTime.now());
            verification.setVerificationData(
                    Map.of(
                        "livenessScore", verificationResult.livenessScore(),
                        "matchScore", verificationResult.idMatchScore(),
                        "extractedName", verificationResult.extractedName()
                    )
            );
        } else {
            verification.setFailureReason(verificationResult.errorMessage());
        }
        
        idVerRepository.save(verification);
        booking.setIdVerification(verification);
        bookingRepository.save(booking);
        
        // Record event
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.ID_VERIFICATION_SUBMITTED,
            guestId,
            CheckInActorRole.GUEST,
            Map.of(
                "status", verification.getStatus().name(),
                "documentType", dto.getDocumentType(),
                "countryCode", dto.getIssueCountry()
            )
        );
        
        // Notify guest
        String message = verificationResult.isSuccess()
                ? "Vaša identifikacija je potvrđena. Možete nastaviti sa checkin-om."
                : "Verifikacija identifikacije nije uspela. " + verificationResult.errorMessage();
        
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(guestId)
                .type(NotificationType.ID_VERIFICATION_RESULT)
                .message(message)
                .relatedEntityId("booking-" + bookingId)
                .build());
        
        log.info("ID verification submitted for booking {}, status: {}", 
                bookingId, verification.getStatus());
        
        return mapToStatusDTO(verification);
    }
    
    private void validatePhotoUploads(IdVerificationSubmitDTO dto) {
        if (dto.getIdFrontPhoto() == null || dto.getIdFrontPhoto().isBlank()) {
            throw new ValidationException("ID front photo is required");
        }
        if (dto.getIdBackPhoto() == null || dto.getIdBackPhoto().isBlank()) {
            throw new ValidationException("ID back photo is required");
        }
        if (dto.getSelfiePhoto() == null || dto.getSelfiePhoto().isBlank()) {
            throw new ValidationException("Selfie photo is required");
        }
        
        // Validate base64 decoding and approximate size
        byte[] frontPhoto = Base64.getDecoder().decode(dto.getIdFrontPhoto());
        byte[] backPhoto = Base64.getDecoder().decode(dto.getIdBackPhoto());
        byte[] selfiePhoto = Base64.getDecoder().decode(dto.getSelfiePhoto());
        
        if (frontPhoto.length > 5_000_000 || backPhoto.length > 5_000_000 || 
            selfiePhoto.length > 5_000_000) {
            throw new ValidationException("Photos must be smaller than 5MB");
        }
    }
    
    private IdVerificationStatusDTO mapToStatusDTO(CheckInIdVerification verification) {
        return new IdVerificationStatusDTO(
                verification.getId(),
                verification.getStatus(),
                verification.getSubmittedAt(),
                verification.getVerifiedAt(),
                verification.getFailureReason()
        );
    }
}
```

---

### 3.3 MockIdVerificationProvider

**File**: `Rentoza/src/main/java/org/example/rentoza/booking/checkin/verification/MockIdVerificationProvider.java`

```java
@Service
@Slf4j
public class MockIdVerificationProvider implements IdVerificationProvider {
    
    @Value("${app.idver.mock.always-pass:false}")
    private boolean alwaysPass;

    /**
     * Mock verification: Simulates ID verification and liveness check.
     * 
     * In production, this would call a third-party provider like:
     * - iDenfy
     * - Jumio
     * - Onfido
     * - AWS Rekognition Face Match
     */
    @Override
    public VerificationResult verifyIdAndLiveness(
            String idFrontUrl,
            String idBackUrl,
            String selfieUrl,
            User guest) {
        
        // Simulate random pass/fail (weighted toward pass for testing)
        boolean shouldPass = alwaysPass || Math.random() > 0.1;
        
        if (shouldPass) {
            return new VerificationResult(
                    true,
                    0.95, // liveness score (0-1)
                    0.92, // ID match score (0-1)
                    guest.getFirstName() + " " + guest.getLastName(),
                    null // no error
            );
        } else {
            return new VerificationResult(
                    false,
                    0.65,
                    0.45,
                    null,
                    "Liveness check failed: Please provide a clear selfie"
            );
        }
    }

    public record VerificationResult(
            boolean success,
            double livenessScore,
            double idMatchScore,
            String extractedName,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return success;
        }
    }
}
```

---

## Phase 4: Search & Discovery (Geospatial Search)

### Objective
Enable car search by location with fuzzy coordinates for non-booked cars to prevent stalking.

### 4.1 CarRepository Spatial Query Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/car/CarRepository.java`

Add spatial index query method:

```java
@Repository
public interface CarRepository extends JpaRepository<Car, Long> {
    
    /**
     * Find available cars near a location using spatial index.
     * 
     * <p><b>Database Index:</b>
     * CREATE SPATIAL INDEX idx_car_location ON cars(home_location);
     * (Requires MySQL 5.7+ or PostgreSQL with PostGIS)
     * 
     * @param centerLat Search center latitude
     * @param centerLon Search center longitude
     * @param radiusKm Search radius in kilometers
     * @param startTime Booking period start
     * @param endTime Booking period end
     * @return List of available cars within radius
     */
    @Query(value = """
        SELECT DISTINCT c.* FROM cars c
        LEFT JOIN bookings b ON c.id = b.car_id 
            AND b.status IN ('ACTIVE', 'PENDING_APPROVAL')
            AND (
                (b.start_time < :endTime AND b.end_time > :startTime)
            )
        WHERE b.id IS NULL
        AND ST_Distance_Sphere(
            POINT(c.home_longitude, c.home_latitude),
            POINT(:centerLon, :centerLat)
        ) / 1000 <= :radiusKm
        AND c.is_available = true
        ORDER BY ST_Distance_Sphere(
            POINT(c.home_longitude, c.home_latitude),
            POINT(:centerLon, :centerLat)
        )
        """, nativeQuery = true)
    List<Car> findNearby(
            @Param("centerLat") Double centerLat,
            @Param("centerLon") Double centerLon,
            @Param("radiusKm") Double radiusKm,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
```

---

### 4.2 CarService Search Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/car/CarService.java`

```java
@Service
@Slf4j
public class CarService {
    
    private final CarRepository carRepository;
    private final ReviewRepository reviewRepository;
    
    /**
     * Search for cars by location (geospatial).
     * 
     * Security:
     * - For booked cars (participants only): Return exact location
     * - For non-booked cars (search results): Return fuzzy location (±500m radius)
     * 
     * @param searchLat User's search center latitude
     * @param searchLon User's search center longitude
     * @param radiusKm Search radius
     * @param startTime Booking start time
     * @param endTime Booking end time
     * @param currentUserId Current user ID (for access control)
     * @return List of available cars with obfuscated locations
     */
    @Transactional(readOnly = true)
    public List<CarSearchResultDTO> searchNearby(
            Double searchLat,
            Double searchLon,
            Double radiusKm,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long currentUserId) {
        
        // Query spatial index for cars within radius
        List<Car> nearby = carRepository.findNearby(
                searchLat, searchLon, radiusKm, startTime, endTime);
        
        log.debug("Spatial search found {} cars near ({}, {})",
                nearby.size(), searchLat, searchLon);
        
        return nearby.stream()
                .map(car -> buildSearchResult(car, currentUserId))
                .collect(Collectors.toList());
    }
    
    /**
     * Build search result with location obfuscation.
     */
    private CarSearchResultDTO buildSearchResult(Car car, Long currentUserId) {
        boolean isBooked = hasUserBookedThisCar(car.getId(), currentUserId);
        
        Double displayLat = car.getHomeLatitude();
        Double displayLon = car.getHomeLongitude();
        String displayAddress = car.getLocation();
        
        if (!isBooked) {
            // Obfuscate location: add random ±500m offset
            GeoPoint obfuscated = obfuscateCoordinates(
                    car.getHomeLatitude(),
                    car.getHomeLongitude()
            );
            displayLat = obfuscated.latitude().doubleValue();
            displayLon = obfuscated.longitude().doubleValue();
            displayAddress = "~" + car.getLocation(); // "~" prefix indicates fuzzy
            
            log.debug("Location obfuscated for non-booked car {}: {} -> {}",
                    car.getId(), car.getLocation(), displayAddress);
        }
        
        return new CarSearchResultDTO(
                car.getId(),
                car.getBrand(),
                car.getModel(),
                car.getYear(),
                car.getPricePerDay(),
                displayLat,
                displayLon,
                displayAddress,
                car.getImageUrl(),
                isBooked,
                getAverageRating(car.getId())
        );
    }
    
    /**
     * Obfuscate coordinates by adding random offset within ±500m.
     */
    private GeoPoint obfuscateCoordinates(Double lat, Double lon) {
        // Random offset: 300-500 meters in random direction
        double offsetMeters = 300 + (Math.random() * 200);
        double angle = Math.random() * 2 * Math.PI;
        
        // Convert offset to lat/lon delta
        double latDelta = (offsetMeters / 111_000.0) * Math.cos(angle);
        double lonDelta = (offsetMeters / 111_000.0) * Math.sin(angle);
        
        return new GeoPoint(
                BigDecimal.valueOf(lat + latDelta),
                BigDecimal.valueOf(lon + lonDelta)
        );
    }
    
    private boolean hasUserBookedThisCar(Long carId, Long userId) {
        // Check if user has any ACTIVE/PENDING booking for this car
        return carRepository.findById(carId)
                .map(car -> car.getBookings().stream()
                    .anyMatch(b -> b.getRenter().getId().equals(userId) && 
                        (b.getStatus() == BookingStatus.ACTIVE || 
                         b.getStatus() == BookingStatus.PENDING_APPROVAL))
                )
                .orElse(false);
    }
    
    private Double getAverageRating(Long carId) {
        return reviewRepository.findAverageRatingByCarId(carId);
    }
}
```

---

### 4.3 CarResponseDTO Enhancement

**File**: `Rentoza/src/main/java/org/example/rentoza/car/dto/CarResponseDTO.java`

```java
@Data
public class CarSearchResultDTO {
    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private BigDecimal pricePerDay;
    
    // Potentially obfuscated location
    private Double latitude;
    private Double longitude;
    private String location; // May be prefixed with "~" if obfuscated
    
    private String primaryImageUrl;
    private boolean isBooked; // true if current user has active booking
    private Double averageRating;
}
```

---

### 4.4 CarRepository Spatial Index

Ensure database has spatial index:

```sql
-- MySQL
ALTER TABLE cars ADD SPATIAL INDEX idx_car_location (home_location);

-- PostgreSQL (with PostGIS)
CREATE INDEX idx_car_location_gist ON cars USING GIST(home_location);
```

---

## Phase 5: Frontend Implementation (Angular)

### 5.1 Booking Form Location Picker

**File**: `rentoza-frontend/src/app/features/booking/booking-form/booking-form.component.ts`

```typescript
import { GoogleMapsModule } from '@angular/google-maps';
import { FormGroup, FormBuilder, Validators } from '@angular/forms';

@Component({
  selector: 'app-booking-form',
  templateUrl: './booking-form.component.html',
  styleUrls: ['./booking-form.component.css']
})
export class BookingFormComponent implements OnInit {
  
  bookingForm: FormGroup;
  selectedPickupLocation: { lat: number; lng: number; address: string } | null = null;
  mapCenter = { lat: 44.8176, lng: 20.4633 }; // Belgrade center
  
  constructor(
    private formBuilder: FormBuilder,
    private bookingService: BookingService,
    private geocodingService: GeocodingService
  ) {
    this.bookingForm = this.formBuilder.group({
      carId: ['', Validators.required],
      startDate: ['', Validators.required],
      endDate: ['', Validators.required],
      pickupMethod: ['home', Validators.required], // 'home' or 'custom'
      
      // Geospatial fields (Phase 2.4)
      pickupLatitude: [null],
      pickupLongitude: [null],
      pickupAddress: [''],
      pickupCity: [''],
      pickupZipCode: [''],
      
      insuranceType: ['BASIC'],
      prepaidRefuel: [false]
    });
  }

  onMapClick(event: google.maps.MapMouseEvent): void {
    if (!event.latLng) return;
    
    const lat = event.latLng.lat();
    const lng = event.latLng.lng();
    
    this.selectedPickupLocation = { lat, lng, address: '' };
    
    // Reverse geocode to get address
    this.geocodingService.reverseGeocode(lat, lng).subscribe(
      (address: string) => {
        if (this.selectedPickupLocation) {
          this.selectedPickupLocation.address = address;
        }
        
        // Update form
        this.bookingForm.patchValue({
          pickupMethod: 'custom',
          pickupLatitude: lat,
          pickupLongitude: lng,
          pickupAddress: address
        });
      }
    );
  }

  submitBooking(): void {
    if (this.bookingForm.invalid) {
      return;
    }
    
    const dto: BookingRequestDTO = {
      carId: this.bookingForm.value.carId,
      startTime: this.bookingForm.value.startDate,
      endTime: this.bookingForm.value.endDate,
      insuranceType: this.bookingForm.value.insuranceType,
      prepaidRefuel: this.bookingForm.value.prepaidRefuel,
      
      // Geospatial fields
      pickupLatitude: this.bookingForm.value.pickupLatitude,
      pickupLongitude: this.bookingForm.value.pickupLongitude,
      pickupAddress: this.bookingForm.value.pickupAddress,
      pickupCity: this.bookingForm.value.pickupCity,
      pickupZipCode: this.bookingForm.value.pickupZipCode
    };
    
    this.bookingService.createBooking(dto).subscribe(
      (booking) => {
        console.log('Booking created:', booking);
        // Navigate to booking confirmation
      },
      (error) => {
        console.error('Booking creation failed:', error);
      }
    );
  }
}
```

---

### 5.2 Check-in Guest ID Verification

**File**: `rentoza-frontend/src/app/features/bookings/check-in/check-in-guest.component.ts`

```typescript
@Component({
  selector: 'app-check-in-guest',
  templateUrl: './check-in-guest.component.html',
  styleUrls: ['./check-in-guest.component.css']
})
export class CheckInGuestComponent implements OnInit {
  
  idVerificationForm: FormGroup;
  idFrontPreview: string | null = null;
  idBackPreview: string | null = null;
  selfiePreview: string | null = null;
  
  conditionForm: FormGroup;
  
  constructor(
    private fb: FormBuilder,
    private checkInService: CheckInService,
    private notificationService: NotificationService
  ) {
    this.idVerificationForm = this.fb.group({
      documentType: ['NATIONAL_ID', Validators.required],
      issueCountry: ['RS', Validators.required],
      idFrontPhoto: [null, Validators.required],
      idBackPhoto: [null, Validators.required],
      selfiePhoto: [null, Validators.required]
    });
    
    this.conditionForm = this.fb.group({
      conditionAccepted: [false, Validators.requiredTrue],
      conditionComment: ['']
    });
  }

  onIdFrontSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.previewAndEncode(file, 'idFront');
    }
  }

  onIdBackSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.previewAndEncode(file, 'idBack');
    }
  }

  onSelfieSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.previewAndEncode(file, 'selfie');
    }
  }

  private previewAndEncode(file: File, fieldName: string): void {
    const reader = new FileReader();
    reader.onload = (e: ProgressEvent<FileReader>) => {
      const base64 = e.target?.result as string;
      
      // Store preview
      if (fieldName === 'idFront') {
        this.idFrontPreview = base64;
        this.idVerificationForm.patchValue({ idFrontPhoto: base64 });
      } else if (fieldName === 'idBack') {
        this.idBackPreview = base64;
        this.idVerificationForm.patchValue({ idBackPhoto: base64 });
      } else if (fieldName === 'selfie') {
        this.selfiePreview = base64;
        this.idVerificationForm.patchValue({ selfiePhoto: base64 });
      }
    };
    reader.readAsDataURL(file);
  }

  submitIdVerification(bookingId: number): void {
    if (this.idVerificationForm.invalid) {
      this.notificationService.error('Molimo popunite sve polje za verifikaciju');
      return;
    }
    
    const dto: IdVerificationSubmitDTO = {
      bookingId,
      documentType: this.idVerificationForm.value.documentType,
      issueCountry: this.idVerificationForm.value.issueCountry,
      idFrontPhoto: this.idVerificationForm.value.idFrontPhoto,
      idBackPhoto: this.idVerificationForm.value.idBackPhoto,
      selfiePhoto: this.idVerificationForm.value.selfiePhoto
    };
    
    this.checkInService.submitIdVerification(dto).subscribe(
      (result) => {
        if (result.status === 'VERIFIED') {
          this.notificationService.success('Identifikacija potvrđena!');
          // Enable condition acknowledgment step
        } else {
          this.notificationService.error('Verifikacija nije uspela: ' + result.failureReason);
        }
      }
    );
  }

  acknowledgeCondition(bookingId: number): void {
    if (this.conditionForm.invalid) {
      return;
    }
    
    const dto: GuestConditionAcknowledgmentDTO = {
      bookingId,
      conditionAccepted: true,
      conditionComment: this.conditionForm.value.conditionComment,
      guestLatitude: navigator.geolocation ? getCurrentLat() : null,
      guestLongitude: navigator.geolocation ? getCurrentLon() : null,
      hotspots: []
    };
    
    this.checkInService.acknowledgeCondition(dto).subscribe(
      (result) => {
        this.notificationService.success('Stanje vozila potvrđeno!');
      }
    );
  }
}
```

---

### 5.3 Car Search with Location

**File**: `rentoza-frontend/src/app/features/home/pages/home/home.component.ts`

```typescript
@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css']
})
export class HomeComponent implements OnInit {
  
  searchForm: FormGroup;
  searchResults: CarSearchResultDTO[] = [];
  mapCenter = { lat: 44.8176, lng: 20.4633 };
  searchLocation: { lat: number; lng: number } | null = null;
  
  constructor(
    private fb: FormBuilder,
    private carService: CarService,
    private geolocationService: GeolocationService
  ) {
    this.searchForm = this.fb.group({
      pickupLatitude: [null, Validators.required],
      pickupLongitude: [null, Validators.required],
      startDate: [null, Validators.required],
      endDate: [null, Validators.required],
      radiusKm: [10, Validators.required]
    });
  }

  searchCars(): void {
    if (this.searchForm.invalid) return;
    
    const { pickupLatitude, pickupLongitude, startDate, endDate, radiusKm } = 
            this.searchForm.value;
    
    this.carService.searchNearby(
        pickupLatitude,
        pickupLongitude,
        radiusKm,
        startDate,
        endDate
    ).subscribe(
      (cars: CarSearchResultDTO[]) => {
        this.searchResults = cars;
        this.searchLocation = { lat: pickupLatitude, lng: pickupLongitude };
      }
    );
  }

  onMapClick(event: google.maps.MapMouseEvent): void {
    if (!event.latLng) return;
    
    this.searchForm.patchValue({
      pickupLatitude: event.latLng.lat(),
      pickupLongitude: event.latLng.lng()
    });
  }

  useMyLocation(): void {
    this.geolocationService.getCurrentLocation().subscribe(
      (coords: GeolocationCoordinates) => {
        this.searchForm.patchValue({
          pickupLatitude: coords.latitude,
          pickupLongitude: coords.longitude
        });
        this.mapCenter = { lat: coords.latitude, lng: coords.longitude };
      }
    );
  }
}
```

---

## Implementation Checklist

### Phase 1: Booking Creation & Delivery (Week 1-2)
- [ ] Add geospatial fields to BookingRequestDTO
- [ ] Create DeliveryFeeCalculator service with RoutingService integration
- [ ] Modify BookingService.createBooking() to capture pickupLocation and calculate delivery fee
- [ ] Add integration tests for delivery fee calculation
- [ ] Update API documentation

### Phase 2: Check-in Upgrades (Week 3-4)
- [ ] Implement location variance check in CheckInService
- [ ] Add LocationDensity inference in GeofenceService
- [ ] Implement dynamic geofence radius
- [ ] Update CheckInService.confirmHandshake() to validate against pickupLocation
- [ ] Add location variance notifications
- [ ] Add integration tests for location verification

### Phase 3: ID Verification (Week 5-6)
- [ ] Create IdVerificationSubmitDTO
- [ ] Implement IdVerificationService with photo upload and storage
- [ ] Create MockIdVerificationProvider
- [ ] Add IdVerificationController endpoints
- [ ] Implement ID verification UI in Angular
- [ ] Add end-to-end tests

### Phase 4: Geospatial Search (Week 7-8)
- [ ] Add spatial index to cars table
- [ ] Implement CarRepository.findNearby() with ST_Distance_Sphere
- [ ] Enhance CarService with location obfuscation logic
- [ ] Add location obfuscation tests
- [ ] Implement search UI with Google Maps
- [ ] Performance testing with spatial queries

### Phase 5: Frontend Angular Components (Week 9-10)
- [ ] Build booking form with map location picker
- [ ] Implement check-in ID verification photo upload
- [ ] Build car search interface with geospatial results
- [ ] Add location obfuscation visual indicators ("~" prefix)
- [ ] Implement geolocation permission requests
- [ ] Add offline fallback for location services

---

## Security Considerations

### Data Protection
- **Location Obfuscation**: Non-booked cars show fuzzy coordinates (±500m) to prevent stalking
- **Photo Encryption**: ID verification photos stored with AES-256-GCM encryption
- **EXIF Data**: Strip EXIF metadata from user-uploaded photos before storage
- **Audit Trail**: All location-related events logged with timestamps and user IDs

### Access Control
- **RLS Enforcement**: Location details visible only to booking participants
- **Geofence Validation**: Remote handoff requires GPS proximity verification
- **Variance Thresholds**: > 2km deviation blocks check-in completion

### API Security
- **Rate Limiting**: Geospatial search queries limited to prevent abuse
- **JWT Validation**: All endpoints require valid authentication token
- **Spatial Index Queries**: Use parameterized queries to prevent SQL injection

---

## Performance Optimization

### Database Indexes
- `idx_booking_checkin_window(status, check_in_session_id, start_time)` - for check-in scheduler
- `SPATIAL INDEX idx_car_location(home_location)` - for geospatial search
- `idx_booking_pickup_location(pickup_latitude, pickup_longitude)` - for variance calculations

### Caching
- Cache location density inference results (5-minute TTL)
- Cache delivery fee estimates for common routes
- Cache geofence radius values per location density

### Query Optimization
- Use `JOIN FETCH` to avoid N+1 in check-in scheduler queries
- Batch geofence distance calculations when processing multiple bookings
- Limit spatial query results to 100 cars per search

---

## Testing Strategy

### Unit Tests
- DeliveryFeeCalculator with various distance scenarios
- LocationDensity inference logic
- Variance calculation (Haversine formula verification)
- Coordinate obfuscation randomness distribution

### Integration Tests
- End-to-end booking creation with location snapshot
- Check-in with location variance detection
- Geofence validation with dynamic radius
- ID verification photo upload and mock provider

### Performance Tests
- Spatial index performance with 100k+ cars
- Geofence calculation latency < 100ms
- Search query performance < 500ms for 50km radius

---

## Rollout Strategy

### Canary Deployment (10% of users)
1. Enable geospatial features for beta testers
2. Monitor location variance alerts and geofence blocks
3. Collect feedback on ID verification UX

### Gradual Rollout (Week by week)
- Week 1: Booking creation with location capture
- Week 2: Check-in location verification
- Week 3: ID verification (optional, opt-in)
- Week 4: Geospatial search (feature flag enabled)

### Rollback Plan
- Feature flags for each phase to enable quick rollback
- Fallback to in-person handoff if geofence validation fails
- Fallback to geocoding service if spatial queries fail

---

## References

- **GeoPoint**: `org.example.rentoza.common.GeoPoint` - Haversine distance calculation
- **EXIF Handling**: Apache Commons Imaging for photo metadata
- **Routing Service**: OSRM (Open Source Routing Machine) for driving distance
- **Geocoding**: Nominatim or Google Geocoding for address lookup
- **Spatial Queries**: MySQL ST_Distance_Sphere or PostgreSQL PostGIS

---

## Document History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-05 | Initial roadmap for Phases 1-4 |

