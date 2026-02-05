package org.example.rentoza.delivery;

import org.example.rentoza.car.Car;
import org.example.rentoza.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Service for calculating delivery fees based on distance and POI rules.
 * 
 * FEE CALCULATION PRIORITY:
 * 1. POI fixed fee (if destination is within a POI with fixed fee)
 * 2. POI surcharge + calculated fee (if destination is within a POI with surcharge)
 * 3. Standard per-km calculation using car's delivery_fee_per_km
 * 4. Minimum fee enforcement (from POI or system default)
 * 
 * DISTANCE CALCULATION:
 * - Uses OSRM for accurate road distance when available
 * - Falls back to Haversine with 1.3x multiplier if OSRM unavailable
 * 
 * @see DeliveryPoi
 * @see OsrmRoutingService
 * @since 2.4.0 (Geospatial Location Migration)
 */
@Service
public class DeliveryFeeCalculator {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFeeCalculator.class);

    /**
     * System-wide minimum delivery fee (applied if car doesn't have own minimum)
     */
    private static final BigDecimal SYSTEM_MINIMUM_FEE = new BigDecimal("5.00");

    /**
     * Default per-km rate if car doesn't specify one
     */
    private static final BigDecimal DEFAULT_PER_KM_RATE = new BigDecimal("1.50");

    private final OsrmRoutingService routingService;
    private final DeliveryPoiRepository poiRepository;

    public DeliveryFeeCalculator(OsrmRoutingService routingService, DeliveryPoiRepository poiRepository) {
        this.routingService = routingService;
        this.poiRepository = poiRepository;
    }

    /**
     * Calculate delivery fee for a specific car to a destination.
     * 
     * @param car         The car being delivered
     * @param destination User's requested pickup location
     * @return Delivery fee calculation result
     */
    public DeliveryFeeResult calculateDeliveryFee(Car car, GeoPoint destination) {
        if (car == null || destination == null) {
            throw new IllegalArgumentException("Car and destination are required");
        }

        if (!car.hasGeoLocation()) {
            log.warn("Cannot calculate delivery fee: car {} has no geolocation", car.getId());
            return DeliveryFeeResult.unavailable("Car location not available");
        }

        if (!car.offersDelivery()) {
            log.debug("Car {} does not offer delivery", car.getId());
            return DeliveryFeeResult.unavailable("Delivery not offered for this car");
        }

        GeoPoint carLocation = car.getLocationGeoPoint();

        // Step 1: Calculate distance using OSRM
        OsrmRoutingService.RoutingResult routingResult = 
                routingService.calculateRouteWithFallback(carLocation, destination);

        double distanceKm = routingResult.distanceKm();
        
        // Step 2: Check if destination is within car's delivery radius
        if (car.getDeliveryRadiusKm() != null && distanceKm > car.getDeliveryRadiusKm()) {
            log.debug("Destination {} km exceeds car's delivery radius {} km", 
                    String.format("%.2f", distanceKm), car.getDeliveryRadiusKm());
            return DeliveryFeeResult.outsideRange(
                    distanceKm, 
                    car.getDeliveryRadiusKm(),
                    routingResult.source()
            );
        }

        // Step 3: Check for POI overrides at destination
        Optional<DeliveryPoi> destinationPoi = poiRepository.findHighestPriorityPoiAtPoint(
                destination.getLatitude().doubleValue(),
                destination.getLongitude().doubleValue()
        );

        // Step 4: Calculate base fee
        BigDecimal perKmRate = car.getDeliveryFeePerKm() != null ? 
                car.getDeliveryFeePerKm() : DEFAULT_PER_KM_RATE;
        
        BigDecimal calculatedFee = perKmRate
                .multiply(BigDecimal.valueOf(distanceKm))
                .setScale(2, RoundingMode.HALF_UP);

        // Step 5: Apply POI rules if applicable
        BigDecimal finalFee;
        String poiCode = null;

        if (destinationPoi.isPresent()) {
            DeliveryPoi poi = destinationPoi.get();
            poiCode = poi.getCode();
            finalFee = poi.calculateEffectiveFee(calculatedFee);
            
            log.debug("POI {} applied: calculated={}, final={}", 
                    poi.getCode(), calculatedFee, finalFee);
        } else {
            // No POI, apply system minimum
            finalFee = calculatedFee.max(SYSTEM_MINIMUM_FEE);
        }

        return DeliveryFeeResult.success(
                finalFee,
                calculatedFee,
                distanceKm,
                routingResult.durationMinutes(),
                routingResult.source(),
                poiCode
        );
    }

    /**
     * Quick check if delivery is available (without full fee calculation)
     */
    public boolean isDeliveryAvailable(Car car, GeoPoint destination) {
        if (car == null || destination == null || !car.hasGeoLocation() || !car.offersDelivery()) {
            return false;
        }

        double distance = car.getLocationGeoPoint().distanceTo(destination);
        return car.getDeliveryRadiusKm() == null || distance <= car.getDeliveryRadiusKm();
    }

    /**
     * Get straight-line distance estimate (faster, for UI previews)
     */
    public double getEstimatedDistance(Car car, GeoPoint destination) {
        if (car == null || destination == null || !car.hasGeoLocation()) {
            return 0.0;
        }
        return car.getLocationGeoPoint().distanceTo(destination);
    }

    // ========== RESULT CLASSES ==========

    /**
     * Result of delivery fee calculation
     */
    public record DeliveryFeeResult(
            boolean available,
            BigDecimal fee,
            BigDecimal calculatedFee,
            Double distanceKm,
            Double estimatedMinutes,
            OsrmRoutingService.RoutingSource routingSource,
            String appliedPoiCode,
            String unavailableReason,
            Double maxRadiusKm
    ) {
        /**
         * Successful calculation with fee
         */
        public static DeliveryFeeResult success(
                BigDecimal fee,
                BigDecimal calculatedFee,
                double distanceKm,
                double estimatedMinutes,
                OsrmRoutingService.RoutingSource source,
                String poiCode
        ) {
            return new DeliveryFeeResult(
                    true, fee, calculatedFee, distanceKm, estimatedMinutes,
                    source, poiCode, null, null
            );
        }

        /**
         * Delivery unavailable (car doesn't offer it, no location, etc.)
         */
        public static DeliveryFeeResult unavailable(String reason) {
            return new DeliveryFeeResult(
                    false, null, null, null, null,
                    null, null, reason, null
            );
        }

        /**
         * Destination is outside car's delivery range
         */
        public static DeliveryFeeResult outsideRange(
                double actualDistance,
                double maxRadius,
                OsrmRoutingService.RoutingSource source
        ) {
            return new DeliveryFeeResult(
                    false, null, null, actualDistance, null,
                    source, null, "Destination outside delivery range", maxRadius
            );
        }

        /**
         * Check if a POI fee override was applied
         */
        public boolean hasPoiOverride() {
            return appliedPoiCode != null;
        }

        /**
         * Check if distance calculation was accurate (OSRM) vs estimated
         */
        public boolean isDistanceAccurate() {
            return routingSource == OsrmRoutingService.RoutingSource.OSRM;
        }
    }
}
