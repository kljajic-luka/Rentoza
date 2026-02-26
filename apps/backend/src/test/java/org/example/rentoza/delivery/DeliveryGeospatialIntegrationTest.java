package org.example.rentoza.delivery;

import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.testconfig.AbstractPostGisIntegrationTest;
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
 * Integration tests for the delivery/geospatial layer.
 *
 * Covers:
 * - Spatial repository behavior (ST_DWithin, lon/lat ordering, priority/radius tie-break)
 * - POI containment and ranking
 * - Outside-radius behavior
 *
 * Uses PostGIS-enabled Testcontainer to exercise real spatial queries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryGeospatialIntegrationTest extends AbstractPostGisIntegrationTest {

    @Autowired
    private DeliveryPoiRepository poiRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    // ========== Serbia coordinates for testing ==========
    // Belgrade Nikola Tesla Airport  (44.8184, 20.3091)
    // Belgrade city center / Terazije (44.8153, 20.4603)
    // Novi Sad city center            (45.2671, 19.8335)
    // Nis Aerodrom                    (43.3375, 21.8544)

    @BeforeAll
    void initPostGis() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // PostGIS extension is already in tiger schema (handled by AbstractPostGisIntegrationTest)
            // Add geography column not covered by JPA entity (matches prod Flyway V-migration)
            stmt.execute("ALTER TABLE delivery_pois ADD COLUMN IF NOT EXISTS "
                    + "location_point geography(Point, 4326)");
            // Create function to auto-populate location_point from lat/lon
            // Uses unqualified PostGIS functions — resolved via search_path to tiger schema
            stmt.execute("""
                    CREATE OR REPLACE FUNCTION update_poi_location_point()
                    RETURNS TRIGGER AS $$
                    BEGIN
                        IF NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL THEN
                            NEW.location_point := ST_SetSRID(
                                ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
                        ELSE
                            NEW.location_point := NULL;
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            stmt.execute("DROP TRIGGER IF EXISTS trg_poi_location_point ON delivery_pois");
            stmt.execute("""
                    CREATE TRIGGER trg_poi_location_point
                    BEFORE INSERT OR UPDATE ON delivery_pois
                    FOR EACH ROW EXECUTE FUNCTION update_poi_location_point();
                    """);
        }
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // Clean slate
        tx.execute(status -> {
            poiRepository.deleteAll();
            poiRepository.flush();
            return null;
        });
    }

    // ========== Helpers ==========

    private DeliveryPoi createPoi(String name, String code, double lat, double lon,
                                   DeliveryPoi.PoiType type, double radiusKm,
                                   BigDecimal fixedFee, BigDecimal minimumFee,
                                   BigDecimal surcharge, int priority) {
        DeliveryPoi poi = new DeliveryPoi();
        poi.setName(name);
        poi.setCode(code);
        poi.setLocation(GeoPoint.of(lat, lon));
        poi.setPoiType(type);
        poi.setRadiusKm(radiusKm);
        poi.setFixedFee(fixedFee);
        poi.setMinimumFee(minimumFee);
        poi.setSurcharge(surcharge);
        poi.setPriority(priority);
        poi.setActive(true);
        return poi;
    }

    // ========== TC-GEO-01: ST_DWithin basic containment ==========

    @Test
    @DisplayName("TC-GEO-01: Point inside POI radius is found by ST_DWithin")
    void pointInsideRadiusIsFound() {
        tx.execute(status -> {
            // Airport POI at Belgrade Airport with 5km radius
            poiRepository.save(createPoi(
                    "Belgrade Airport", "BEG",
                    44.8184, 20.3091,
                    DeliveryPoi.PoiType.AIRPORT, 5.0,
                    new BigDecimal("25.00"), null, null, 10));
            return null;
        });

        // Query a point ~1km from the airport center (within 5km radius)
        List<DeliveryPoi> found = poiRepository.findPoisContainingPoint(44.8200, 20.3100);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCode()).isEqualTo("BEG");
    }

    // ========== TC-GEO-02: Point outside POI radius is NOT found ==========

    @Test
    @DisplayName("TC-GEO-02: Point outside POI radius is NOT returned")
    void pointOutsideRadiusIsNotFound() {
        tx.execute(status -> {
            poiRepository.save(createPoi(
                    "Belgrade Airport", "BEG",
                    44.8184, 20.3091,
                    DeliveryPoi.PoiType.AIRPORT, 2.0,  // tight 2km radius
                    new BigDecimal("25.00"), null, null, 10));
            return null;
        });

        // Terazije is ~12km from the airport — well outside 2km
        List<DeliveryPoi> found = poiRepository.findPoisContainingPoint(44.8153, 20.4603);
        assertThat(found).isEmpty();
    }

    // ========== TC-GEO-03: Lon/lat ordering correctness ==========

    @Test
    @DisplayName("TC-GEO-03: ST_MakePoint uses (longitude, latitude) — verify correct coordinate order")
    void lonLatOrderingIsCorrect() {
        tx.execute(status -> {
            // POI at Nis Airport (43.3375, 21.8544) with 3km radius
            poiRepository.save(createPoi(
                    "Nis Airport", "NIS-AIR",
                    43.3375, 21.8544,
                    DeliveryPoi.PoiType.AIRPORT, 3.0,
                    new BigDecimal("20.00"), null, null, 5));
            return null;
        });

        // Correct coordinates: (lat=43.3380, lon=21.8550) — ~100m away
        List<DeliveryPoi> correctOrder = poiRepository.findPoisContainingPoint(43.3380, 21.8550);
        assertThat(correctOrder).hasSize(1)
                .extracting(DeliveryPoi::getCode).containsExactly("NIS-AIR");

        // Swapped coordinates would be lat=21.8550, lon=43.3380 — outside Serbia entirely
        // This should NOT match the POI (proves lon/lat order is correct in SQL)
        List<DeliveryPoi> swapped = poiRepository.findPoisContainingPoint(21.8550, 43.3380);
        assertThat(swapped).isEmpty();
    }

    // ========== TC-GEO-04: Priority/radius tie-break ==========

    @Test
    @DisplayName("TC-GEO-04: Overlapping POIs ranked by priority DESC, then radius ASC")
    void priorityAndRadiusTieBreak() {
        tx.execute(status -> {
            // Two overlapping POIs at Belgrade center area
            // Higher priority, larger radius
            poiRepository.save(createPoi(
                    "Belgrade City Center", "BG-CTR",
                    44.8153, 20.4603,
                    DeliveryPoi.PoiType.CITY_CENTER, 5.0,
                    null, new BigDecimal("10.00"), new BigDecimal("15.00"), 20));

            // Lower priority, smaller radius
            poiRepository.save(createPoi(
                    "Terazije Shopping", "BG-TRZ",
                    44.8155, 20.4590,
                    DeliveryPoi.PoiType.SHOPPING_MALL, 1.0,
                    null, new BigDecimal("5.00"), null, 5));
            return null;
        });

        // Point at Terazije, within both POIs
        List<DeliveryPoi> pois = poiRepository.findPoisContainingPoint(44.8155, 20.4595);
        assertThat(pois).hasSizeGreaterThanOrEqualTo(2);
        // First should be highest priority (BG-CTR, priority=20)
        assertThat(pois.get(0).getCode()).isEqualTo("BG-CTR");
        // Second should be lower priority (BG-TRZ, priority=5)
        assertThat(pois.get(1).getCode()).isEqualTo("BG-TRZ");

        // findHighestPriorityPoiAtPoint should return only the highest-priority one
        Optional<DeliveryPoi> highest = poiRepository.findHighestPriorityPoiAtPoint(44.8155, 20.4595);
        assertThat(highest).isPresent();
        assertThat(highest.get().getCode()).isEqualTo("BG-CTR");
    }

    // ========== TC-GEO-05: Same priority → smallest radius wins ==========

    @Test
    @DisplayName("TC-GEO-05: Same priority — smallest radius is returned first")
    void samePrioritySmallestRadiusFirst() {
        tx.execute(status -> {
            poiRepository.save(createPoi(
                    "Wide Area", "WIDE",
                    44.8153, 20.4603,
                    DeliveryPoi.PoiType.BUSINESS_DISTRICT, 10.0,
                    null, null, null, 5));

            poiRepository.save(createPoi(
                    "Narrow Area", "NARROW",
                    44.8155, 20.4600,
                    DeliveryPoi.PoiType.CITY_CENTER, 2.0,
                    null, null, null, 5));
            return null;
        });

        List<DeliveryPoi> pois = poiRepository.findPoisContainingPoint(44.8154, 20.4601);
        assertThat(pois).hasSizeGreaterThanOrEqualTo(2);
        // Same priority → smaller radius first
        assertThat(pois.get(0).getCode()).isEqualTo("NARROW");
        assertThat(pois.get(1).getCode()).isEqualTo("WIDE");
    }

    // ========== TC-GEO-06: Inactive POI not returned ==========

    @Test
    @DisplayName("TC-GEO-06: Inactive POIs are excluded from spatial queries")
    void inactivePoisExcluded() {
        tx.execute(status -> {
            DeliveryPoi inactive = createPoi(
                    "Deactivated POI", "DEACT",
                    44.8153, 20.4603,
                    DeliveryPoi.PoiType.CITY_CENTER, 10.0,
                    new BigDecimal("99.00"), null, null, 100);
            inactive.setActive(false);
            poiRepository.save(inactive);
            return null;
        });

        List<DeliveryPoi> found = poiRepository.findPoisContainingPoint(44.8153, 20.4603);
        assertThat(found).isEmpty();
    }

    // ========== TC-GEO-07: isPointWithinAnyPoi ==========

    @Test
    @DisplayName("TC-GEO-07: isPointWithinAnyPoi returns correct boolean")
    void isPointWithinAnyPoiWorks() {
        tx.execute(status -> {
            poiRepository.save(createPoi(
                    "Belgrade Airport", "BEG",
                    44.8184, 20.3091,
                    DeliveryPoi.PoiType.AIRPORT, 3.0,
                    new BigDecimal("25.00"), null, null, 10));
            return null;
        });

        // Inside
        assertThat(poiRepository.isPointWithinAnyPoi(44.8190, 20.3100)).isTrue();
        // Far outside
        assertThat(poiRepository.isPointWithinAnyPoi(45.2671, 19.8335)).isFalse();
    }

    // ========== TC-GEO-08: findPoisNearPoint (distance search) ==========

    @Test
    @DisplayName("TC-GEO-08: findPoisNearPoint returns POIs within max distance")
    void findPoisNearPoint() {
        tx.execute(status -> {
            poiRepository.save(createPoi(
                    "Belgrade Airport", "BEG",
                    44.8184, 20.3091,
                    DeliveryPoi.PoiType.AIRPORT, 3.0,
                    new BigDecimal("25.00"), null, null, 10));

            poiRepository.save(createPoi(
                    "Novi Sad Center", "NS-CTR",
                    45.2671, 19.8335,
                    DeliveryPoi.PoiType.CITY_CENTER, 5.0,
                    null, new BigDecimal("8.00"), null, 5));
            return null;
        });

        // Search near Belgrade center — airport is ~12km away, Novi Sad is ~75km
        List<DeliveryPoi> nearby = poiRepository.findPoisNearPoint(44.8153, 20.4603, 20.0);
        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).getCode()).isEqualTo("BEG");

        // Broader search should find both
        List<DeliveryPoi> broader = poiRepository.findPoisNearPoint(44.8153, 20.4603, 100.0);
        assertThat(broader).hasSize(2);
    }
}
