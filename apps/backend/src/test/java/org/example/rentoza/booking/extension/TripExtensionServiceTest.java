package org.example.rentoza.booking.extension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.extension.dto.TripExtensionDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentProvider;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TripExtensionService}.
 *
 * <h2>Audit Section 15 Coverage</h2>
 * <p>Validates the complete trip extension lifecycle:</p>
 * <ul>
 *   <li>Guest requests extension while trip is in progress</li>
 *   <li>Host approves/declines extension within response window</li>
 *   <li>Guest cancels pending extension</li>
 *   <li>Cost calculation from snapshot daily rate (and fallback)</li>
 *   <li>Access control enforcement (guest-only request, host-only approve/decline)</li>
 *   <li>State validation (only IN_TRIP bookings, only PENDING extensions)</li>
 *   <li>Overlap detection preventing conflicts with next booking</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TripExtensionServiceTest {

    private static final Long GUEST_USER_ID = 10L;
    private static final Long HOST_USER_ID = 20L;
    private static final Long BOOKING_ID = 1L;
    private static final Long EXTENSION_ID = 50L;

    @Mock
    private TripExtensionRepository extensionRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private NotificationService notificationService;

        @Mock
        private BookingPaymentService bookingPaymentService;

    private TripExtensionService service;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new TripExtensionService(
                extensionRepository,
                bookingRepository,
                notificationService,
                bookingPaymentService,
                meterRegistry
        );
        lenient().when(bookingPaymentService.findExtensionPaymentAction(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "responseHours", 24);
    }

    // ========== REQUEST EXTENSION ==========

    @Nested
    @DisplayName("requestExtension")
    class RequestExtension {

        @Test
        @DisplayName("1. request succeeds for IN_TRIP booking with valid dates")
        void request_succeeds_for_in_trip_booking() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            LocalDate newEndDate = LocalDate.of(2025, 3, 8);

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));
            when(extensionRepository.hasPendingExtension(BOOKING_ID))
                    .thenReturn(false);
            when(bookingRepository.existsOverlappingBookingsWithLock(
                    eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(false);
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> {
                        TripExtension ext = invocation.getArgument(0);
                        ext.setId(EXTENSION_ID);
                        return ext;
                    });

            TripExtensionDTO result = service.requestExtension(
                    BOOKING_ID, newEndDate, "Need extra days", GUEST_USER_ID);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.PENDING);
            assertThat(result.getBookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.getOriginalEndDate()).isEqualTo(LocalDate.of(2025, 3, 5));
            assertThat(result.getRequestedEndDate()).isEqualTo(newEndDate);
            assertThat(result.getAdditionalDays()).isEqualTo(3);
            assertThat(result.getReason()).isEqualTo("Need extra days");
            assertThat(result.getStatusDisplay()).isEqualTo("Na \u010dekanju");

            verify(extensionRepository).save(any(TripExtension.class));
            verify(notificationService).createNotification(any(CreateNotificationRequestDTO.class));
        }

        @Test
        @DisplayName("2. request rejected if booking is not IN_TRIP")
        void request_rejected_if_not_in_trip() {
            Booking booking = createTestBooking(BookingStatus.ACTIVE);
            LocalDate newEndDate = LocalDate.of(2025, 3, 8);

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));

            assertThatThrownBy(() ->
                    service.requestExtension(BOOKING_ID, newEndDate, "reason", GUEST_USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("aktivnog putovanja");

            verify(extensionRepository, never()).save(any());
        }

        @Test
        @DisplayName("3. request rejected if caller is not the guest")
        void request_rejected_if_not_guest() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            Long wrongUserId = 999L;
            LocalDate newEndDate = LocalDate.of(2025, 3, 8);

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));

            assertThatThrownBy(() ->
                    service.requestExtension(BOOKING_ID, newEndDate, "reason", wrongUserId))
                    .isInstanceOf(AccessDeniedException.class);

            verify(extensionRepository, never()).save(any());
        }

        @Test
        @DisplayName("4. request rejected if a pending extension already exists")
        void request_rejected_if_pending_extension_exists() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            LocalDate newEndDate = LocalDate.of(2025, 3, 8);

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));
            when(extensionRepository.hasPendingExtension(BOOKING_ID))
                    .thenReturn(true);

            assertThatThrownBy(() ->
                    service.requestExtension(BOOKING_ID, newEndDate, "reason", GUEST_USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("na \u010dekanju");

            verify(extensionRepository, never()).save(any());
        }

        @Test
        @DisplayName("5. request rejected if new end date is not after current end date")
        void request_rejected_if_new_date_not_after_current() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            // Current end date is 2025-03-05; request same or earlier date
            LocalDate sameEndDate = LocalDate.of(2025, 3, 5);

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));
            when(extensionRepository.hasPendingExtension(BOOKING_ID))
                    .thenReturn(false);

            assertThatThrownBy(() ->
                    service.requestExtension(BOOKING_ID, sameEndDate, "reason", GUEST_USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("posle trenutnog datuma");

            verify(extensionRepository, never()).save(any());
        }

        @Test
        @DisplayName("6. request rejected if overlap with next booking is detected")
        void request_rejected_if_overlap_detected() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            LocalDate newEndDate = LocalDate.of(2025, 3, 8);

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));
            when(extensionRepository.hasPendingExtension(BOOKING_ID))
                    .thenReturn(false);
            when(bookingRepository.existsOverlappingBookingsWithLock(
                    eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(true);

            assertThatThrownBy(() ->
                    service.requestExtension(BOOKING_ID, newEndDate, "reason", GUEST_USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("druga rezervacija");

            verify(extensionRepository, never()).save(any());
        }

        @Test
        @DisplayName("7. cost calculated from snapshotDailyRate: 5000 * 3 days = 15000")
        void request_calculates_cost_from_snapshot_daily_rate() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            booking.setSnapshotDailyRate(new BigDecimal("5000.00"));
            LocalDate newEndDate = LocalDate.of(2025, 3, 8); // 3 extra days

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));
            when(extensionRepository.hasPendingExtension(BOOKING_ID))
                    .thenReturn(false);
            when(bookingRepository.existsOverlappingBookingsWithLock(
                    eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(false);
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> {
                        TripExtension ext = invocation.getArgument(0);
                        ext.setId(EXTENSION_ID);
                        return ext;
                    });

            TripExtensionDTO result = service.requestExtension(
                    BOOKING_ID, newEndDate, "reason", GUEST_USER_ID);

            assertThat(result.getDailyRate()).isEqualByComparingTo(new BigDecimal("5000.00"));
            assertThat(result.getAdditionalCost()).isEqualByComparingTo(new BigDecimal("15000.00"));
            assertThat(result.getAdditionalDays()).isEqualTo(3);
        }

        @Test
        @DisplayName("8. cost derived from totalPrice / tripDays when snapshotDailyRate is null")
        void request_calculates_cost_without_snapshot() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            booking.setSnapshotDailyRate(null);
            // totalPrice = 20000, trip days = 4 (Mar 1..5) => dailyRate = 5000
            LocalDate newEndDate = LocalDate.of(2025, 3, 7); // 2 extra days

            when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                    .thenReturn(Optional.of(booking));
            when(extensionRepository.hasPendingExtension(BOOKING_ID))
                    .thenReturn(false);
            when(bookingRepository.existsOverlappingBookingsWithLock(
                    eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(false);
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> {
                        TripExtension ext = invocation.getArgument(0);
                        ext.setId(EXTENSION_ID);
                        return ext;
                    });

            TripExtensionDTO result = service.requestExtension(
                    BOOKING_ID, newEndDate, "reason", GUEST_USER_ID);

            // 20000 / 4 = 5000 per day; 5000 * 2 = 10000
            assertThat(result.getDailyRate()).isEqualByComparingTo(new BigDecimal("5000.00"));
            assertThat(result.getAdditionalCost()).isEqualByComparingTo(new BigDecimal("10000.00"));
            assertThat(result.getAdditionalDays()).isEqualTo(2);
        }
    }

    // ========== APPROVE EXTENSION ==========

    @Nested
    @DisplayName("approveExtension")
    class ApproveExtension {

        @Test
                @DisplayName("9. approve charges extension first and then updates booking")
        void approve_updates_booking_end_time_and_total_price() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
            TripExtension extension = createPendingExtension(booking);

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(bookingRepository.save(any(Booking.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(bookingPaymentService.chargeExtension(eq(EXTENSION_ID), eq("pm_1")))
                    .thenReturn(PaymentProvider.PaymentResult.builder()
                            .success(true)
                            .status(PaymentProvider.PaymentStatus.SUCCESS)
                            .transactionId("ext_txn_1")
                            .build());

            TripExtensionDTO result = service.approveExtension(
                    EXTENSION_ID, "Approved, enjoy!", HOST_USER_ID);

            assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.APPROVED);

            // Verify booking was updated: endTime moved to Mar 8 at 10:00
            ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(bookingCaptor.capture());
            Booking savedBooking = bookingCaptor.getValue();

            assertThat(savedBooking.getEndTime())
                    .isEqualTo(LocalDateTime.of(2025, 3, 8, 10, 0));
            // totalPrice: 20000 + 15000 = 35000
            assertThat(savedBooking.getTotalPrice())
                    .isEqualByComparingTo(new BigDecimal("35000.00"));
            verify(bookingPaymentService).chargeExtension(EXTENSION_ID, "pm_1");
        }

        @Test
        @DisplayName("10. approve rejected if caller is not the host")
        void approve_rejected_if_not_host() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
            TripExtension extension = createPendingExtension(booking);
            Long wrongUserId = 999L;

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));

            assertThatThrownBy(() ->
                    service.approveExtension(EXTENSION_ID, "ok", wrongUserId))
                    .isInstanceOf(AccessDeniedException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
                @DisplayName("11. approve is idempotent when extension is already APPROVED")
        void approve_rejected_if_not_pending() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
            TripExtension extension = createPendingExtension(booking);
            extension.approve("already"); // move to APPROVED

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));

            TripExtensionDTO result = service.approveExtension(EXTENSION_ID, "ok", HOST_USER_ID);
            assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.APPROVED);
            verify(bookingRepository, never()).save(any());
            verify(bookingPaymentService, never()).chargeExtension(anyLong(), anyString());
        }

        @Test
        @DisplayName("12. approve sends notification to guest")
        void approve_sends_notification_to_guest() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
            TripExtension extension = createPendingExtension(booking);

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(bookingRepository.save(any(Booking.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(bookingPaymentService.chargeExtension(eq(EXTENSION_ID), eq("pm_1")))
                    .thenReturn(PaymentProvider.PaymentResult.builder()
                            .success(true)
                            .status(PaymentProvider.PaymentStatus.SUCCESS)
                            .transactionId("ext_txn_2")
                            .build());

            service.approveExtension(EXTENSION_ID, "Sure!", HOST_USER_ID);

            ArgumentCaptor<CreateNotificationRequestDTO> captor =
                    ArgumentCaptor.forClass(CreateNotificationRequestDTO.class);
            verify(notificationService).createNotification(captor.capture());

            CreateNotificationRequestDTO notification = captor.getValue();
            assertThat(notification.getRecipientId()).isEqualTo(GUEST_USER_ID);
            assertThat(notification.getMessage()).contains("odobren");
        }

                @Test
                @DisplayName("13. approve keeps extension PAYMENT_PENDING when redirect is required")
                void approve_redirect_required_keeps_payment_pending() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
                        TripExtension extension = createPendingExtension(booking);

                        when(extensionRepository.findById(EXTENSION_ID)).thenReturn(Optional.of(extension));
                        when(extensionRepository.save(any(TripExtension.class))).thenAnswer(invocation -> invocation.getArgument(0));
                        when(bookingPaymentService.chargeExtension(eq(EXTENSION_ID), eq("pm_1")))
                                        .thenReturn(PaymentProvider.PaymentResult.builder()
                                                        .success(false)
                                                        .status(PaymentProvider.PaymentStatus.REDIRECT_REQUIRED)
                                                        .redirectUrl("https://redirect")
                                                        .build());

                        TripExtensionDTO result = service.approveExtension(EXTENSION_ID, "ok", HOST_USER_ID);

                        assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.PAYMENT_PENDING);
                        assertThat(result.getPaymentRedirectUrl()).isNull();
                        assertThat(result.getPaymentActionToken()).isNull();
                        verify(bookingRepository, never()).save(any(Booking.class));
                }

                @Test
                @DisplayName("16. payment-pending extension finalizes once async charge succeeds")
                void payment_pending_extension_finalizes_after_async_success() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
                        TripExtension extension = createPendingExtension(booking);
                        extension.setStatus(TripExtensionStatus.PAYMENT_PENDING);

                        when(extensionRepository.findById(EXTENSION_ID)).thenReturn(Optional.of(extension));
                        when(extensionRepository.save(any(TripExtension.class))).thenAnswer(invocation -> invocation.getArgument(0));
                        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
                        when(bookingPaymentService.findExtensionPaymentAction(BOOKING_ID, EXTENSION_ID))
                                .thenReturn(Optional.of(new BookingPaymentService.ExtensionPaymentAction(
                                        true,
                                        PaymentProvider.PaymentStatus.SUCCESS,
                                        null,
                                        null,
                                        "ext_txn_1",
                                        null
                                )));

                        TripExtensionDTO result = service.approveExtension(EXTENSION_ID, "host response", HOST_USER_ID);

                        assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.APPROVED);
                        verify(bookingRepository).save(any(Booking.class));
                        verify(bookingPaymentService, never()).chargeExtension(anyLong(), anyString());
                }

                @Test
                @DisplayName("17. payment-pending extension reopens to PENDING after async failure")
                void payment_pending_extension_reopens_after_async_failure() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
                        TripExtension extension = createPendingExtension(booking);
                        extension.setStatus(TripExtensionStatus.PAYMENT_PENDING);

                        when(extensionRepository.findById(EXTENSION_ID)).thenReturn(Optional.of(extension));
                        when(extensionRepository.save(any(TripExtension.class))).thenAnswer(invocation -> invocation.getArgument(0));
                        when(bookingPaymentService.findExtensionPaymentAction(BOOKING_ID, EXTENSION_ID))
                                .thenReturn(Optional.of(new BookingPaymentService.ExtensionPaymentAction(
                                        false,
                                        PaymentProvider.PaymentStatus.FAILED,
                                        null,
                                        null,
                                        null,
                                        null
                                )));

                        TripExtensionDTO result = service.approveExtension(EXTENSION_ID, "host response", HOST_USER_ID);

                        assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.PENDING);
                        verify(bookingPaymentService, never()).chargeExtension(anyLong(), anyString());
                }

                @Test
                @DisplayName("14. approve fails and does not mutate booking when payment fails")
                void approve_payment_failure_does_not_mutate_booking() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
                        TripExtension extension = createPendingExtension(booking);

                        when(extensionRepository.findById(EXTENSION_ID)).thenReturn(Optional.of(extension));
                        when(extensionRepository.save(any(TripExtension.class))).thenAnswer(invocation -> invocation.getArgument(0));
                        when(bookingPaymentService.chargeExtension(eq(EXTENSION_ID), eq("pm_1")))
                                        .thenReturn(PaymentProvider.PaymentResult.builder()
                                                        .success(false)
                                                        .status(PaymentProvider.PaymentStatus.FAILED)
                                                        .errorCode("CARD_DECLINED")
                                                        .errorMessage("declined")
                                                        .build());

                        assertThatThrownBy(() -> service.approveExtension(EXTENSION_ID, "ok", HOST_USER_ID))
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("Naplata produženja nije uspela");

                        verify(bookingRepository, never()).save(any(Booking.class));
                }

                @Test
                @DisplayName("15. duplicate approve is idempotent for already-approved extension")
                void approve_is_idempotent_when_already_approved() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        booking.setStoredPaymentMethodId("pm_1");
                        TripExtension extension = createPendingExtension(booking);
                        extension.approve("already");

                        when(extensionRepository.findById(EXTENSION_ID)).thenReturn(Optional.of(extension));

                        TripExtensionDTO result = service.approveExtension(EXTENSION_ID, "ignored", HOST_USER_ID);

                        assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.APPROVED);
                        verify(bookingPaymentService, never()).chargeExtension(anyLong(), anyString());
                        verify(bookingRepository, never()).save(any(Booking.class));
                }

                @Test
                @DisplayName("18. renter pending read contains payment continuation data")
                void renter_pending_read_exposes_payment_continuation_data() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        TripExtension extension = createPendingExtension(booking);
                        extension.setStatus(TripExtensionStatus.PAYMENT_PENDING);

                        when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(booking));
                        when(extensionRepository.findPendingByBookingId(BOOKING_ID)).thenReturn(Optional.of(extension));
                        when(bookingPaymentService.findExtensionPaymentAction(BOOKING_ID, EXTENSION_ID))
                                .thenReturn(Optional.of(new BookingPaymentService.ExtensionPaymentAction(
                                        false,
                                        PaymentProvider.PaymentStatus.REDIRECT_REQUIRED,
                                        "https://redirect",
                                        "sca-token-1",
                                        null,
                                        "auth-ext"
                                )));

                        TripExtensionDTO result = service.getPendingExtension(BOOKING_ID, GUEST_USER_ID);

                        assertThat(result).isNotNull();
                        assertThat(result.getPaymentRedirectUrl()).isEqualTo("https://redirect");
                        assertThat(result.getPaymentActionToken()).isEqualTo("sca-token-1");
                        verify(extensionRepository, never()).save(any(TripExtension.class));
                }

                @Test
                @DisplayName("19. host pending read hides payment continuation data")
                void host_pending_read_hides_payment_continuation_data() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);
                        TripExtension extension = createPendingExtension(booking);
                        extension.setStatus(TripExtensionStatus.PAYMENT_PENDING);

                        when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(booking));
                        when(extensionRepository.findPendingByBookingId(BOOKING_ID)).thenReturn(Optional.of(extension));

                        TripExtensionDTO result = service.getPendingExtension(BOOKING_ID, HOST_USER_ID);

                        assertThat(result).isNotNull();
                        assertThat(result.getPaymentRedirectUrl()).isNull();
                        assertThat(result.getPaymentActionToken()).isNull();
                        verify(extensionRepository, never()).save(any(TripExtension.class));
                        verify(bookingPaymentService, never()).findExtensionPaymentAction(anyLong(), anyLong());
                }
    }

    // ========== DECLINE EXTENSION ==========

    @Nested
    @DisplayName("declineExtension")
    class DeclineExtension {

        @Test
        @DisplayName("13. decline sets status to DECLINED")
        void decline_sets_status_declined() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            TripExtension extension = createPendingExtension(booking);

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TripExtensionDTO result = service.declineExtension(
                    EXTENSION_ID, "Car needed for next booking", HOST_USER_ID);

            assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.DECLINED);
            assertThat(result.getStatusDisplay()).isEqualTo("Odbijeno");

            // Booking should NOT be modified on decline
            verify(bookingRepository, never()).save(any());

            // Guest should be notified
            verify(notificationService).createNotification(any(CreateNotificationRequestDTO.class));
        }

        @Test
        @DisplayName("14. decline rejected if extension is not PENDING")
        void decline_rejected_if_not_pending() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            TripExtension extension = createPendingExtension(booking);
            extension.approve("already approved"); // move to APPROVED

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));

            assertThatThrownBy(() ->
                    service.declineExtension(EXTENSION_ID, "too late", HOST_USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nije na \u010dekanju");

            verify(extensionRepository, never()).save(any());
        }
    }

    // ========== CANCEL EXTENSION ==========

    @Nested
    @DisplayName("cancelExtension")
    class CancelExtension {

        @Test
        @DisplayName("15. cancel by guest succeeds")
        void cancel_by_guest_succeeds() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            TripExtension extension = createPendingExtension(booking);

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TripExtensionDTO result = service.cancelExtension(EXTENSION_ID, GUEST_USER_ID);

            assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.CANCELLED);
            assertThat(result.getStatusDisplay()).isEqualTo("Otkazano");

            verify(extensionRepository).save(any(TripExtension.class));
        }

        @Test
        @DisplayName("16. cancel rejected if caller is not the guest")
        void cancel_rejected_if_not_guest() {
            Booking booking = createTestBooking(BookingStatus.IN_TRIP);
            TripExtension extension = createPendingExtension(booking);
            Long wrongUserId = 999L;

            when(extensionRepository.findById(EXTENSION_ID))
                    .thenReturn(Optional.of(extension));

            assertThatThrownBy(() ->
                    service.cancelExtension(EXTENSION_ID, wrongUserId))
                    .isInstanceOf(AccessDeniedException.class);

            verify(extensionRepository, never()).save(any());
        }
    }

        @Nested
        @DisplayName("getExtensionsForBooking")
        class GetExtensionsForBooking {

                @Test
                @DisplayName("17. returns empty list when booking has no extensions")
                void get_extensions_for_booking_returns_empty_list_when_none_exist() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);

                        when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                                        .thenReturn(Optional.of(booking));
                        when(extensionRepository.findByBookingIdOrderByCreatedAtDesc(BOOKING_ID))
                                        .thenReturn(java.util.List.of());

                        java.util.List<TripExtensionDTO> result = service.getExtensionsForBooking(BOOKING_ID, GUEST_USER_ID);

                        assertThat(result).isEmpty();
                        verify(extensionRepository).findByBookingIdOrderByCreatedAtDesc(BOOKING_ID);
                        verify(bookingPaymentService, never()).findExtensionPaymentAction(anyLong(), anyLong());
                }

                @Test
                @DisplayName("18. rejects access when caller is neither host nor guest")
                void get_extensions_for_booking_rejects_unauthorized_user() {
                        Booking booking = createTestBooking(BookingStatus.IN_TRIP);

                        when(bookingRepository.findByIdWithRelations(BOOKING_ID))
                                        .thenReturn(Optional.of(booking));

                        assertThatThrownBy(() -> service.getExtensionsForBooking(BOOKING_ID, 999L))
                                        .isInstanceOf(AccessDeniedException.class)
                                        .hasMessageContaining("Nemate pristup ovoj rezervaciji");

                        verify(extensionRepository, never()).findByBookingIdOrderByCreatedAtDesc(anyLong());
                }
        }

    // ========== HELPER METHODS ==========

    /**
     * Creates a fully wired test booking with renter (id=10), owner (id=20),
     * car (id=100), dates Mar 1-5 2025, totalPrice 20000, snapshotDailyRate 5000.
     */
    private Booking createTestBooking(BookingStatus status) {
        User renter = new User();
        renter.setId(GUEST_USER_ID);
        renter.setFirstName("Test");
        renter.setLastName("Renter");

        User owner = new User();
        owner.setId(HOST_USER_ID);
        owner.setFirstName("Test");
        owner.setLastName("Owner");

        Car car = new Car();
        car.setId(100L);
        car.setOwner(owner);
        car.setBrand("Tesla");
        car.setModel("Model 3");
        car.setImageUrl("http://img.test/car.jpg");

        Booking booking = new Booking();
        booking.setId(BOOKING_ID);
        booking.setStatus(status);
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setStartTime(LocalDateTime.of(2025, 3, 1, 10, 0));
        booking.setEndTime(LocalDateTime.of(2025, 3, 5, 10, 0));
        booking.setTotalPrice(new BigDecimal("20000.00"));
        booking.setSnapshotDailyRate(new BigDecimal("5000.00"));

        return booking;
    }

    /**
     * Creates a PENDING trip extension for the given booking:
     * original end Mar 5, requested end Mar 8, 3 additional days,
     * dailyRate 5000, additionalCost 15000.
     */
    private TripExtension createPendingExtension(Booking booking) {
        return TripExtension.builder()
                .id(EXTENSION_ID)
                .booking(booking)
                .originalEndDate(LocalDate.of(2025, 3, 5))
                .requestedEndDate(LocalDate.of(2025, 3, 8))
                .additionalDays(3)
                .reason("Need extra days")
                .dailyRate(new BigDecimal("5000.00"))
                .additionalCost(new BigDecimal("15000.00"))
                .status(TripExtensionStatus.PENDING)
                .build();
    }
}
