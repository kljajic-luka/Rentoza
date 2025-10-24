package org.example.rentoza.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByCarId(Long carId);

    List<Booking> findByRenterEmailIgnoreCase(String email);

    List<Booking> findByCarIdAndEndDateAfterAndStartDateBefore(Long carId, LocalDate start, LocalDate end);

    List<Booking> findByCarIdAndRenterEmailAndStatusIn(
            Long carId,
            String renterEmail,
            List<BookingStatus> statuses
    );
    List<Booking> findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
            Long carId,
            String renterEmail,
            List<BookingStatus> statuses
    );
}