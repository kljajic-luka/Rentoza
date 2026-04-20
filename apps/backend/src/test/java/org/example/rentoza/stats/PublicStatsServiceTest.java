package org.example.rentoza.stats;

import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.ListingStatus;
import org.example.rentoza.dto.HomeStatsDTO;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Testovi za PublicStatsService — proverava pragove za javni prikaz statistika.
 */
@ExtendWith(MockitoExtension.class)
class PublicStatsServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private CarRepository carRepository;

    private PublicStatsService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PublicStatsService(reviewRepository, carRepository);
        setField("minRatingThreshold", 4.0);
        setField("minVehiclesThreshold", 10L);
        setField("minReviewsForRating", 5L);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = PublicStatsService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Nested
    @DisplayName("Prag za rejting")
    class RatingThresholdTests {

        @Test
        @DisplayName("Vraca null rejting kada nema recenzija")
        void shouldReturnNullRating_whenNoReviews() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(null);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(0L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(20L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.guestSatisfactionRating()).isNull();
        }

        @Test
        @DisplayName("Vraca null rejting kada ima manje od min recenzija")
        void shouldReturnNullRating_whenBelowMinReviews() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(4.5);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(3L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(20L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.guestSatisfactionRating()).isNull();
        }

        @Test
        @DisplayName("Vraca null rejting kada je prosek ispod praga")
        void shouldReturnNullRating_whenBelowMinRating() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(3.5);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(10L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(20L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.guestSatisfactionRating()).isNull();
        }

        @Test
        @DisplayName("Vraca rejting zaokruzen na jednu decimalu kada su svi pragovi zadovoljeni")
        void shouldReturnRoundedRating_whenAboveAllThresholds() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(4.67);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(8L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(20L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.guestSatisfactionRating()).isEqualTo(4.7);
        }
    }

    @Nested
    @DisplayName("Prag za broj vozila")
    class VehicleThresholdTests {

        @Test
        @DisplayName("Vraca null broj vozila kada je ispod praga")
        void shouldReturnNullVehicles_whenBelowThreshold() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(null);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(0L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(3L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.verifiedVehiclesCount()).isNull();
        }

        @Test
        @DisplayName("Vraca tacno 10 kada je na granici praga")
        void shouldReturnCount_whenExactlyAtThreshold() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(null);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(0L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(10L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.verifiedVehiclesCount()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Vraca broj vozila kada je iznad praga")
        void shouldReturnCount_whenAboveThreshold() {
            when(reviewRepository.findVisibleAverageRatingByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(null);
            when(reviewRepository.countVisibleByDirection(
                    eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(0L);
            when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(25L);

            HomeStatsDTO result = service.getHomeStats();

            assertThat(result.verifiedVehiclesCount()).isEqualTo(25L);
        }
    }

    @Test
    @DisplayName("Support availability je uvek null (uklonjen lazni 24/7)")
    void shouldAlwaysReturnNullSupport() {
        when(reviewRepository.findVisibleAverageRatingByDirection(
                eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(4.8);
        when(reviewRepository.countVisibleByDirection(
                eq(ReviewDirection.FROM_USER), any(Instant.class))).thenReturn(20L);
        when(carRepository.countByListingStatus(ListingStatus.APPROVED)).thenReturn(50L);

        HomeStatsDTO result = service.getHomeStats();

        assertThat(result.supportAvailability()).isNull();
    }
}
