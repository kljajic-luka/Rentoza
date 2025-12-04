package org.example.rentoza.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for migrating legacy string-based locations to geospatial coordinates.
 * 
 * Uses Nominatim (OpenStreetMap) for geocoding with strict rate-limiting
 * to comply with their usage policy.
 * 
 * NOMINATIM USAGE POLICY:
 * - Maximum 1 request per second
 * - Provide User-Agent header
 * - Cache results (we cache in database)
 * - No heavy automated queries
 * 
 * @see <a href="https://operations.osmfoundation.org/policies/nominatim/">Nominatim Usage Policy</a>
 * @since 2.4.0 (Geospatial Location Migration)
 */
@Service
public class LocationMigrationService {

    private static final Logger log = LoggerFactory.getLogger(LocationMigrationService.class);

    /**
     * Minimum delay between Nominatim requests (milliseconds).
     * Nominatim policy requires max 1 request/second; we use 1100ms for safety margin.
     */
    private static final long NOMINATIM_RATE_LIMIT_MS = 1100;

    /**
     * Serbia bounding box for result validation
     */
    private static final double SERBIA_MIN_LAT = 42.2;
    private static final double SERBIA_MAX_LAT = 46.2;
    private static final double SERBIA_MIN_LON = 18.8;
    private static final double SERBIA_MAX_LON = 23.0;

    private final RestTemplate restTemplate;
    private final CarRepository carRepository;
    private final AtomicBoolean migrationInProgress = new AtomicBoolean(false);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);

    @Value("${rentoza.nominatim.base-url:https://nominatim.openstreetmap.org}")
    private String nominatimBaseUrl;

    @Value("${rentoza.nominatim.enabled:true}")
    private boolean nominatimEnabled;

    public LocationMigrationService(RestTemplate restTemplate, CarRepository carRepository) {
        this.restTemplate = restTemplate;
        this.carRepository = carRepository;
    }

    /**
     * Start asynchronous migration of all cars without geolocation.
     * Only one migration can run at a time.
     * 
     * @return CompletableFuture with migration result
     */
    @Async
    public CompletableFuture<MigrationResult> migrateAllAsync() {
        if (!migrationInProgress.compareAndSet(false, true)) {
            log.warn("Migration already in progress, skipping");
            return CompletableFuture.completedFuture(
                    MigrationResult.alreadyRunning()
            );
        }

        try {
            successCount.set(0);
            failureCount.set(0);

            List<Car> carsNeedingGeocoding = carRepository.findCarsNeedingGeocoding(1000);
            int total = carsNeedingGeocoding.size();
            
            log.info("Starting location migration for {} cars", total);

            for (int i = 0; i < carsNeedingGeocoding.size(); i++) {
                Car car = carsNeedingGeocoding.get(i);
                
                try {
                    boolean success = geocodeAndUpdateCar(car);
                    if (success) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Failed to geocode car {}: {}", car.getId(), e.getMessage());
                    failureCount.incrementAndGet();
                }

                // Rate limiting: wait between requests
                if (i < carsNeedingGeocoding.size() - 1) {
                    try {
                        Thread.sleep(NOMINATIM_RATE_LIMIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Migration interrupted");
                        break;
                    }
                }

                // Progress logging every 10 cars
                if ((i + 1) % 10 == 0) {
                    log.info("Migration progress: {}/{} ({} success, {} failed)",
                            i + 1, total, successCount.get(), failureCount.get());
                }
            }

            MigrationResult result = new MigrationResult(
                    true,
                    total,
                    successCount.get(),
                    failureCount.get(),
                    null
            );

            log.info("Migration completed: {} total, {} success, {} failed",
                    total, successCount.get(), failureCount.get());

            return CompletableFuture.completedFuture(result);

        } finally {
            migrationInProgress.set(false);
        }
    }

    /**
     * Migrate a batch of cars (for chunked processing)
     * 
     * @param batchSize Number of cars to process
     * @return Migration result
     */
    @Transactional
    public MigrationResult migrateBatch(int batchSize) {
        if (!nominatimEnabled) {
            return new MigrationResult(false, 0, 0, 0, "Nominatim geocoding is disabled");
        }

        List<Car> cars = carRepository.findCarsNeedingGeocoding(batchSize);
        
        int success = 0;
        int failure = 0;

        for (int i = 0; i < cars.size(); i++) {
            Car car = cars.get(i);
            
            try {
                if (geocodeAndUpdateCar(car)) {
                    success++;
                } else {
                    failure++;
                }
            } catch (Exception e) {
                log.error("Geocoding failed for car {}: {}", car.getId(), e.getMessage());
                failure++;
            }

            // Rate limiting between requests (except for last one)
            if (i < cars.size() - 1) {
                try {
                    Thread.sleep(NOMINATIM_RATE_LIMIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new MigrationResult(true, cars.size(), success, failure, null);
    }

    /**
     * Geocode a single car's location and update the database.
     * 
     * @param car Car to geocode
     * @return true if geocoding succeeded, false otherwise
     */
    @Transactional
    public boolean geocodeAndUpdateCar(Car car) {
        String location = car.getLocation();
        
        if (location == null || location.isBlank()) {
            log.debug("Car {} has no location string to geocode", car.getId());
            return false;
        }

        // Add "Serbia" to improve geocoding accuracy
        String searchQuery = location.contains("Serbia") || location.contains("Srbija")
                ? location
                : location + ", Serbia";

        try {
            GeoPoint geoPoint = geocodeLocation(searchQuery);
            
            if (geoPoint != null) {
                car.setLocationGeoPoint(geoPoint);
                carRepository.save(car);
                
                log.info("Geocoded car {}: '{}' -> ({}, {})",
                        car.getId(), location, 
                        geoPoint.getLatitude(), geoPoint.getLongitude());
                
                return true;
            } else {
                log.warn("No geocoding result for car {}: '{}'", car.getId(), location);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Geocoding error for car {}: {}", car.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Geocode a location string using Nominatim.
     * 
     * @param location Location string (e.g., "Belgrade, Serbia")
     * @return GeoPoint if geocoding succeeded, null otherwise
     */
    public GeoPoint geocodeLocation(String location) {
        if (!nominatimEnabled) {
            log.debug("Nominatim disabled, skipping geocoding");
            return null;
        }

        if (location == null || location.isBlank()) {
            return null;
        }

        try {
            String url = String.format(
                    "%s/search?q=%s&format=json&limit=1&countrycodes=rs",
                    nominatimBaseUrl,
                    java.net.URLEncoder.encode(location, java.nio.charset.StandardCharsets.UTF_8)
            );

            log.debug("Nominatim request: {}", url);

            // Note: In production, add User-Agent header via RestTemplate customization
            NominatimResult[] results = restTemplate.getForObject(url, NominatimResult[].class);

            if (results != null && results.length > 0) {
                NominatimResult result = results[0];
                double lat = Double.parseDouble(result.lat);
                double lon = Double.parseDouble(result.lon);

                // Validate result is within Serbia bounds
                if (lat >= SERBIA_MIN_LAT && lat <= SERBIA_MAX_LAT &&
                    lon >= SERBIA_MIN_LON && lon <= SERBIA_MAX_LON) {
                    
                    return GeoPoint.of(lat, lon);
                } else {
                    log.warn("Geocoding result outside Serbia bounds: ({}, {})", lat, lon);
                    return null;
                }
            }

            return null;

        } catch (RestClientException e) {
            log.error("Nominatim request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get migration status
     */
    public MigrationStatus getStatus() {
        long totalCars = carRepository.count();
        long geocodedCars = carRepository.countCarsWithGeoLocation();
        List<Car> pending = carRepository.findCarsNeedingGeocoding(1);
        
        return new MigrationStatus(
                totalCars,
                geocodedCars,
                totalCars - geocodedCars,
                migrationInProgress.get(),
                successCount.get(),
                failureCount.get()
        );
    }

    /**
     * Check if migration is currently running
     */
    public boolean isMigrationInProgress() {
        return migrationInProgress.get();
    }

    // ========== DTOs ==========

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NominatimResult {
        @JsonProperty("lat")
        public String lat;

        @JsonProperty("lon")
        public String lon;

        @JsonProperty("display_name")
        public String displayName;
    }

    public record MigrationResult(
            boolean completed,
            int totalProcessed,
            int successCount,
            int failureCount,
            String error
    ) {
        public static MigrationResult alreadyRunning() {
            return new MigrationResult(false, 0, 0, 0, "Migration already in progress");
        }
    }

    public record MigrationStatus(
            long totalCars,
            long geocodedCars,
            long pendingCars,
            boolean migrationInProgress,
            int currentSessionSuccess,
            int currentSessionFailure
    ) {
        public double completionPercentage() {
            return totalCars > 0 ? (geocodedCars * 100.0 / totalCars) : 100.0;
        }
    }
}
