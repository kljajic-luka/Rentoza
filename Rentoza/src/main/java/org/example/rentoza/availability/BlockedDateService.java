package org.example.rentoza.availability;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.availability.dto.BlockDateRequestDTO;
import org.example.rentoza.availability.dto.BlockedDateResponseDTO;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for managing car availability blocking.
 * Handles business logic for creating, retrieving, and deleting blocked date ranges.
 */
@Service
@Slf4j
public class BlockedDateService {

    private final BlockedDateRepository blockedDateRepository;
    private final CarRepository carRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final JwtUtil jwtUtil;

    public BlockedDateService(
            BlockedDateRepository blockedDateRepository,
            CarRepository carRepository,
            UserRepository userRepository,
            BookingRepository bookingRepository,
            JwtUtil jwtUtil
    ) {
        this.blockedDateRepository = blockedDateRepository;
        this.carRepository = carRepository;
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Get all blocked dates for a specific car.
     * Used by both owners (to manage their calendar) and renters (to check availability).
     */
    public List<BlockedDateResponseDTO> getBlockedDatesForCar(Long carId) {
        Car car = carRepository.findById(carId)
                .orElseThrow(() -> new ResourceNotFoundException("Car not found with ID: " + carId));

        List<BlockedDate> blockedDates = blockedDateRepository.findByCarIdOrderByStartDateAsc(carId);

        return blockedDates.stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    /**
     * Block a date range for a specific car.
     * Only the car owner can block dates.
     * Validates that:
     * - Start date is before end date
     * - No overlap with existing confirmed bookings
     * - No overlap with other blocked ranges
     */
    @Transactional
    public BlockedDateResponseDTO blockDateRange(BlockDateRequestDTO request, String authHeader) {
        // Extract user from JWT
        String token = authHeader.substring(7);
        String ownerEmail = jwtUtil.getEmailFromToken(token);

        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Verify car exists and user is the owner
        Car car = carRepository.findById(request.getCarId())
                .orElseThrow(() -> new ResourceNotFoundException("Car not found with ID: " + request.getCarId()));

        if (!car.getOwner().getId().equals(owner.getId())) {
            throw new IllegalStateException("You can only block dates for your own cars");
        }

        // Validate date range
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date must be after or equal to start date");
        }

        // Check for overlap with existing bookings
        boolean hasBookingConflict = bookingRepository.existsOverlappingBookings(
                car.getId(),
                request.getStartDate(),
                request.getEndDate()
        );

        if (hasBookingConflict) {
            throw new IllegalStateException("Cannot block dates that overlap with existing bookings");
        }

        // Check for overlap with other blocked ranges
        boolean hasBlockConflict = blockedDateRepository.existsOverlappingBlockedDates(
                car.getId(),
                request.getStartDate(),
                request.getEndDate()
        );

        if (hasBlockConflict) {
            throw new IllegalStateException("Cannot block dates that are already blocked");
        }

        // Create and save blocked date range
        BlockedDate blockedDate = BlockedDate.builder()
                .car(car)
                .owner(owner)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .build();

        BlockedDate saved = blockedDateRepository.save(blockedDate);

        log.info("Owner {} blocked dates {} to {} for car {}",
                owner.getId(), request.getStartDate(), request.getEndDate(), car.getId());

        return toResponseDTO(saved);
    }

    /**
     * Unblock (delete) a specific blocked date range.
     * Only the car owner can unblock their own dates.
     */
    @Transactional
    public void unblockDateRange(Long blockId, String authHeader) {
        // Extract user from JWT
        String token = authHeader.substring(7);
        String ownerEmail = jwtUtil.getEmailFromToken(token);

        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Verify blocked date exists and user is the owner
        BlockedDate blockedDate = blockedDateRepository.findById(blockId)
                .orElseThrow(() -> new ResourceNotFoundException("Blocked date not found with ID: " + blockId));

        if (!blockedDate.getOwner().getId().equals(owner.getId())) {
            throw new IllegalStateException("You can only unblock your own blocked dates");
        }

        blockedDateRepository.delete(blockedDate);

        log.info("Owner {} unblocked date range {} for car {}",
                owner.getId(), blockId, blockedDate.getCar().getId());
    }

    /**
     * Check if a specific date range is available (not blocked and not booked).
     * Used during booking validation.
     */
    public boolean isDateRangeAvailable(Long carId, LocalDate startDate, LocalDate endDate) {
        boolean hasBookings = bookingRepository.existsOverlappingBookings(carId, startDate, endDate);
        boolean hasBlocks = blockedDateRepository.existsOverlappingBlockedDates(carId, startDate, endDate);

        return !hasBookings && !hasBlocks;
    }

    /**
     * Convert entity to DTO for API responses.
     */
    private BlockedDateResponseDTO toResponseDTO(BlockedDate blockedDate) {
        return BlockedDateResponseDTO.builder()
                .id(blockedDate.getId())
                .carId(blockedDate.getCar().getId())
                .startDate(blockedDate.getStartDate())
                .endDate(blockedDate.getEndDate())
                .createdAt(blockedDate.getCreatedAt())
                .build();
    }
}
