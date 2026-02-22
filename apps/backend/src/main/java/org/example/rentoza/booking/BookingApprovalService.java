package org.example.rentoza.booking;

import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.cqrs.BookingDomainEvent;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.exception.ApprovalDecisionDeadlineExceededException;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.PaymentProvider;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Service responsible for host approval workflow operations.
 * Handles approve/decline actions and auto-expiry of pending requests.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookingApprovalService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final String DEADLINE_EXPIRED_REASON =
            "Request expired (approval deadline reached before host decision)";

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final ChatServiceClient chatServiceClient;
    private final InternalServiceJwtUtil internalServiceJwtUtil;
    private final ApplicationEventPublisher eventPublisher;
    private final org.example.rentoza.payment.BookingPaymentService bookingPaymentService;

    // P2 FIX: Default 24h to match dev/prod properties (was 48h, inconsistent)
    @Value("${app.booking.host-approval.approval-sla-hours:24}")
    private int approvalSlaHours;

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
    @Transactional(dontRollbackOn = ApprovalDecisionDeadlineExceededException.class)
    public BookingResponseDTO approveBooking(Long bookingId, Long ownerId) {
        log.debug("[ApprovalService] Starting approval for bookingId={}, ownerId={}", bookingId, ownerId);

        // Use findByIdWithRelations to fetch car, renter, and owner eagerly
        // This prevents LazyInitializationException when mapping to DTO in controller
        log.debug("[ApprovalService] Calling bookingRepository.findByIdWithRelationsForUpdate({})", bookingId);
        Booking booking = bookingRepository.findByIdWithRelationsForUpdate(bookingId)
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

        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        if (booking.getDecisionDeadlineAt() != null && !booking.getDecisionDeadlineAt().isAfter(now)) {
            // Hard correctness guarantee: enforce deadline at decision time (scheduler is only fallback).
            booking.setStatus(BookingStatus.EXPIRED_SYSTEM);
            booking.setDeclineReason(DEADLINE_EXPIRED_REASON);
            booking.setDeclinedAt(now);
            // P0 FIX: Release payment holds via gateway
            boolean releasedOk = releasePaymentHolds(booking);
            booking.setPaymentStatus(releasedOk ? "RELEASED" : "RELEASE_FAILED");
            bookingRepository.save(booking);

            publishEvent(new BookingDomainEvent.BookingExpired(
                booking.getId(),
                booking.getRenter().getId(),
                booking.getCar().getOwner().getId(),
                Instant.now()
            ));
            sendExpiryNotification(booking);

                throw new ApprovalDecisionDeadlineExceededException(
                    "Booking request expired at decision deadline and can no longer be approved."
                );
        }

        // Check time availability (race condition protection)
        boolean conflictsExist = bookingRepository.existsConflictingBookings(
                booking.getCar().getId(),
                booking.getStartTime(),
                booking.getEndTime()
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
        // Payment was already authorized at booking creation time (P0 fix).
        // Status remains "AUTHORIZED" (set during createBooking flow).
        // Payment capture happens at trip completion.

        try {
            Booking savedBooking = bookingRepository.save(booking);
            log.info("Booking approved: bookingId={}, ownerId={}, renterId={}, carId={}, approvedAt={}", 
                    bookingId, ownerId, booking.getRenter().getId(), booking.getCar().getId(), booking.getApprovedAt());

            // Publish CQRS event for read model sync
            publishEvent(new BookingDomainEvent.BookingApproved(
                    bookingId,
                    ownerId,
                    booking.getPaymentVerificationRef(),
                    Instant.now()
            ));

            log.info("💳 Payment was pre-authorized at booking creation. Ref: {}", 
                    booking.getPaymentVerificationRef());

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

        // P0 FIX: Release payment holds via gateway (not just status update)
        boolean released = releasePaymentHolds(booking);
        booking.setPaymentStatus(released ? "RELEASED" : "RELEASE_FAILED");

        try {
            Booking savedBooking = bookingRepository.save(booking);
            log.info("Booking declined: bookingId={}, ownerId={}, renterId={}, carId={}, declinedAt={}, reason={}", 
                    bookingId, ownerId, booking.getRenter().getId(), booking.getCar().getId(), 
                    booking.getDeclinedAt(), booking.getDeclineReason());

            // Publish CQRS event for read model sync
            publishEvent(new BookingDomainEvent.BookingDeclined(
                    bookingId,
                    ownerId,
                    booking.getDeclineReason(),
                    Instant.now()
            ));

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
     * Called by scheduled job every 15 minutes.
     * 
     * @return Number of bookings expired
     */
    @Transactional
    public int autoExpirePendingBookings() {
        log.debug("[ApprovalService] Running auto-expire job");
        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);

        List<Booking> expiredBookings = bookingRepository.findPendingBookingsBefore(now);

        if (expiredBookings.isEmpty()) {
            log.debug("[ApprovalService] No pending bookings to expire");
            return 0;
        }

        log.info("[ApprovalService] Found {} pending bookings to expire", expiredBookings.size());

        for (Booking booking : expiredBookings) {
            // Use EXPIRED_SYSTEM for auto-expiry (distinguishable from user-initiated expiry)
            booking.setStatus(BookingStatus.EXPIRED_SYSTEM);
            booking.setDeclineReason("Request expired (no response from host within deadline)");

            // P0 FIX: Release payment holds via gateway (not just status update)
            boolean released = releasePaymentHolds(booking);
            booking.setPaymentStatus(released ? "RELEASED" : "RELEASE_FAILED");

            bookingRepository.save(booking);

            log.info("Booking auto-expired: bookingId={}, renterId={}, carId={}, decisionDeadline={}", 
                    booking.getId(), booking.getRenter().getId(), booking.getCar().getId(), 
                    booking.getDecisionDeadlineAt());

            // Publish CQRS event for read model sync
            publishEvent(new BookingDomainEvent.BookingExpired(
                    booking.getId(),
                    booking.getRenter().getId(),
                    booking.getCar().getOwner().getId(),
                    Instant.now()
            ));

            // Notify guest
            sendExpiryNotification(booking);
        }

        return expiredBookings.size();
    }

    /**
     * Send pre-expiry reminders for pending approval requests.
     * Reminders are idempotent per booking+threshold.
     */
    @Transactional
    public int sendPendingApprovalReminders() {
        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        List<Booking> pendingBookings = bookingRepository.findPendingBookingsAfter(now);
        if (pendingBookings.isEmpty()) {
            return 0;
        }

        Set<Integer> thresholds = resolveReminderThresholds();
        java.util.List<Integer> orderedThresholds = thresholds.stream()
            .sorted(java.util.Comparator.reverseOrder())
            .toList();
        int sent = 0;

        for (Booking booking : pendingBookings) {
            LocalDateTime deadline = booking.getDecisionDeadlineAt();
            if (deadline == null) {
                continue;
            }

            long minutesToDeadline = java.time.Duration.between(now, deadline).toMinutes();
            if (minutesToDeadline <= 0) {
                continue;
            }

            for (int i = 0; i < orderedThresholds.size(); i++) {
                Integer thresholdHour = orderedThresholds.get(i);
                long upperBoundMinutes = thresholdHour * 60L;
                long lowerBoundMinutes = (i == orderedThresholds.size() - 1)
                        ? 0L
                        : orderedThresholds.get(i + 1) * 60L;

                // Send only when within this threshold band, e.g.:
                // 24h reminder -> (1h, 24h], 1h reminder -> (0h, 1h]
                if (minutesToDeadline > upperBoundMinutes || minutesToDeadline <= lowerBoundMinutes) {
                    continue;
                }

                String relatedEntityId = "booking-" + booking.getId() + "-approval-reminder-" + thresholdHour + "h";
                boolean reminderAlreadySent = !notificationRepository
                        .findByTypeAndRelatedEntityId(NotificationType.BOOKING_APPROVAL_REMINDER, relatedEntityId)
                        .isEmpty();

                if (reminderAlreadySent) {
                    continue;
                }

                sendApprovalReminderNotification(booking, thresholdHour, relatedEntityId);
                sent++;
            }
        }

        return sent;
    }

    private Set<Integer> resolveReminderThresholds() {
        Set<Integer> thresholds = new LinkedHashSet<>();

        if (approvalSlaHours >= 24) {
            thresholds.add(24);
        } else {
            // Equivalent pre-expiry reminder for shorter SLAs.
            thresholds.add(Math.max(2, approvalSlaHours / 2));
        }
        thresholds.add(1);

        return thresholds;
    }

    private void sendApprovalReminderNotification(Booking booking, int thresholdHour, String relatedEntityId) {
        String message = String.format(
                "Podsetnik: zahtev za rezervaciju %s %s ističe za oko %d h. Molimo odobrite ili odbijte na vreme.",
                booking.getCar().getBrand(),
                booking.getCar().getModel(),
                thresholdHour
        );

        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.BOOKING_APPROVAL_REMINDER)
                .message(message)
                .relatedEntityId(relatedEntityId)
                .build());

        log.info("Approval reminder sent: bookingId={}, threshold={}h, ownerId={}",
                booking.getId(), thresholdHour, booking.getCar().getOwner().getId());
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

    // ========== CQRS EVENT PUBLISHING ==========

    /**
     * Publish a booking domain event for CQRS read model synchronization.
     * Non-critical: failures are logged but don't block the approval workflow.
     */
    private void publishEvent(BookingDomainEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("[ApprovalService] Published event: {}", event.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("[ApprovalService] Failed to publish event {}: {}", 
                    event.getClass().getSimpleName(), e.getMessage());
        }
    }

    // ========== PAYMENT HOLD RELEASE ==========

    /**
     * Release all payment holds (booking + deposit) via the payment gateway.
     * 
     * <p><b>P0 Fix:</b> Previously, decline/expiry only updated the paymentStatus string
     * without actually calling the payment gateway to release the authorization holds.
     * This left money frozen on the guest's card indefinitely.
     * 
     * <p>Non-critical: failures are logged but don't block the decline/expiry workflow.
     * The payment gateway will auto-expire uncaptured authorizations after 7 days.
     */
    private boolean releasePaymentHolds(Booking booking) {
        boolean allSucceeded = true;
        try {
            // Release booking payment hold
            PaymentProvider.PaymentResult bookingResult = bookingPaymentService.releaseBookingPayment(booking.getId());
            if (bookingResult == null) {
                log.info("[ApprovalService] No booking payment hold to release for booking {} (null result)", booking.getId());
            } else if (bookingResult.isSuccess()) {
                log.info("[ApprovalService] Released booking payment hold for booking {}", booking.getId());
            } else {
                log.error("[ApprovalService] Booking payment release returned failure for booking {}: {}",
                        booking.getId(), bookingResult.getErrorMessage());
                allSucceeded = false;
            }
        } catch (Exception e) {
            log.error("[ApprovalService] Failed to release booking payment hold for booking {}: {}", 
                    booking.getId(), e.getMessage());
            allSucceeded = false;
        }

        try {
            // Release deposit hold
            String depositAuthId = booking.getDepositAuthorizationId();
            if (depositAuthId != null && !depositAuthId.isBlank()) {
                PaymentProvider.PaymentResult depositResult = bookingPaymentService.releaseDeposit(booking.getId(), depositAuthId);
                if (depositResult == null) {
                    log.info("[ApprovalService] No deposit hold to release for booking {} (null result)", booking.getId());
                } else if (depositResult.isSuccess()) {
                    log.info("[ApprovalService] Released deposit hold for booking {}", booking.getId());
                } else {
                    log.error("[ApprovalService] Deposit release returned failure for booking {}: {}",
                            booking.getId(), depositResult.getErrorMessage());
                    allSucceeded = false;
                }
            }
        } catch (Exception e) {
            log.error("[ApprovalService] Failed to release deposit hold for booking {}: {}", 
                    booking.getId(), e.getMessage());
            allSucceeded = false;
        }
        return allSucceeded;
    }
}
