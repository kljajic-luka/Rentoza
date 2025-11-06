package org.example.rentoza.booking;

import jakarta.transaction.Transactional;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.booking.dto.UserBookingResponseDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private final BookingRepository repo;
    private final CarRepository carRepo;
    private final UserRepository userRepo;
    private final ReviewRepository reviewRepo;
    private final JwtUtil jwtUtil;

    public BookingService(BookingRepository repo, CarRepository carRepo, UserRepository userRepo, ReviewRepository reviewRepo, JwtUtil jwtUtil) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.userRepo = userRepo;
        this.reviewRepo = reviewRepo;
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

    public Booking getBookingById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
    }

    public List<BookingResponseDTO> getBookingsForCar(Long carId) {
        var bookings = repo.findByCarId(carId);

        return bookings.stream()
                .map(BookingResponseDTO::new)
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

    @Transactional
    public List<UserBookingResponseDTO> getMyBookings(String authHeader) {
        String token = authHeader.substring(7);
        String userEmail = jwtUtil.getEmailFromToken(token);

        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Fetch all bookings with car details using optimized query (no N+1)
        List<Booking> bookings = repo.findByRenterIdWithDetails(user.getId());

        if (bookings.isEmpty()) {
            return List.of();
        }

        // Fetch all reviews for these bookings in one query
        List<Long> bookingIds = bookings.stream()
                .map(Booking::getId)
                .collect(Collectors.toList());

        Map<Long, Review> reviewsByBookingId = reviewRepo
                .findByBookingIdInAndDirection(bookingIds, ReviewDirection.FROM_USER)
                .stream()
                .collect(Collectors.toMap(
                        r -> r.getBooking().getId(),
                        r -> r,
                        (existing, replacement) -> existing // Keep first if duplicate
                ));

        // Map to DTOs
        return bookings.stream()
                .map(booking -> {
                    Car car = booking.getCar();
                    Review review = reviewsByBookingId.get(booking.getId());

                    return new UserBookingResponseDTO(
                            booking.getId(),
                            car.getId(),
                            car.getBrand(),
                            car.getModel(),
                            car.getYear(),
                            car.getImageUrl(),
                            car.getLocation(),
                            car.getPricePerDay(),
                            booking.getStartDate(),
                            booking.getEndDate(),
                            booking.getTotalPrice(),
                            booking.getStatus().name(),
                            review != null,
                            review != null ? review.getRating() : null,
                            review != null ? review.getComment() : null
                    );
                })
                .collect(Collectors.toList());
    }
}