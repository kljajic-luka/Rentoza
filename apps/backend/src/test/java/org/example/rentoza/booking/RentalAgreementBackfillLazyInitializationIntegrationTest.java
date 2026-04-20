package org.example.rentoza.booking;

import org.example.rentoza.car.*;
import org.example.rentoza.testconfig.AbstractIntegrationTest;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression: proves that bookings loaded via findByStatusIn() have uninitialized
 * renter and car.owner LAZY proxies that throw LazyInitializationException when
 * accessed outside a transaction, and that findByStatusInWithRelations() fixes this.
 *
 * <p>Production incident 2026-03-10 00:00 UTC: RentalAgreementBackfillService loaded
 * bookings via findByStatusIn (JOIN FETCH car only), then passed them to
 * generateAgreement() which accessed booking.getRenter() and car.getOwner().
 * User#13 (car owner) triggered LazyInitializationException.
 *
 * <p>Requires Docker for Testcontainers PostgreSQL.
 *
 * @see RentalAgreementBackfillService#backfillAgreements()
 * @see RentalAgreementService#generateAgreement(Booking)
 */
@DisplayName("Regression: Booking backfill lazy renter/owner proxy across transaction boundary")
class RentalAgreementBackfillLazyInitializationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private CarRepository carRepository;
    @Autowired private BookingRepository bookingRepository;

    @Test
    @DisplayName("findByStatusIn returns bookings with uninitialized renter proxy (failure mode)")
    void findByStatusIn_detachedRenterProxy_throwsLazyInit() {
        seedActiveBooking();

        // Load outside transaction — mimics the backfill service's non-transactional method
        List<Booking> bookings = bookingRepository.findByStatusIn(List.of(BookingStatus.ACTIVE));
        assertThat(bookings).isNotEmpty();

        Booking detached = bookings.get(0);

        // car was JOIN FETCHed — should work
        assertThatCode(() -> detached.getCar().getBrand())
                .doesNotThrowAnyException();

        // renter was NOT JOIN FETCHed — must throw outside transaction
        assertThatThrownBy(() -> detached.getRenter().getFirstName())
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    @DisplayName("findByStatusIn returns bookings with uninitialized car.owner proxy (failure mode)")
    void findByStatusIn_detachedCarOwnerProxy_throwsLazyInit() {
        seedActiveBooking();

        List<Booking> bookings = bookingRepository.findByStatusIn(List.of(BookingStatus.ACTIVE));
        Booking detached = bookings.get(0);

        assertThatThrownBy(() -> detached.getCar().getOwner().getFirstName())
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    @DisplayName("findByStatusInWithRelations returns bookings with initialized renter and car.owner (fix)")
    void findByStatusInWithRelations_allProxiesInitialized() {
        seedActiveBooking();

        List<Booking> bookings = bookingRepository.findByStatusInWithRelations(List.of(BookingStatus.ACTIVE));
        assertThat(bookings).isNotEmpty();

        Booking booking = bookings.get(0);

        // All lazy relations must be accessible without LazyInitializationException
        assertThatCode(() -> {
            booking.getCar().getBrand();
            booking.getCar().getOwner().getFirstName();
            booking.getRenter().getFirstName();
        }).doesNotThrowAnyException();

        assertThat(booking.getRenter().getFirstName()).isEqualTo("Renter");
        assertThat(booking.getCar().getOwner().getFirstName()).isEqualTo("Owner");
    }

    private void seedActiveBooking() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            User owner = new User();
            owner.setFirstName("Owner");
            owner.setLastName("Backfill");
            owner.setEmail("owner-backfill-" + System.nanoTime() + "@test.local");
            owner.setPassword("{noop}password");
            owner.setRole(Role.USER);
            owner = userRepository.save(owner);

            User renter = new User();
            renter.setFirstName("Renter");
            renter.setLastName("Backfill");
            renter.setEmail("renter-backfill-" + System.nanoTime() + "@test.local");
            renter.setPassword("{noop}password");
            renter.setRole(Role.USER);
            renter = userRepository.save(renter);

            Car car = new Car();
            car.setBrand("Audi");
            car.setModel("A4");
            car.setYear(2022);
            car.setLocation("novi-sad");
            car.setPricePerDay(BigDecimal.valueOf(3500));
            car.setFuelType(FuelType.DIZEL);
            car.setTransmissionType(TransmissionType.AUTOMATIC);
            car.setOwner(owner);
            car.setApprovalStatus(ApprovalStatus.APPROVED);
            car.setListingStatus(ListingStatus.APPROVED);
            car.setAvailable(true);
            car = carRepository.save(car);

            LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Belgrade"));
            Booking booking = new Booking();
            booking.setCar(car);
            booking.setRenter(renter);
            booking.setStatus(BookingStatus.ACTIVE);
            booking.setStartTime(now.plusDays(1));
            booking.setEndTime(now.plusDays(3));
            booking.setStartTimeUtc(Instant.now().plusSeconds(86400));
            booking.setEndTimeUtc(Instant.now().plusSeconds(259200));
            booking.setTotalPrice(BigDecimal.valueOf(7000));
            bookingRepository.save(booking);
        });
    }
}
