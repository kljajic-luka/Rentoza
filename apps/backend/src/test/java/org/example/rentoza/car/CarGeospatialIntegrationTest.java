package org.example.rentoza.car;

import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.testconfig.AbstractPostGisIntegrationTest;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link CarRepository} geospatial queries (findNearby,
 * findIfWithinDeliveryRange, findCarsOfferingDeliveryTo).
 *
 * <p>These queries use {@code tiger.ST_DWithin} (production Supabase schema).
 * The base class migrates PostGIS to the {@code tiger} schema so the
 * schema-qualified SQL executes identically to production.
 *
 * <p>Covers:
 * <ul>
 *   <li>TC-CAR-GEO-01: Cars within radius are returned, ordered by distance</li>
 *   <li>TC-CAR-GEO-02: Cars outside radius are excluded</li>
 *   <li>TC-CAR-GEO-03: Unavailable cars excluded from findNearby</li>
 *   <li>TC-CAR-GEO-04: Cars without geo-coordinates excluded from findNearby</li>
 *   <li>TC-CAR-GEO-05: findIfWithinDeliveryRange respects delivery_radius_km</li>
 *   <li>TC-CAR-GEO-06: findCarsOfferingDeliveryTo returns cars within their delivery radius</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarGeospatialIntegrationTest extends AbstractPostGisIntegrationTest {

    @Autowired private CarRepository carRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private Long hostId;

    // ========== Serbia coordinates for testing ==========
    // Belgrade city center / Terazije  (44.8153, 20.4603)
    // Belgrade Airport                 (44.8184, 20.3091)  ~12km from center
    // Novi Sad city center             (45.2671, 19.8335)  ~75km from Belgrade
    // Zemun (Belgrade suburb)          (44.8438, 20.4113)  ~5km from center

    @BeforeAll
    void initSchema() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // Add geography column to cars table (matches prod V48 migration)
            // Uses tiger.geography since PostGIS is in the tiger schema
            stmt.execute("ALTER TABLE cars ADD COLUMN IF NOT EXISTS "
                    + "location_point tiger.geography(Point, 4326)");

            // Create trigger to auto-populate location_point from location_latitude/location_longitude
            // (matches V48 update_car_location_point)
            stmt.execute("""
                    CREATE OR REPLACE FUNCTION update_car_location_point()
                    RETURNS TRIGGER AS $$
                    BEGIN
                        IF NEW.location_latitude IS NOT NULL AND NEW.location_longitude IS NOT NULL THEN
                            NEW.location_point := tiger.ST_SetSRID(
                                tiger.ST_MakePoint(NEW.location_longitude, NEW.location_latitude),
                                4326)::tiger.geography;
                        ELSE
                            NEW.location_point := NULL;
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            stmt.execute("DROP TRIGGER IF EXISTS trg_update_car_location_point ON cars");
            stmt.execute("""
                    CREATE TRIGGER trg_update_car_location_point
                    BEFORE INSERT OR UPDATE OF location_latitude, location_longitude
                    ON cars
                    FOR EACH ROW EXECUTE FUNCTION update_car_location_point();
                    """);

            // Create GIST index (matches prod V48)
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cars_location_point_gist "
                    + "ON cars USING GIST (location_point)");
        }
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            carRepository.deleteAll();
            userRepository.deleteAll();
            return null;
        });

        tx.execute(status -> {
            User host = new User();
            host.setEmail("geo-host-" + System.nanoTime() + "@test.com");
            host.setFirstName("GeoHost");
            host.setLastName("Test");
            host.setPassword("pass");
            host.setAge(30);
            host = userRepository.save(host);
            hostId = host.getId();
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        tx.execute(status -> {
            carRepository.deleteAll();
            userRepository.deleteAll();
            return null;
        });
    }

    private Car createCarAtLocation(String brand, String model, double lat, double lon,
                                     boolean available, Double deliveryRadiusKm,
                                     BigDecimal deliveryFeePerKm) {
        User host = userRepository.findById(hostId).orElseThrow();
        Car car = new Car();
        car.setOwner(host);
        car.setBrand(brand);
        car.setModel(model);
        car.setYear(2023);
        car.setPricePerDay(new BigDecimal("100.00"));
        car.setAvailable(available);
        car.setApprovalStatus(ApprovalStatus.APPROVED);
        car.setListingStatus(ListingStatus.APPROVED);
        car.setLocation(brand + " " + model);

        GeoPoint geoPoint = GeoPoint.of(lat, lon);
        geoPoint.setCity("Belgrade");
        car.setLocationGeoPoint(geoPoint);

        if (deliveryRadiusKm != null) {
            car.setDeliveryRadiusKm(deliveryRadiusKm);
        }
        if (deliveryFeePerKm != null) {
            car.setDeliveryFeePerKm(deliveryFeePerKm);
        }

        return carRepository.save(car);
    }

    // ========== TC-CAR-GEO-01: findNearby returns cars within radius, ordered by distance ==========

    @Test
    @DisplayName("TC-CAR-GEO-01: findNearby returns cars within radius ordered by distance ASC")
    void findNearbyReturnsOrderedByDistance() {
        tx.execute(status -> {
            // Car A: at Zemun (~5km from center)
            createCarAtLocation("VW", "Golf", 44.8438, 20.4113,
                    true, null, null);
            // Car B: at Belgrade center (closest)
            createCarAtLocation("BMW", "X5", 44.8153, 20.4603,
                    true, null, null);
            // Car C: at Novi Sad (~75km from center)
            createCarAtLocation("Audi", "A4", 45.2671, 19.8335,
                    true, null, null);
            return null;
        });

        // Search from Belgrade center, 20km radius
        List<Car> nearby = carRepository.findNearby(44.8153, 20.4603, 20.0);

        // Should find center + Zemun, but NOT Novi Sad (75km > 20km)
        assertThat(nearby).hasSize(2);
        // Ordered by distance: center first (~0km), then Zemun (~5km)
        assertThat(nearby.get(0).getBrand()).isEqualTo("BMW");
        assertThat(nearby.get(1).getBrand()).isEqualTo("VW");
    }

    // ========== TC-CAR-GEO-02: Cars outside radius are excluded ==========

    @Test
    @DisplayName("TC-CAR-GEO-02: findNearby excludes cars outside search radius")
    void findNearbyExcludesOutsideRadius() {
        tx.execute(status -> {
            // Car at Novi Sad (~75km away)
            createCarAtLocation("Mercedes", "C300", 45.2671, 19.8335,
                    true, null, null);
            return null;
        });

        // Search from Belgrade center, 10km radius
        List<Car> nearby = carRepository.findNearby(44.8153, 20.4603, 10.0);
        assertThat(nearby).isEmpty();

        // Broader radius (100km) should find it
        List<Car> broader = carRepository.findNearby(44.8153, 20.4603, 100.0);
        assertThat(broader).hasSize(1);
        assertThat(broader.get(0).getBrand()).isEqualTo("Mercedes");
    }

    // ========== TC-CAR-GEO-03: Unavailable cars excluded ==========

    @Test
    @DisplayName("TC-CAR-GEO-03: findNearby excludes unavailable cars")
    void findNearbyExcludesUnavailableCars() {
        tx.execute(status -> {
            // Available car
            createCarAtLocation("BMW", "X3", 44.8153, 20.4603,
                    true, null, null);
            // Unavailable car at same location
            createCarAtLocation("Audi", "Q7", 44.8160, 20.4610,
                    false, null, null);
            return null;
        });

        List<Car> nearby = carRepository.findNearby(44.8153, 20.4603, 5.0);
        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).getBrand()).isEqualTo("BMW");
    }

    // ========== TC-CAR-GEO-04: Cars without coordinates excluded ==========

    @Test
    @DisplayName("TC-CAR-GEO-04: findNearby excludes cars without geo-coordinates")
    void findNearbyExcludesNullCoordinates() {
        tx.execute(status -> {
            // Car with coordinates
            createCarAtLocation("VW", "Passat", 44.8153, 20.4603,
                    true, null, null);

            // Car without coordinates
            User host = userRepository.findById(hostId).orElseThrow();
            Car noGeo = new Car();
            noGeo.setOwner(host);
            noGeo.setBrand("Fiat");
            noGeo.setModel("500");
            noGeo.setYear(2023);
            noGeo.setPricePerDay(new BigDecimal("50.00"));
            noGeo.setAvailable(true);
            noGeo.setApprovalStatus(ApprovalStatus.APPROVED);
            noGeo.setListingStatus(ListingStatus.APPROVED);
            noGeo.setLocation("beograd");
            // No locationGeoPoint set — location_point will be NULL
            carRepository.save(noGeo);
            return null;
        });

        List<Car> nearby = carRepository.findNearby(44.8153, 20.4603, 20.0);
        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).getBrand()).isEqualTo("VW");
    }

    // ========== TC-CAR-GEO-05: findIfWithinDeliveryRange respects delivery_radius_km ==========

    @Test
    @DisplayName("TC-CAR-GEO-05: findIfWithinDeliveryRange returns car only when within delivery radius")
    void findIfWithinDeliveryRange() {
        Car car = tx.execute(status ->
                createCarAtLocation("BMW", "X5", 44.8153, 20.4603,
                        true, 10.0, new BigDecimal("2.00")));

        // Zemun is ~5km away — within 10km delivery radius
        Optional<Car> withinRange = carRepository.findIfWithinDeliveryRange(
                car.getId(), 44.8438, 20.4113);
        assertThat(withinRange).isPresent();

        // Novi Sad is ~75km away — outside 10km delivery radius
        Optional<Car> outsideRange = carRepository.findIfWithinDeliveryRange(
                car.getId(), 45.2671, 19.8335);
        assertThat(outsideRange).isEmpty();
    }

    // ========== TC-CAR-GEO-06: findCarsOfferingDeliveryTo returns cars within their delivery radius ==========

    @Test
    @DisplayName("TC-CAR-GEO-06: findCarsOfferingDeliveryTo returns cars that can reach the location")
    void findCarsOfferingDeliveryTo() {
        tx.execute(status -> {
            // Car A: at center, 10km delivery radius — CAN deliver to Zemun (~5km)
            createCarAtLocation("BMW", "X5", 44.8153, 20.4603,
                    true, 10.0, new BigDecimal("2.00"));

            // Car B: at center, 3km delivery radius — CANNOT deliver to Zemun (5km > 3km)
            createCarAtLocation("VW", "Polo", 44.8153, 20.4603,
                    true, 3.0, new BigDecimal("1.50"));

            // Car C: at center, no delivery (null radius/fee)
            createCarAtLocation("Fiat", "Punto", 44.8153, 20.4603,
                    true, null, null);
            return null;
        });

        // Request delivery to Zemun
        List<Car> canDeliver = carRepository.findCarsOfferingDeliveryTo(44.8438, 20.4113);

        // Only Car A (10km radius) can deliver to Zemun
        assertThat(canDeliver).hasSize(1);
        assertThat(canDeliver.get(0).getBrand()).isEqualTo("BMW");
    }
}
