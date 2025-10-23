package org.example.rentoza.booking;

import org.example.rentoza.car.CarRepository;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BookingService {

    private final BookingRepository repo;
    private final CarRepository carRepo;

    public BookingService(BookingRepository repo, CarRepository carRepo) {
        this.repo = repo;
        this.carRepo = carRepo;
    }

    public Booking createBooking(Booking booking) {
        var car = carRepo.findById(booking.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        // check overlapping bookings
        var conflicts = repo.findByCarIdAndEndDateAfterAndStartDateBefore(
                booking.getCarId(),
                booking.getStartDate(),
                booking.getEndDate()
        );
        if (!conflicts.isEmpty()) {
            throw new RuntimeException("Car already booked for these dates");
        }

        // calculate total price
        long days = ChronoUnit.DAYS.between(booking.getStartDate(), booking.getEndDate());
        if (days <= 0) throw new RuntimeException("Invalid booking dates");

        booking.setTotalPrice(days * car.getPricePerDay());
        return repo.save(booking);
    }

    public List<Booking> getBookingsByUser(String email) {
        return repo.findByRenterEmail(email);
    }

    public List<Booking> getBookingsForCar(Long carId) {
        return repo.findByCarId(carId);
    }

    public Booking cancelBooking(Long id) {
        var booking = repo.findById(id).orElseThrow(() -> new RuntimeException("Booking not found"));
        booking.setStatus(BookingStatus.CANCELLED);
        return repo.save(booking);
    }
}
