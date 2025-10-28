package org.example.rentoza.booking;

import jakarta.transaction.Transactional;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BookingService {

    private final BookingRepository repo;
    private final CarRepository carRepo;
    private final UserRepository userRepo;

    private final JwtUtil jwtUtil;

    public BookingService(BookingRepository repo, CarRepository carRepo, UserRepository userRepo,JwtUtil jwtUtil) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
    }

    public Booking createBooking(BookingRequestDTO dto, String authHeader) {
        String token = authHeader.substring(7);
        String renterEmail = jwtUtil.getEmailFromToken(token);

        User renter = userRepo.findByEmail(renterEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Car car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStartDate(dto.getStartDate());
        booking.setEndDate(dto.getEndDate());
        booking.setStatus(BookingStatus.ACTIVE);

        long days = ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate());
        booking.setTotalPrice(days * car.getPricePerDay());

        return repo.save(booking);
    }

    public List<Booking> getBookingsByUser(String email) {
        return repo.findByRenterEmailIgnoreCase(email);
    }

    public List<BookingResponseDTO> getBookingsForCar(Long carId) {
        var bookings = repo.findByCarId(carId);

        return bookings.stream()
                .map(b -> new BookingResponseDTO(
                        b.getId(),
                        b.getCar().getId(),
                        b.getRenter().getEmail(),
                        b.getStartDate(),
                        b.getEndDate(),
                        b.getTotalPrice(),
                        b.getStatus().name()
                ))
                .toList();
    }

    @Transactional
    public Booking cancelBooking(Long id) {
        var booking = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        booking.setStatus(BookingStatus.CANCELLED);

        // Force initialize before leaving the transaction
        Hibernate.initialize(booking.getCar());
        Hibernate.initialize(booking.getRenter());

        return booking;
    }
}