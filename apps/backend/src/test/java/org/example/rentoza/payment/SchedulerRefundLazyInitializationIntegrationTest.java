package org.example.rentoza.payment;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.*;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression: proves that CancellationRecord loaded via findByRefundStatus()
 * produces a detached LAZY booking proxy that throws LazyInitializationException
 * when accessed outside a persistence context, and that findByIdWithFullDetails()
 * avoids this by eagerly fetching all relations.
 *
 * <p>Production incident 2026-03-09: booking 62 — scheduler loaded CancellationRecord
 * via plain Spring Data query (no JOIN FETCH), then passed it into a REQUIRES_NEW
 * transaction where record.getBooking().getId() threw LazyInitializationException.
 *
 * <p>Requires Docker for Testcontainers PostgreSQL.
 *
 * @see SchedulerItemProcessor#processRefundSafely
 */
@DisplayName("Regression: CancellationRecord lazy booking proxy across transaction boundary")
class SchedulerRefundLazyInitializationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private CarRepository carRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private CancellationRecordRepository cancellationRecordRepository;

    @Test
    @DisplayName("findByRefundStatus returns detached record whose booking proxy throws LazyInitializationException")
    void findByRefundStatus_detachedBookingProxy_throwsLazyInit() {
        Long recordId = seedCancellationRecord();

        // Load outside transaction — mimics scheduler's non-transactional processCancellationRefunds()
        List<CancellationRecord> records = cancellationRecordRepository.findByRefundStatus(RefundStatus.PENDING);
        CancellationRecord detached = records.stream()
                .filter(r -> r.getId().equals(recordId))
                .findFirst()
                .orElseThrow();

        // booking is a LAZY proxy — accessing any field beyond getId() must throw
        assertThatThrownBy(() -> detached.getBooking().getStatus())
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    @DisplayName("findByIdWithFullDetails returns managed record with initialized booking, renter, car, owner")
    void findByIdWithFullDetails_allProxiesInitialized() {
        Long recordId = seedCancellationRecord();

        // Use the JOIN FETCH query that the fix relies on
        CancellationRecord record = cancellationRecordRepository.findByIdWithFullDetails(recordId)
                .orElseThrow();

        // All lazy relations must be accessible without LazyInitializationException
        assertThatCode(() -> {
            record.getBooking().getStatus();
            record.getBooking().getRenter().getFirstName();
            record.getBooking().getCar().getBrand();
            record.getBooking().getCar().getOwner().getFirstName();
        }).doesNotThrowAnyException();
    }

    private Long seedCancellationRecord() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            User owner = new User();
            owner.setFirstName("Owner");
            owner.setLastName("Test");
            owner.setEmail("owner-lazy-" + System.nanoTime() + "@test.local");
            owner.setPassword("{noop}password");
            owner.setRole(Role.USER);
            owner = userRepository.save(owner);

            User renter = new User();
            renter.setFirstName("Renter");
            renter.setLastName("Test");
            renter.setEmail("renter-lazy-" + System.nanoTime() + "@test.local");
            renter.setPassword("{noop}password");
            renter.setRole(Role.USER);
            renter = userRepository.save(renter);

            Car car = new Car();
            car.setBrand("BMW");
            car.setModel("X5");
            car.setYear(2023);
            car.setLocation("belgrade");
            car.setPricePerDay(BigDecimal.valueOf(4000));
            car.setFuelType(FuelType.BENZIN);
            car.setTransmissionType(TransmissionType.MANUAL);
            car.setOwner(owner);
            car.setApprovalStatus(ApprovalStatus.APPROVED);
            car.setListingStatus(ListingStatus.APPROVED);
            car.setAvailable(true);
            car = carRepository.save(car);

            LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Belgrade"));
            Booking booking = new Booking();
            booking.setCar(car);
            booking.setRenter(renter);
            booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
            booking.setStartTime(now.plusDays(1));
            booking.setEndTime(now.plusDays(3));
            booking.setStartTimeUtc(Instant.now().plusSeconds(86400));
            booking.setEndTimeUtc(Instant.now().plusSeconds(259200));
            booking.setTotalPrice(BigDecimal.valueOf(8000));
            booking = bookingRepository.save(booking);

            CancellationRecord record = CancellationRecord.builder()
                    .booking(booking)
                    .cancelledBy(CancelledBy.GUEST)
                    .reason(CancellationReason.GUEST_CHANGE_OF_PLANS)
                    .initiatedAt(now)
                    .processedAt(now)
                    .hoursBeforeTripStart(48L)
                    .originalTotalPrice(booking.getTotalPrice())
                    .bookingTotal(booking.getTotalPrice())
                    .penaltyAmount(BigDecimal.ZERO)
                    .refundToGuest(booking.getTotalPrice())
                    .payoutToHost(BigDecimal.ZERO)
                    .policyVersion("TURO_V1.0_2024")
                    .appliedRule("Guest cancelled >24h before trip - full refund")
                    .refundStatus(RefundStatus.PENDING)
                    .retryCount(0)
                    .maxRetries(3)
                    .build();
            record = cancellationRecordRepository.save(record);
            return record.getId();
        });
    }
}
