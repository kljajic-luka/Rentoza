package org.example.rentoza.delivery;

import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public delivery endpoints for calculating fees and retrieving POIs.
 * 
 * Provides delivery fee calculation and POI lookup for the frontend booking flow.
 * Uses {@link DeliveryFeeCalculator} for accurate fee calculation with OSRM routing.
 * 
 * ENDPOINTS:
 * - GET /api/delivery/calculate-fee - Calculate delivery fee for car to destination
 * - GET /api/delivery/pois/nearby - Find POIs near a location
 * - GET /api/delivery/pois - Get all active POIs
 * 
 * NOTE: Geocoding endpoints remain in {@link LocationController}.
 * 
 * @see DeliveryFeeCalculator
 * @see DeliveryPoi
 * @since 2.4.0 (Geospatial Location Migration)
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryController.class);

    private final DeliveryFeeCalculator deliveryFeeCalculator;
    private final DeliveryPoiRepository poiRepository;
    private final CarRepository carRepository;

    public DeliveryController(
            DeliveryFeeCalculator deliveryFeeCalculator,
            DeliveryPoiRepository poiRepository,
            CarRepository carRepository) {
        this.deliveryFeeCalculator = deliveryFeeCalculator;
        this.poiRepository = poiRepository;
        this.carRepository = carRepository;
    }

    /**
     * Calculate delivery fee for a car to a destination.
     * 
     * @param carId     ID of the car to deliver
     * @param latitude  Delivery destination latitude
     * @param longitude Delivery destination longitude
     * @return Delivery fee calculation result
     */
    @GetMapping("/calculate-fee")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DeliveryFeeResponseDTO> calculateDeliveryFee(
            @RequestParam Long carId,
            @RequestParam Double latitude,
            @RequestParam Double longitude) {

        log.debug("Calculating delivery fee for car {} to ({}, {})", carId, latitude, longitude);

        // Validate Serbia bounds
        if (!isWithinSerbia(latitude, longitude)) {
            log.warn("Destination coordinates ({}, {}) are outside Serbia bounds", latitude, longitude);
            return ResponseEntity.badRequest()
                    .body(DeliveryFeeResponseDTO.error("Destination must be within Serbia"));
        }

        // Find the car
        Car car = carRepository.findById(carId).orElse(null);
        if (car == null) {
            log.warn("Car {} not found for delivery fee calculation", carId);
            return ResponseEntity.notFound().build();
        }

        // Calculate delivery fee
        GeoPoint destination = GeoPoint.of(latitude, longitude);
        DeliveryFeeCalculator.DeliveryFeeResult result = deliveryFeeCalculator.calculateDeliveryFee(car, destination);

        // Map to frontend-compatible DTO
        DeliveryFeeResponseDTO response = mapToResponseDTO(result);

        return ResponseEntity.ok(response);
    }

    /**
     * Find POIs near a location.
     * 
     * @param latitude      Center latitude
     * @param longitude     Center longitude
     * @param maxDistanceKm Maximum distance to search (default 10km)
     * @return List of nearby POIs
     */
    @GetMapping("/pois/nearby")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DeliveryPoiResponseDTO>> getNearbyPois(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(defaultValue = "10") Double maxDistanceKm) {

        log.debug("Finding POIs near ({}, {}) within {} km", latitude, longitude, maxDistanceKm);

        // Validate Serbia bounds
        if (!isWithinSerbia(latitude, longitude)) {
            log.warn("Coordinates ({}, {}) are outside Serbia bounds", latitude, longitude);
            return ResponseEntity.badRequest().body(List.of());
        }

        List<DeliveryPoi> pois = poiRepository.findPoisNearPoint(latitude, longitude, maxDistanceKm);
        List<DeliveryPoiResponseDTO> response = pois.stream()
                .map(this::mapToPoiResponseDTO)
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Get all active POIs.
     * Used for frontend caching and map display.
     * 
     * @return List of all active POIs
     */
    @GetMapping("/pois")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DeliveryPoiResponseDTO>> getAllPois() {
        log.debug("Fetching all active POIs");

        List<DeliveryPoi> pois = poiRepository.findByActiveTrue();
        List<DeliveryPoiResponseDTO> response = pois.stream()
                .map(this::mapToPoiResponseDTO)
                .toList();

        return ResponseEntity.ok(response);
    }

    // ========== PRIVATE HELPERS ==========

    private boolean isWithinSerbia(double latitude, double longitude) {
        return latitude >= 42.2 && latitude <= 46.2
                && longitude >= 18.8 && longitude <= 23.0;
    }

    /**
     * Map internal DeliveryFeeResult to frontend-compatible DTO.
     * Matches TypeScript interface: DeliveryFeeResult in location.model.ts
     */
    private DeliveryFeeResponseDTO mapToResponseDTO(DeliveryFeeCalculator.DeliveryFeeResult result) {
        return new DeliveryFeeResponseDTO(
                result.available(),
                result.fee() != null ? result.fee().doubleValue() : null,
                result.calculatedFee() != null ? result.calculatedFee().doubleValue() : null,
                result.distanceKm(),
                result.estimatedMinutes(),
                result.routingSource() != null ? result.routingSource().name() : null,
                result.appliedPoiCode(),
                result.unavailableReason(),
                result.maxRadiusKm(),
                buildBreakdown(result)
        );
    }

    /**
     * Build fee breakdown for display in frontend
     */
    private DeliveryFeeResponseDTO.FeeBreakdown buildBreakdown(DeliveryFeeCalculator.DeliveryFeeResult result) {
        if (!result.available() || result.fee() == null) {
            return null;
        }

        // Simple breakdown: base fee = 0, distance fee = calculated fee
        // POI surcharge is difference between final and calculated fee
        double baseFee = 0.0;
        double distanceFee = result.calculatedFee() != null ? result.calculatedFee().doubleValue() : 0.0;
        Double poiSurcharge = null;
        String poiName = null;

        if (result.appliedPoiCode() != null && result.calculatedFee() != null && result.fee() != null) {
            double diff = result.fee().doubleValue() - result.calculatedFee().doubleValue();
            if (Math.abs(diff) > 0.01) {
                poiSurcharge = diff;
                poiName = result.appliedPoiCode(); // Frontend can look up full name
            }
        }

        return new DeliveryFeeResponseDTO.FeeBreakdown(baseFee, distanceFee, poiSurcharge, poiName);
    }

    /**
     * Map DeliveryPoi entity to frontend-compatible DTO.
     * Matches TypeScript interface: DeliveryPoi in location.model.ts
     */
    private DeliveryPoiResponseDTO mapToPoiResponseDTO(DeliveryPoi poi) {
        return new DeliveryPoiResponseDTO(
                poi.getId(),
                poi.getName(),
                poi.getCode(),
                poi.getLocation() != null ? poi.getLocation().getLatitude().doubleValue() : null,
                poi.getLocation() != null ? poi.getLocation().getLongitude().doubleValue() : null,
                poi.getRadiusKm(),
                poi.getPoiType().name(),
                poi.getFixedFee() != null ? poi.getFixedFee().doubleValue() : null,
                poi.getMinimumFee() != null ? poi.getMinimumFee().doubleValue() : null,
                poi.getSurcharge() != null ? poi.getSurcharge().doubleValue() : null,
                poi.getActive()
        );
    }

    // ========== RESPONSE DTOs ==========

    /**
     * Delivery fee calculation response.
     * Matches frontend TypeScript interface: DeliveryFeeResult
     * 
     * @see rentoza-frontend/src/app/core/models/location.model.ts
     */
    public record DeliveryFeeResponseDTO(
            boolean available,
            Double fee,
            Double calculatedFee,
            Double distanceKm,
            Double estimatedMinutes,
            String routingSource,
            String appliedPoiCode,
            String unavailableReason,
            Double maxRadiusKm,
            FeeBreakdown breakdown
    ) {
        /**
         * Fee breakdown for detailed display
         */
        public record FeeBreakdown(
                double baseFee,
                double distanceFee,
                Double poiSurcharge,
                String poiName
        ) {}

        /**
         * Create error response
         */
        public static DeliveryFeeResponseDTO error(String reason) {
            return new DeliveryFeeResponseDTO(
                    false, null, null, null, null,
                    null, null, reason, null, null
            );
        }
    }

    /**
     * POI response DTO.
     * Matches frontend TypeScript interface: DeliveryPoi
     * 
     * @see rentoza-frontend/src/app/core/models/location.model.ts
     */
    public record DeliveryPoiResponseDTO(
            Long id,
            String name,
            String code,
            Double latitude,
            Double longitude,
            Double radiusKm,
            String poiType,
            Double fixedFee,
            Double minimumFee,
            Double surcharge,
            Boolean active
    ) {}
}
