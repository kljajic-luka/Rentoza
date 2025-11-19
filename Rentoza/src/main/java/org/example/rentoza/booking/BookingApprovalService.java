package org.example.rentoza.booking;

import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service responsible for host approval workflow operations.
 * Handles approve/decline actions and auto-expiry of pending requests.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookingApprovalService {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ChatServiceClient chatServiceClient;
    private final InternalServiceJwtUtil internalServiceJwtUtil;

    /**
     * Approve a pending booking request.
     * Transitions booking from PENDING_APPROVAL to ACTIVE.
     * 
     * @param bookingId ID of the booking to approve
     * @param ownerId ID of the car owner (must match booking's car owner)
     * @return Approved booking DTO
     * @throws ResourceNotFoundException if booking not found
     * @throws IllegalStateException if booking is not in PENDING_APPROVAL status
     * @throws AccessDeniedException if user is not the car owner
     * @throws BookingConflictException if dates are no longer available
     * @throws OptimisticLockingFailureException if booking was modified concurrently
     */
    @Transactional
    public BookingResponseDTO approveBooking(Long bookingId, Long ownerId) {
        log.debug("[ApprovalService] Starting approval for bookingId={}, ownerId={}", bookingId, ownerId);

        // Use findByIdWithRelations to fetch car, renter, and owner eagerly
        // This prevents LazyInitializationException when mapping to DTO in controller
        log.debug("[ApprovalService] Calling bookingRepository.findByIdWithRelations({})", bookingId);
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        // Verify status
        if (booking.getStatus() != BookingStatus.PENDING_APPROVAL) {
            log.warn("[ApprovalService] Booking {} is not pending approval (status={})", bookingId, booking.getStatus());
            throw new IllegalStateException("Booking is not pending approval. Current status: " + booking.getStatus());
        }

        // Verify ownership (defense in depth - controller already has @PreAuthorize)
        if (!booking.getCar().getOwner().getId().equals(ownerId)) {
            log.warn("[ApprovalService] User {} attempted to approve booking {} owned by user {}", 
                    ownerId, bookingId, booking.getCar().getOwner().getId());
            throw new AccessDeniedException("User is not the car owner");
        }

        // Check date availability (race condition protection)
        boolean conflictsExist = bookingRepository.existsConflictingBookings(
                booking.getCar().getId(),
                booking.getStartDate(),
                booking.getEndDate()
        );

        if (conflictsExist) {
            log.warn("[ApprovalService] Dates no longer available for booking {}", bookingId);
            throw new BookingConflictException("Dates are no longer available for this car");
        }

        // Fetch owner user entity for approval tracking
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + ownerId));

        // Update booking status and audit fields
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setApprovedBy(owner);
        booking.setApprovedAt(LocalDateTime.now());
        booking.setPaymentStatus("AUTHORIZED"); // Simulated payment authorization

        // Generate simulated payment reference
        booking.setPaymentVerificationRef("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        try {
            Booking savedBooking = bookingRepository.save(booking);
            log.info("Booking approved: bookingId={}, ownerId={}, renterId={}, carId={}, approvedAt={}", 
                    bookingId, ownerId, booking.getRenter().getId(), booking.getCar().getId(), booking.getApprovedAt());

            // Simulate payment authorization (placeholder)
            log.debug("💳 [SIMULATED] Payment authorized for booking {} with ref {}", 
                    bookingId, booking.getPaymentVerificationRef());

            // Send notification to guest (renter)
            sendApprovalNotification(savedBooking);

            // Create chat conversation (delayed until approval)
            createChatConversation(savedBooking);

            // Map to DTO inside transaction to ensure all lazy fields are initialized
            return new BookingResponseDTO(savedBooking);

        } catch (OptimisticLockingFailureException e) {
            log.error("[ApprovalService] Optimistic locking failure for booking {}", bookingId, e);
            throw new BookingConflictException("Booking was already modified by another user. Please try again.");
        }
    }

    /**
     * Decline a pending booking request.
     * Transitions booking from PENDING_APPROVAL to DECLINED.
     * 
     * @param bookingId ID of the booking to decline
     * @param ownerId ID of the car owner (must match booking's car owner)
     * @param reason Optional reason for decline
     * @return Declined booking DTO
     * @throws ResourceNotFoundException if booking not found
     * @throws IllegalStateException if booking is not in PENDING_APPROVAL status
     * @throws AccessDeniedException if user is not the car owner
     * @throws OptimisticLockingFailureException if booking was modified concurrently
     */
    @Transactional
    public BookingResponseDTO declineBooking(Long bookingId, Long ownerId, String reason) {
        log.debug("[ApprovalService] Starting decline for bookingId={}, ownerId={}, reason={}", 
                bookingId, ownerId, reason);

        // Use findByIdWithRelations to fetch car, renter, and owner eagerly
        log.debug("[ApprovalService] Calling bookingRepository.findByIdWithRelations({})", bookingId);
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        // Verify status
        if (booking.getStatus() != BookingStatus.PENDING_APPROVAL) {
            log.warn("[ApprovalService] Booking {} is not pending approval (status={})", bookingId, booking.getStatus());
            throw new IllegalStateException("Booking is not pending approval. Current status: " + booking.getStatus());
        }

        // Verify ownership (defense in depth)
        if (!booking.getCar().getOwner().getId().equals(ownerId)) {
            log.warn("[ApprovalService] User {} attempted to decline booking {} owned by user {}", 
                    ownerId, bookingId, booking.getCar().getOwner().getId());
            throw new AccessDeniedException("User is not the car owner");
        }

        // Fetch owner user entity for decline tracking
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + ownerId));

        // Update booking status and audit fields
        booking.setStatus(BookingStatus.DECLINED);
        booking.setDeclinedBy(owner);
        booking.setDeclinedAt(LocalDateTime.now());
        booking.setDeclineReason(reason != null && !reason.isBlank() ? reason : "No reason provided");
        booking.setPaymentStatus("RELEASED"); // Release simulated payment hold

        try {
            Booking savedBooking = bookingRepository.save(booking);
            log.info("Booking declined: bookingId={}, ownerId={}, renterId={}, carId={}, declinedAt={}, reason={}", 
                    bookingId, ownerId, booking.getRenter().getId(), booking.getCar().getId(), 
                    booking.getDeclinedAt(), booking.getDeclineReason());

            // Send notification to guest (renter)
            sendDeclineNotification(savedBooking);

            // Map to DTO inside transaction to ensure all lazy fields are initialized
            return new BookingResponseDTO(savedBooking);

        } catch (OptimisticLockingFailureException e) {
            log.error("[ApprovalService] Optimistic locking failure for booking {}", bookingId, e);
            throw new BookingConflictException("Booking was already modified by another user. Please try again.");
        }
    }

    /**
     * Get all pending approval requests for an owner's cars.
     * 
     * @param ownerId ID of the car owner
     * @return List of pending bookings
     */
    public List<Booking> getPendingRequests(Long ownerId) {
        log.debug("[ApprovalService] Fetching pending requests for ownerId={}", ownerId);
        return bookingRepository.findPendingBookingsForOwner(ownerId);
    }

    /**
     * Auto-expire pending bookings that have passed their decision deadline.
     * Called by scheduled job.
     * 
     * @return Number of bookings expired
     */
    @Transactional
    public int autoExpirePendingBookings() {
        log.debug("[ApprovalService] Running auto-expire job");
        LocalDateTime now = LocalDateTime.now();

        List<Booking> expiredBookings = bookingRepository.findPendingBookingsBefore(now);

        if (expiredBookings.isEmpty()) {
            log.debug("[ApprovalService] No pending bookings to expire");
            return 0;
        }

        log.info("[ApprovalService] Found {} pending bookings to expire", expiredBookings.size());

        for (Booking booking : expiredBookings) {
            booking.setStatus(BookingStatus.EXPIRED);
            booking.setDeclineReason("Request expired (no response from host within deadline)");
            booking.setPaymentStatus("RELEASED");

            bookingRepository.save(booking);

            log.info("Booking expired: bookingId={}, renterId={}, carId={}, decisionDeadline={}", 
                    booking.getId(), booking.getRenter().getId(), booking.getCar().getId(), 
                    booking.getDecisionDeadlineAt());

            // Notify guest
            sendExpiryNotification(booking);
        }

        return expiredBookings.size();
    }

    // ========== PRIVATE HELPER METHODS ==========

    /**
     * Send approval notification to guest (renter).
     */
    private void sendApprovalNotification(Booking booking) {
        String message = String.format(
                "Your booking request for %s %s %s has been approved! Trip dates: %s to %s",
                booking.getCar().getBrand(),
                booking.getCar().getModel(),
                booking.getCar().getYear(),
                booking.getStartDate(),
                booking.getEndDate()
        );

        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.BOOKING_APPROVED)
                .message(message)
                .relatedEntityId("booking-" + booking.getId())
                .build());

        log.debug("[ApprovalService] Sent approval notification to renterId={}", booking.getRenter().getId());
    }

    /**
     * Send decline notification to guest (renter).
     */
    private void sendDeclineNotification(Booking booking) {
        String message = String.format(
                "Your booking request for %s %s %s was declined. Reason: %s",
                booking.getCar().getBrand(),
                booking.getCar().getModel(),
                booking.getCar().getYear(),
                booking.getDeclineReason()
        );

        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.BOOKING_DECLINED)
                .message(message)
                .relatedEntityId("booking-" + booking.getId())
                .build());

        log.debug("[ApprovalService] Sent decline notification to renterId={}", booking.getRenter().getId());
    }

    /**
     * Send expiry notification to guest (renter).
     */
    private void sendExpiryNotification(Booking booking) {
        String message = String.format(
                "Your booking request for %s %s %s expired due to host inactivity.",
                booking.getCar().getBrand(),
                booking.getCar().getModel(),
                booking.getCar().getYear()
        );

        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.BOOKING_EXPIRED)
                .message(message)
                .relatedEntityId("booking-" + booking.getId())
                .build());

        log.debug("[ApprovalService] Sent expiry notification to renterId={}", booking.getRenter().getId());
    }

    /**
     * Create chat conversation for approved booking.
     * Conversation is only created when booking becomes ACTIVE.
     * Uses InternalServiceJwtUtil to generate INTERNAL_SERVICE token for chat microservice.
     */
    private void createChatConversation(Booking booking) {
        try {
            // Generate internal service token for chat service communication
            String internalToken = internalServiceJwtUtil.generateServiceToken("chat-service").trim();

            // Pass raw token - ChatServiceClient handles the Bearer prefix
            chatServiceClient.createConversationAsync(
                    booking.getId().toString(),
                    booking.getRenter().getId().toString(),
                    booking.getCar().getOwner().getId().toString(),
                    internalToken
            );

            log.debug("[ApprovalService] Triggered chat conversation creation for bookingId={}", booking.getId());

        } catch (Exception e) {
            log.error("[ApprovalService] Failed to create chat conversation for booking {}", booking.getId(), e);
            // Don't fail approval if chat creation fails - log and continue
        }
    }
}
