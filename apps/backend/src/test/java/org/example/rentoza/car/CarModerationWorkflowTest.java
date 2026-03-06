package org.example.rentoza.car;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.dto.CarRequestDTO;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Car Moderation Workflow")
class CarModerationWorkflowTest {

    @Mock private CarRepository carRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private CurrentUser currentUser;
    @Mock private CarImageStorageService carImageStorageService;
    @Mock private MarketplaceComplianceService marketplaceComplianceService;

    private CarService carService;
    private User owner;

    @BeforeEach
    void setUp() {
        carService = new CarService(
            carRepository,
            bookingRepository,
            reviewRepository,
            currentUser,
            carImageStorageService,
            marketplaceComplianceService);

        owner = new User();
        owner.setId(7L);
        owner.setEmail("owner@test.com");
        owner.setIsIdentityVerified(true);
    }

    @Test
    @DisplayName("Material owner edits send approved listings back to moderation")
    void materialEditSendsApprovedListingBackToModeration() {
        Car car = approvedCar();
        when(carRepository.findById(99L)).thenReturn(Optional.of(car));
        when(carRepository.save(any(Car.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CarRequestDTO dto = new CarRequestDTO();
        dto.setPricePerDay(new BigDecimal("9500"));

        carService.updateCar(99L, dto, owner);

        assertThat(car.getListingStatus()).isEqualTo(ListingStatus.PENDING_APPROVAL);
        assertThat(car.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(car.isAvailable()).isFalse();
        assertThat(car.getApprovedAt()).isNull();
        assertThat(car.getApprovedBy()).isNull();
    }

    @Test
    @DisplayName("Public inventory excludes cars that fail live marketplace visibility")
    void getAllCarsFiltersOutNonVisibleCars() {
        when(currentUser.idOrNull()).thenReturn(null);

        Car visibleCar = approvedCar();

        Car expiredCar = approvedCar();
        expiredCar.setId(2L);
        expiredCar.setRegistrationExpiryDate(LocalDate.now().minusDays(1));

        when(carRepository.findByAvailableTrueAndListingStatus(ListingStatus.APPROVED))
                .thenReturn(List.of(visibleCar, expiredCar));
        when(marketplaceComplianceService.filterMarketplaceVisible(List.of(visibleCar, expiredCar)))
            .thenReturn(List.of(visibleCar));

        List<CarResponseDTO> results = carService.getAllCars();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(1L);
    }

    private Car approvedCar() {
        Car car = new Car();
        car.setId(1L);
        car.setOwner(owner);
        car.setBrand("BMW");
        car.setModel("X5");
        car.setYear(2023);
        car.setPricePerDay(new BigDecimal("8000"));
        car.setLocation("beograd");
        car.setLicensePlate("BG-123-AB");
        car.setApprovalStatus(ApprovalStatus.APPROVED);
        car.setListingStatus(ListingStatus.APPROVED);
        car.setAvailable(true);
        car.setApprovedAt(Instant.now());
        car.setApprovedBy(owner);
        car.setDocumentsVerifiedAt(LocalDateTime.now().minusDays(1));
        car.setDocumentsVerifiedBy(owner);
        car.setRegistrationExpiryDate(LocalDate.now().plusMonths(6));
        car.setInsuranceExpiryDate(LocalDate.now().plusMonths(6));
        car.setTechnicalInspectionExpiryDate(LocalDate.now().plusMonths(3));
        return car;
    }
}