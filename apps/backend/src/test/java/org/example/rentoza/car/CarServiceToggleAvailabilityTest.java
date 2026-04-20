package org.example.rentoza.car;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.car.storage.CarImageStorageService;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CarService toggleAvailability")
class CarServiceToggleAvailabilityTest {

    @Mock
    private CarRepository repo;

    @Mock
    private BookingRepository bookingRepo;

    @Mock
    private ReviewRepository reviewRepo;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private CarImageStorageService carImageStorageService;

    @Mock
    private MarketplaceComplianceService marketplaceComplianceService;

    private CarService carService;
    private User owner;
    private Car car;

    @BeforeEach
    void setUp() {
        carService = new CarService(
                repo,
                bookingRepo,
                reviewRepo,
                currentUser,
                carImageStorageService,
                marketplaceComplianceService
        );

        owner = new User();
        owner.setId(15L);
        owner.setEmail("owner@example.com");

        car = new Car();
        car.setId(100L);
        car.setOwner(owner);
        car.setBrand("Toyota");
        car.setModel("Yaris");
        car.setLocation("belgrade");
        car.setListingStatus(ListingStatus.APPROVED);
        car.setApprovalStatus(ApprovalStatus.APPROVED);
        car.setAvailable(false);
    }

    @Test
    @DisplayName("should reactivate an approved compliant inactive car")
    void shouldReactivateApprovedCompliantInactiveCar() {
        when(repo.findById(100L)).thenReturn(Optional.of(car));
        when(repo.save(any(Car.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketplaceComplianceService.isEligibleForActivation(car)).thenReturn(true);

        CarResponseDTO result = carService.toggleAvailability(100L, true, owner);

        assertThat(result.isAvailable()).isTrue();
        assertThat(car.isAvailable()).isTrue();
        verify(repo).save(car);
    }

    @Test
    @DisplayName("should block activation when the listing is no longer activation eligible")
    void shouldBlockActivationWhenCarIsNotActivationEligible() {
        when(repo.findById(100L)).thenReturn(Optional.of(car));
        when(marketplaceComplianceService.isEligibleForActivation(car)).thenReturn(false);

        assertThatThrownBy(() -> carService.toggleAvailability(100L, true, owner))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cannot activate car");

        assertThat(car.isAvailable()).isFalse();
        verify(repo, never()).save(any(Car.class));
    }
}