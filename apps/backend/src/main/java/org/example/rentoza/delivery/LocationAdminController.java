package org.example.rentoza.delivery;

import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin controller for geospatial location management.
 * 
 * Provides endpoints for:
 * - Viewing migration status
 * - Triggering batch geocoding
 * - Managing POIs
 * - Testing delivery fee calculations
 * 
 * All endpoints require ADMIN role.
 * 
 * @since 2.4.0 (Geospatial Location Migration)
 */
@RestController
@RequestMapping("/api/admin/locations")
@PreAuthorize("hasRole('ADMIN')")
public class LocationAdminController {

    private static final Logger log = LoggerFactory.getLogger(LocationAdminController.class);

    private final LocationMigrationService migrationService;
    private final DeliveryFeeCalculator feeCalculator;
    private final DeliveryPoiRepository poiRepository;
    private final CarRepository carRepository;
    private final OsrmRoutingService routingService;

    public LocationAdminController(
            LocationMigrationService migrationService,
            DeliveryFeeCalculator feeCalculator,
            DeliveryPoiRepository poiRepository,
            CarRepository carRepository,
            OsrmRoutingService routingService
    ) {
        this.migrationService = migrationService;
        this.feeCalculator = feeCalculator;
        this.poiRepository = poiRepository;
        this.carRepository = carRepository;
        this.routingService = routingService;
    }

    // ========== MIGRATION ENDPOINTS ==========

    /**
     * Get current migration status
     */
    @GetMapping("/migration/status")
    public ResponseEntity<LocationMigrationService.MigrationStatus> getMigrationStatus() {
        return ResponseEntity.ok(migrationService.getStatus());
    }

    /**
     * Start batch migration (synchronous, processes up to 50 cars)
     */
    @PostMapping("/migration/batch")
    public ResponseEntity<LocationMigrationService.MigrationResult> runBatchMigration(
            @RequestParam(defaultValue = "50") int batchSize
    ) {
        log.info("Admin triggered batch migration for {} cars", batchSize);
        
        int cappedBatchSize = Math.min(batchSize, 100); // Cap at 100 for safety
        LocationMigrationService.MigrationResult result = migrationService.migrateBatch(cappedBatchSize);
        
        return ResponseEntity.ok(result);
    }

    /**
     * Start full async migration (background process)
     */
    @PostMapping("/migration/start-async")
    public ResponseEntity<Map<String, String>> startAsyncMigration() {
        if (migrationService.isMigrationInProgress()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Migration already in progress"
            ));
        }

        migrationService.migrateAllAsync();
        
        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "message", "Migration started in background. Check /migration/status for progress."
        ));
    }

    /**
     * Geocode a single location (for testing)
     */
    @GetMapping("/geocode")
    public ResponseEntity<?> testGeocode(@RequestParam String location) {
        GeoPoint result = migrationService.geocodeLocation(location);
        
        if (result != null) {
            return ResponseEntity.ok(Map.of(
                    "location", location,
                    "latitude", result.getLatitude(),
                    "longitude", result.getLongitude()
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "location", location,
                    "result", "No geocoding result found"
            ));
        }
    }

    // ========== POI MANAGEMENT ==========

    /**
     * List all POIs
     */
    @GetMapping("/pois")
    public ResponseEntity<List<DeliveryPoi>> listPois() {
        return ResponseEntity.ok(poiRepository.findAll());
    }

    /**
     * Get POIs at a specific location
     */
    @GetMapping("/pois/at")
    public ResponseEntity<List<DeliveryPoi>> getPoisAtLocation(
            @RequestParam double latitude,
            @RequestParam double longitude
    ) {
        List<DeliveryPoi> pois = poiRepository.findPoisContainingPoint(latitude, longitude);
        return ResponseEntity.ok(pois);
    }

    /**
     * Create a new POI
     */
    @PostMapping("/pois")
    public ResponseEntity<DeliveryPoi> createPoi(@RequestBody DeliveryPoiRequest request) {
        DeliveryPoi poi = new DeliveryPoi();
        poi.setName(request.name());
        poi.setCode(request.code());
        poi.setLocation(GeoPoint.of(request.latitude(), request.longitude()));
        poi.setRadiusKm(request.radiusKm() != null ? request.radiusKm() : 2.0);
        poi.setPoiType(request.poiType());
        poi.setFixedFee(request.fixedFee());
        poi.setMinimumFee(request.minimumFee());
        poi.setSurcharge(request.surcharge());
        poi.setPriority(request.priority() != null ? request.priority() : 0);
        poi.setActive(request.active() != null ? request.active() : true);
        poi.setNotes(request.notes());

        DeliveryPoi saved = poiRepository.save(poi);
        log.info("Created POI: {} ({})", saved.getName(), saved.getCode());
        
        return ResponseEntity.ok(saved);
    }

    /**
     * Update a POI
     */
    @PutMapping("/pois/{id}")
    public ResponseEntity<DeliveryPoi> updatePoi(
            @PathVariable Long id,
            @RequestBody DeliveryPoiRequest request
    ) {
        return poiRepository.findById(id)
                .map(poi -> {
                    if (request.name() != null) poi.setName(request.name());
                    if (request.latitude() != null && request.longitude() != null) {
                        poi.setLocation(GeoPoint.of(request.latitude(), request.longitude()));
                    }
                    if (request.radiusKm() != null) poi.setRadiusKm(request.radiusKm());
                    if (request.poiType() != null) poi.setPoiType(request.poiType());
                    if (request.fixedFee() != null) poi.setFixedFee(request.fixedFee());
                    if (request.minimumFee() != null) poi.setMinimumFee(request.minimumFee());
                    if (request.surcharge() != null) poi.setSurcharge(request.surcharge());
                    if (request.priority() != null) poi.setPriority(request.priority());
                    if (request.active() != null) poi.setActive(request.active());
                    if (request.notes() != null) poi.setNotes(request.notes());

                    DeliveryPoi saved = poiRepository.save(poi);
                    log.info("Updated POI: {} ({})", saved.getName(), saved.getCode());
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a POI (soft delete by setting active=false)
     */
    @DeleteMapping("/pois/{id}")
    public ResponseEntity<Void> deletePoi(@PathVariable Long id) {
        return poiRepository.findById(id)
                .map(poi -> {
                    poi.setActive(false);
                    poiRepository.save(poi);
                    log.info("Deactivated POI: {} ({})", poi.getName(), poi.getCode());
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== DELIVERY FEE TESTING ==========

    /**
     * Calculate delivery fee for a car to a destination (for testing)
     */
    @GetMapping("/delivery-fee/calculate")
    public ResponseEntity<DeliveryFeeCalculator.DeliveryFeeResult> calculateDeliveryFee(
            @RequestParam Long carId,
            @RequestParam double latitude,
            @RequestParam double longitude
    ) {
        return carRepository.findById(carId)
                .map(car -> {
                    GeoPoint destination = GeoPoint.of(latitude, longitude);
                    DeliveryFeeCalculator.DeliveryFeeResult result = 
                            feeCalculator.calculateDeliveryFee(car, destination);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Calculate driving distance between two points (for testing OSRM)
     */
    @GetMapping("/route/calculate")
    public ResponseEntity<OsrmRoutingService.RoutingResult> calculateRoute(
            @RequestParam double originLat,
            @RequestParam double originLon,
            @RequestParam double destLat,
            @RequestParam double destLon
    ) {
        GeoPoint origin = GeoPoint.of(originLat, originLon);
        GeoPoint destination = GeoPoint.of(destLat, destLon);
        
        OsrmRoutingService.RoutingResult result = 
                routingService.calculateRouteWithFallback(origin, destination);
        
        return ResponseEntity.ok(result);
    }

    // ========== NEARBY SEARCH TESTING ==========

    /**
     * Search for cars near a location (for testing SPATIAL queries)
     */
    @GetMapping("/cars/nearby")
    public ResponseEntity<List<Car>> findNearbyCars(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "10") double radiusKm
    ) {
        List<Car> cars = carRepository.findNearby(latitude, longitude, radiusKm);
        return ResponseEntity.ok(cars);
    }

    // ========== REQUEST DTOs ==========

    public record DeliveryPoiRequest(
            String name,
            String code,
            Double latitude,
            Double longitude,
            Double radiusKm,
            DeliveryPoi.PoiType poiType,
            java.math.BigDecimal fixedFee,
            java.math.BigDecimal minimumFee,
            java.math.BigDecimal surcharge,
            Integer priority,
            Boolean active,
            String notes
    ) {}
}
