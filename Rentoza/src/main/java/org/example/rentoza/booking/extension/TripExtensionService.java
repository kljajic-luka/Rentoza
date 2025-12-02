package org.example.rentoza.booking.extension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.extension.dto.TripExtensionDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Service for managing trip extension requests.
 */
@Service
@Slf4j
public class TripExtensionService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final TripExtensionRepository extensionRepository;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;

    // Metrics
    private final Counter extensionRequestedCounter;
    private final Counter extensionApprovedCounter;
    private final Counter extensionDeclinedCounter;

    @Value("${app.trip-extension.response-hours:24}")
    private int responseHours;

    public TripExtensionService(
            TripExtensionRepository extensionRepository,
            BookingRepository bookingRepository,
            NotificationService notificationService,
            MeterRegistry meterRegistry) {
        this.extensionRepository = extensionRepository;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;

        this.extensionRequestedCounter = Counter.builder("trip_extension.requested")
                .description("Trip extensions requested")
                .register(meterRegistry);

        this.extensionApprovedCounter = Counter.builder("trip_extension.approved")
                .description("Trip extensions approved")
                .register(meterRegistry);

        this.extensionDeclinedCounter = Counter.builder("trip_extension.declined")
                .description("Trip extensions declined")
                .register(meterRegistry);
    }

    // ========== REQUEST EXTENSION ==========

    /**
     * Request a trip extension.
     */
    @Transactional
    public TripExtensionDTO requestExtension(
            Long bookingId,
            LocalDate newEndDate,
            String reason,
            Long guestUserId) {
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate guest
        if (!booking.getRenter().getId().equals(guestUserId)) {
            throw new AccessDeniedException("Samo gost može zatražiti produženje");
        }
        
        // Validate booking status - must be IN_TRIP
        if (booking.getStatus() != BookingStatus.IN_TRIP) {
            throw new IllegalStateException("Produženje je moguće samo tokom aktivnog putovanja");
        }
        
        // Check for existing pending extension
        if (extensionRepository.hasPendingExtension(bookingId)) {
            throw new IllegalStateException("Već postoji zahtev za produženje na čekanju");
        }
        
        // Validate new end date
        LocalDate currentEndDate = booking.getEndDate();
        if (!newEndDate.isAfter(currentEndDate)) {
            throw new IllegalArgumentException("Novi datum mora biti posle trenutnog datuma završetka");
        }
        
        int additionalDays = (int) ChronoUnit.DAYS.between(currentEndDate, newEndDate);
        
        // Check availability (simplified - in production would check calendar)
        // TODO: Integrate with availability service
        
        // Calculate cost
        BigDecimal dailyRate = booking.getSnapshotDailyRate() != null 
                ? booking.getSnapshotDailyRate() 
                : booking.getTotalPrice().divide(BigDecimal.valueOf(
                    ChronoUnit.DAYS.between(booking.getStartDate(), booking.getEndDate())), 2, java.math.RoundingMode.HALF_UP);
        
        BigDecimal additionalCost = dailyRate.multiply(BigDecimal.valueOf(additionalDays));

        TripExtension extension = TripExtension.builder()
                .booking(booking)
                .originalEndDate(currentEndDate)
                .requestedEndDate(newEndDate)
                .additionalDays(additionalDays)
                .reason(reason)
                .dailyRate(dailyRate)
                .additionalCost(additionalCost)
                .status(TripExtensionStatus.PENDING)
                .responseDeadline(Instant.now().plus(Duration.ofHours(responseHours)))
                .build();

        extension = extensionRepository.save(extension);

        extensionRequestedCounter.increment();
        log.info("[TripExtension] Extension requested for booking {}: {} days, {} RSD", 
            bookingId, additionalDays, additionalCost);

        // Notify host
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.BOOKING_REQUEST_RECEIVED)
                .message(String.format("Gost traži produženje za %d dana (%.0f RSD). Imate %d sati da odgovorite.",
                        additionalDays, additionalCost.doubleValue(), responseHours))
                .relatedEntityId(String.valueOf(bookingId))
                .build());

        return mapToDTO(extension);
    }

    // ========== HOST RESPONSE ==========

    /**
     * Host approves extension.
     */
    @Transactional
    public TripExtensionDTO approveExtension(Long extensionId, String response, Long hostUserId) {
        TripExtension extension = getExtensionForHost(extensionId, hostUserId);

        if (extension.getStatus() != TripExtensionStatus.PENDING) {
            throw new IllegalStateException("Ovaj zahtev više nije na čekanju");
        }

        extension.approve(response);

        // Update booking end time (keep same time of day, extend to new date)
        Booking booking = extension.getBooking();
        java.time.LocalTime endTimeOfDay = booking.getEndTime().toLocalTime();
        booking.setEndTime(extension.getRequestedEndDate().atTime(endTimeOfDay));
        booking.setTotalPrice(booking.getTotalPrice().add(extension.getAdditionalCost()));
        bookingRepository.save(booking);

        extension = extensionRepository.save(extension);

        extensionApprovedCounter.increment();
        log.info("[TripExtension] Extension {} approved for booking {}", extensionId, booking.getId());

        // Notify guest
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.BOOKING_APPROVED)
                .message(String.format("Vaš zahtev za produženje je odobren! Novi datum završetka: %s",
                        extension.getRequestedEndDate()))
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());

        return mapToDTO(extension);
    }

    /**
     * Host declines extension.
     */
    @Transactional
    public TripExtensionDTO declineExtension(Long extensionId, String response, Long hostUserId) {
        TripExtension extension = getExtensionForHost(extensionId, hostUserId);

        if (extension.getStatus() != TripExtensionStatus.PENDING) {
            throw new IllegalStateException("Ovaj zahtev više nije na čekanju");
        }

        extension.decline(response);
        extension = extensionRepository.save(extension);

        extensionDeclinedCounter.increment();
        log.info("[TripExtension] Extension {} declined for booking {}", extensionId, extension.getBooking().getId());

        // Notify guest
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(extension.getBooking().getRenter().getId())
                .type(NotificationType.BOOKING_DECLINED)
                .message("Vaš zahtev za produženje je odbijen" + 
                        (response != null ? ": " + response : ""))
                .relatedEntityId(String.valueOf(extension.getBooking().getId()))
                .build());

        return mapToDTO(extension);
    }

    // ========== GUEST ACTIONS ==========

    /**
     * Guest cancels pending extension request.
     */
    @Transactional
    public TripExtensionDTO cancelExtension(Long extensionId, Long guestUserId) {
        TripExtension extension = extensionRepository.findById(extensionId)
                .orElseThrow(() -> new ResourceNotFoundException("Zahtev za produženje nije pronađen"));

        if (!extension.getBooking().getRenter().getId().equals(guestUserId)) {
            throw new AccessDeniedException("Nemate pristup ovom zahtevu");
        }

        if (extension.getStatus() != TripExtensionStatus.PENDING) {
            throw new IllegalStateException("Ovaj zahtev više nije na čekanju");
        }

        extension.cancel();
        extension = extensionRepository.save(extension);

        log.info("[TripExtension] Extension {} cancelled by guest", extensionId);

        return mapToDTO(extension);
    }

    // ========== SCHEDULER ==========

    /**
     * Expire pending extensions past deadline.
     * Called by scheduler every hour.
     */
    @Transactional
    public int expirePendingExtensions() {
        List<TripExtension> expired = extensionRepository.findExpiredPending(Instant.now());

        for (TripExtension extension : expired) {
            extension.expire();
            extensionRepository.save(extension);

            log.info("[TripExtension] Extension {} expired", extension.getId());

            // Notify guest
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(extension.getBooking().getRenter().getId())
                    .type(NotificationType.BOOKING_EXPIRED)
                    .message("Vaš zahtev za produženje je istekao jer domaćin nije odgovorio")
                    .relatedEntityId(String.valueOf(extension.getBooking().getId()))
                    .build());
        }

        return expired.size();
    }

    // ========== RETRIEVAL ==========

    @Transactional(readOnly = true)
    public TripExtensionDTO getPendingExtension(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate access
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        boolean isGuest = booking.getRenter().getId().equals(userId);
        
        if (!isHost && !isGuest) {
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }

        return extensionRepository.findPendingByBookingId(bookingId)
                .map(this::mapToDTO)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<TripExtensionDTO> getExtensionsForBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate access
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        boolean isGuest = booking.getRenter().getId().equals(userId);
        
        if (!isHost && !isGuest) {
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }

        return extensionRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TripExtensionDTO> getExtensionsForHost(Long hostId) {
        return extensionRepository.findByHostId(hostId).stream()
                .map(this::mapToDTO)
                .toList();
    }

    // ========== HELPERS ==========

    private TripExtension getExtensionForHost(Long extensionId, Long hostUserId) {
        TripExtension extension = extensionRepository.findById(extensionId)
                .orElseThrow(() -> new ResourceNotFoundException("Zahtev za produženje nije pronađen"));

        if (!extension.getBooking().getCar().getOwner().getId().equals(hostUserId)) {
            throw new AccessDeniedException("Nemate pristup ovom zahtevu");
        }

        return extension;
    }

    private TripExtensionDTO mapToDTO(TripExtension extension) {
        String statusDisplay = switch (extension.getStatus()) {
            case PENDING -> "Na čekanju";
            case APPROVED -> "Odobreno";
            case DECLINED -> "Odbijeno";
            case EXPIRED -> "Isteklo";
            case CANCELLED -> "Otkazano";
        };

        return TripExtensionDTO.builder()
                .id(extension.getId())
                .bookingId(extension.getBooking().getId())
                .originalEndDate(extension.getOriginalEndDate())
                .requestedEndDate(extension.getRequestedEndDate())
                .additionalDays(extension.getAdditionalDays())
                .reason(extension.getReason())
                .dailyRate(extension.getDailyRate())
                .additionalCost(extension.getAdditionalCost())
                .status(extension.getStatus())
                .statusDisplay(statusDisplay)
                .responseDeadline(toLocalDateTime(extension.getResponseDeadline()))
                .hostResponse(extension.getHostResponse())
                .respondedAt(toLocalDateTime(extension.getRespondedAt()))
                .createdAt(toLocalDateTime(extension.getCreatedAt()))
                .vehicleName(extension.getBooking().getCar().getBrand() + " " + extension.getBooking().getCar().getModel())
                .vehicleImageUrl(extension.getBooking().getCar().getImageUrl())
                .guestId(extension.getBooking().getRenter().getId())
                .guestName(extension.getBooking().getRenter().getFirstName() + " " + extension.getBooking().getRenter().getLastName())
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }
}

