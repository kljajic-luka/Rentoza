package org.example.rentoza.delivery;

import org.example.rentoza.car.Car;
import org.example.rentoza.common.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeliveryFeeCalculator} fee precedence logic.
 *
 * Covers:
 * - POI fixed fee > surcharge/minimum > per-km > system minimum
 * - Outside-radius behavior
 * - Fallback routing behaviour
 */
@ExtendWith(MockitoExtension.class)
class DeliveryFeeCalculatorTest {

    @Mock
    private OsrmRoutingService routingService;

    @Mock
    private DeliveryPoiRepository poiRepository;

    private DeliveryFeeCalculator calculator;

    // Test car at Belgrade center (lat=44.8153, lon=20.4603)
    private Car testCar;

    // Destination ~5km away
    private final GeoPoint destination = GeoPoint.of(44.8400, 20.4700);

    @BeforeEach
    void setUp() {
        calculator = new DeliveryFeeCalculator(routingService, poiRepository);

        testCar = new Car();
        testCar.setId(1L);
        GeoPoint carLocation = GeoPoint.of(44.8153, 20.4603);
        carLocation.setCity("Belgrade");
        testCar.setLocationGeoPoint(carLocation);
        testCar.setDeliveryRadiusKm(30.0);
        testCar.setDeliveryFeePerKm(new BigDecimal("2.00"));
    }

    private void stubRouting(double distanceKm, double durationMin) {
        when(routingService.calculateRouteWithFallback(any(), any()))
                .thenReturn(new OsrmRoutingService.RoutingResult(
                        distanceKm, durationMin, OsrmRoutingService.RoutingSource.OSRM));
    }

    private void stubRoutingFallback(double distanceKm, double durationMin) {
        when(routingService.calculateRouteWithFallback(any(), any()))
                .thenReturn(new OsrmRoutingService.RoutingResult(
                        distanceKm, durationMin, OsrmRoutingService.RoutingSource.HAVERSINE_FALLBACK));
    }

    // ========== Fee Precedence Tests ==========

    @Nested
    @DisplayName("Fee Precedence")
    class FeePrecedence {

        @Test
        @DisplayName("TC-FEE-01: POI fixed fee overrides per-km calculation")
        void fixedFeeOverridesPerKm() {
            stubRouting(10.0, 15.0);

            DeliveryPoi airportPoi = new DeliveryPoi("Airport", "BEG",
                    GeoPoint.of(44.84, 20.47), DeliveryPoi.PoiType.AIRPORT);
            airportPoi.setFixedFee(new BigDecimal("25.00"));
            airportPoi.setRadiusKm(5.0);
            airportPoi.setPriority(10);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.of(airportPoi));

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isTrue();
            assertThat(result.fee()).isEqualByComparingTo("25.00");
            // calculatedFee should be the per-km amount (2.00 * 10 = 20.00)
            assertThat(result.calculatedFee()).isEqualByComparingTo("20.00");
            assertThat(result.hasPoiOverride()).isTrue();
            assertThat(result.appliedPoiCode()).isEqualTo("BEG");
        }

        @Test
        @DisplayName("TC-FEE-02: POI surcharge added on top of per-km fee")
        void surchargeAddedToPerKmFee() {
            stubRouting(10.0, 15.0);

            DeliveryPoi centerPoi = new DeliveryPoi("City Center", "BG-CTR",
                    GeoPoint.of(44.84, 20.47), DeliveryPoi.PoiType.CITY_CENTER);
            centerPoi.setSurcharge(new BigDecimal("15.00"));
            centerPoi.setRadiusKm(5.0);
            centerPoi.setPriority(5);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.of(centerPoi));

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isTrue();
            // per-km = 2.00 * 10 = 20.00, + surcharge 15.00 = 35.00
            assertThat(result.fee()).isEqualByComparingTo("35.00");
        }

        @Test
        @DisplayName("TC-FEE-03: POI minimum fee enforced when calculated is lower")
        void minimumFeeEnforced() {
            stubRouting(1.0, 3.0); // very short distance

            DeliveryPoi stationPoi = new DeliveryPoi("Station", "NS-RAIL",
                    GeoPoint.of(44.84, 20.47), DeliveryPoi.PoiType.TRAIN_STATION);
            stationPoi.setMinimumFee(new BigDecimal("10.00"));
            stationPoi.setRadiusKm(5.0);
            stationPoi.setPriority(5);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.of(stationPoi));

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isTrue();
            // per-km = 2.00 * 1 = 2.00, but minimum is 10.00
            assertThat(result.fee()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("TC-FEE-04: Standard per-km when no POI")
        void standardPerKmWithoutPoi() {
            stubRouting(10.0, 15.0);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.empty());

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isTrue();
            // per-km = 2.00 * 10 = 20.00
            assertThat(result.fee()).isEqualByComparingTo("20.00");
            assertThat(result.hasPoiOverride()).isFalse();
        }

        @Test
        @DisplayName("TC-FEE-05: System minimum fee (5.00) enforced when per-km is below")
        void systemMinimumFeeEnforced() {
            stubRouting(0.5, 2.0); // very short

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.empty());

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isTrue();
            // per-km = 2.00 * 0.5 = 1.00, but system minimum is 5.00
            assertThat(result.fee()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("TC-FEE-06: Fixed fee beats surcharge+minimum when all set on same POI")
        void fixedFeeBeatsAllOtherPoiRules() {
            stubRouting(10.0, 15.0);

            DeliveryPoi poi = new DeliveryPoi("Complex POI", "CMPLX",
                    GeoPoint.of(44.84, 20.47), DeliveryPoi.PoiType.AIRPORT);
            poi.setFixedFee(new BigDecimal("30.00"));
            poi.setSurcharge(new BigDecimal("50.00"));
            poi.setMinimumFee(new BigDecimal("100.00"));
            poi.setRadiusKm(5.0);
            poi.setPriority(10);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.of(poi));

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            // Fixed fee takes absolute precedence
            assertThat(result.fee()).isEqualByComparingTo("30.00");
        }
    }

    // ========== Outside-Radius Tests ==========

    @Nested
    @DisplayName("Outside Radius")
    class OutsideRadius {

        @Test
        @DisplayName("TC-FEE-07: Destination exceeds delivery radius → unavailable")
        void outsideDeliveryRadius() {
            testCar.setDeliveryRadiusKm(5.0);
            stubRouting(15.0, 25.0); // 15km > 5km radius

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isFalse();
            assertThat(result.unavailableReason()).contains("outside");
            assertThat(result.maxRadiusKm()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("TC-FEE-08: Car without delivery → unavailable")
        void carWithoutDelivery() {
            testCar.setDeliveryFeePerKm(null);
            testCar.setDeliveryRadiusKm(null);

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isFalse();
        }

        @Test
        @DisplayName("TC-FEE-09: Car without geolocation → unavailable")
        void carWithoutLocation() {
            testCar.setLocationGeoPoint(new GeoPoint());

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isFalse();
        }
    }

    // ========== Routing Fallback Tests ==========

    @Nested
    @DisplayName("Routing Fallback")
    class RoutingFallback {

        @Test
        @DisplayName("TC-FEE-10: Haversine fallback routing still produces valid fee")
        void haversineFallbackProducesValidFee() {
            stubRoutingFallback(8.0, 12.0);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.empty());

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.available()).isTrue();
            assertThat(result.fee()).isEqualByComparingTo("16.00"); // 2.00 * 8 = 16.00
            assertThat(result.routingSource()).isEqualTo(OsrmRoutingService.RoutingSource.HAVERSINE_FALLBACK);
            assertThat(result.isDistanceAccurate()).isFalse();
        }

        @Test
        @DisplayName("TC-FEE-11: OSRM routing is marked as accurate")
        void osrmRoutingIsAccurate() {
            stubRouting(10.0, 15.0);

            when(poiRepository.findHighestPriorityPoiAtPoint(anyDouble(), anyDouble()))
                    .thenReturn(Optional.empty());

            DeliveryFeeCalculator.DeliveryFeeResult result =
                    calculator.calculateDeliveryFee(testCar, destination);

            assertThat(result.routingSource()).isEqualTo(OsrmRoutingService.RoutingSource.OSRM);
            assertThat(result.isDistanceAccurate()).isTrue();
        }
    }

    // ========== Default Per-Km Rate ==========

    @Test
    @DisplayName("TC-FEE-12: Car with deliveryFeePerKm=null is treated as not offering delivery")
    void noDeliveryFeePerKmMeansNoDelivery() {
        testCar.setDeliveryFeePerKm(null);

        DeliveryFeeCalculator.DeliveryFeeResult result =
                calculator.calculateDeliveryFee(testCar, destination);

        // offersDelivery() returns false when deliveryFeePerKm is null
        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).contains("not offered");
    }
}
