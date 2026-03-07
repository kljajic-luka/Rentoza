package org.example.rentoza.payment;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.extension.TripExtension;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.booking.extension.TripExtensionStatus;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.PaymentTransaction.PaymentOperation;
import org.example.rentoza.payment.PaymentTransaction.PaymentTransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProviderEventService — specifically validating
 * that webhook handlers use canTransitionTo lifecycle guards.
 */
@ExtendWith(MockitoExtension.class)
class ProviderEventServiceTest {

    @Mock
    private ProviderEventRepository eventRepository;

    @Mock
    private PaymentTransactionRepository txRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private PayoutLedgerRepository payoutLedgerRepository;

    @Mock
    private TripExtensionRepository tripExtensionRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ProviderEventService service;

    private static final Long BOOKING_ID = 100L;
    private static final String AUTH_ID = "auth-3ds-001";
    private static final String EVENT_ID = "evt-001";

    @BeforeEach
    void setUp() {
        // No webhook secret → skip HMAC verification, no fail-closed behavior
        ReflectionTestUtils.setField(service, "webhookSecret", "");
        ReflectionTestUtils.setField(service, "activeProfile", "dev");
        // Not a duplicate event
        lenient().when(eventRepository.findByProviderEventId(any())).thenReturn(Optional.empty());
        lenient().when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Creates a booking with the specified charge lifecycle status.
     */
    private Booking bookingInState(ChargeLifecycleStatus status) {
        Booking booking = new Booking();
        booking.setId(BOOKING_ID);
        booking.setChargeLifecycleStatus(status);
        return booking;
    }

    /**
     * Creates a REDIRECT_REQUIRED AUTHORIZE tx matching the provider auth ID.
     */
    private PaymentTransaction redirectRequiredTx() {
        PaymentTransaction tx = new PaymentTransaction();
        tx.setId(1L);
        tx.setBookingId(BOOKING_ID);
        tx.setStatus(PaymentTransactionStatus.REDIRECT_REQUIRED);
        tx.setOperation(PaymentOperation.AUTHORIZE);
        tx.setProviderAuthId(AUTH_ID);
        return tx;
    }

    private PaymentTransaction redirectRequiredChargeTx() {
        PaymentTransaction tx = new PaymentTransaction();
        tx.setId(2L);
        tx.setBookingId(BOOKING_ID);
        tx.setStatus(PaymentTransactionStatus.REDIRECT_REQUIRED);
        tx.setOperation(PaymentOperation.CHARGE);
        tx.setProviderReference(AUTH_ID);
        tx.setIdempotencyKey("pay_ext_100_e1");
        return tx;
    }

    private TripExtension paymentPendingExtension(Booking booking) {
        TripExtension extension = new TripExtension();
        extension.setId(1L);
        extension.setBooking(booking);
        extension.setStatus(TripExtensionStatus.PAYMENT_PENDING);
        extension.setRequestedEndDate(java.time.LocalDate.now().plusDays(2));
        extension.setAdditionalCost(java.math.BigDecimal.valueOf(1000));
        return extension;
    }

    // ========================================================================
    // PAYMENT_CONFIRMED — lifecycle guard tests
    // ========================================================================

    @Nested
    @DisplayName("PAYMENT_CONFIRMED lifecycle guards")
    class PaymentConfirmedLifecycleTests {

        @Test
        @DisplayName("PENDING → AUTHORIZED via canTransitionTo guard")
        void shouldTransitionPendingToAuthorized() {
            Booking booking = bookingInState(ChargeLifecycleStatus.PENDING);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.of(redirectRequiredTx()));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_CONFIRMED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
            assertThat(booking.getPaymentStatus()).isEqualTo("AUTHORIZED");
            verify(bookingRepository).save(booking);
        }

        @Test
        @DisplayName("REAUTH_REQUIRED → AUTHORIZED via canTransitionTo guard")
        void shouldTransitionReauthRequiredToAuthorized() {
            Booking booking = bookingInState(ChargeLifecycleStatus.REAUTH_REQUIRED);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.of(redirectRequiredTx()));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_CONFIRMED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
            // Auth expiry should be refreshed on reauth
            assertThat(booking.getBookingAuthExpiresAt()).isNotNull();
        }

        @Test
        @DisplayName("CAPTURED (terminal) — should NOT overwrite on PAYMENT_CONFIRMED")
        void shouldNotOverwriteCapturedState() {
            Booking booking = bookingInState(ChargeLifecycleStatus.CAPTURED);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.of(redirectRequiredTx()));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_CONFIRMED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            // CAPTURED is terminal — handler should NOT overwrite to AUTHORIZED
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURED);
        }

        @Test
        @DisplayName("null status (legacy) → AUTHORIZED")
        void shouldTransitionNullToAuthorized() {
            Booking booking = bookingInState(null);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.of(redirectRequiredTx()));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_CONFIRMED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
        }

        @Test
        @DisplayName("Extension CHARGE confirmation should settle tx without mutating booking lifecycle")
        void shouldConfirmChargeWithoutChangingBookingLifecycle() {
            Booking booking = bookingInState(ChargeLifecycleStatus.CAPTURED);
            booking.setEndTime(java.time.LocalDateTime.now().plusDays(1));
            booking.setTotalPrice(java.math.BigDecimal.valueOf(5000));
            org.example.rentoza.user.User renter = new org.example.rentoza.user.User();
            renter.setId(10L);
            booking.setRenter(renter);
            TripExtension extension = paymentPendingExtension(booking);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.empty());
            when(txRepository.findByProviderReference(AUTH_ID)).thenReturn(Optional.of(redirectRequiredChargeTx()));
            when(tripExtensionRepository.findById(1L)).thenReturn(Optional.of(extension));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(tripExtensionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_CONFIRMED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURED);
                verify(bookingRepository).save(booking);
            verify(tripExtensionRepository).save(extension);
            verify(notificationService).createNotification(any());
        }
    }

    // ========================================================================
    // PAYMENT_FAILED — lifecycle guard tests
    // ========================================================================

    @Nested
    @DisplayName("PAYMENT_FAILED lifecycle guards")
    class PaymentFailedLifecycleTests {

        @Test
        @DisplayName("PENDING → CAPTURE_FAILED via canTransitionTo guard")
        void shouldTransitionPendingToCaptureFailed() {
            Booking booking = bookingInState(ChargeLifecycleStatus.PENDING);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.of(redirectRequiredTx()));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_FAILED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURE_FAILED);
            assertThat(booking.getPaymentStatus()).isEqualTo("PAYMENT_FAILED");
        }

        @Test
        @DisplayName("AUTHORIZED — should NOT transition to CAPTURE_FAILED")
        void shouldNotTransitionAuthorizedToCaptureFailed() {
            Booking booking = bookingInState(ChargeLifecycleStatus.AUTHORIZED);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.of(redirectRequiredTx()));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_FAILED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            // AUTHORIZED should NOT be overwritten — handler only transitions PENDING
            assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
        }

        @Test
        @DisplayName("Extension CHARGE failure should fail tx without mutating booking lifecycle")
        void shouldFailChargeWithoutChangingBookingLifecycle() {
            Booking booking = bookingInState(ChargeLifecycleStatus.CAPTURED);
            TripExtension extension = paymentPendingExtension(booking);
            when(txRepository.findByProviderAuthId(AUTH_ID)).thenReturn(Optional.empty());
            when(txRepository.findByProviderReference(AUTH_ID)).thenReturn(Optional.of(redirectRequiredChargeTx()));
            when(tripExtensionRepository.findById(1L)).thenReturn(Optional.of(extension));
            when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(tripExtensionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.ingestEvent(EVENT_ID, "PAYMENT_FAILED",
                    BOOKING_ID, AUTH_ID, "{}", null);

            assertThat(result).isTrue();
            verifyNoInteractions(bookingRepository);
            verify(tripExtensionRepository).save(extension);
        }
    }

    @Test
    @DisplayName("M6: webhook without authId when secret active rejects non-payout event")
    void webhookWithoutAuthId_whenSecretActive_rejectsNonPayoutEvent() {
        ReflectionTestUtils.setField(service, "webhookSecret", "prod-webhook-secret");

        ProviderEvent stored = ProviderEvent.builder()
                .providerEventId(EVENT_ID)
                .eventType("PAYMENT_CONFIRMED")
                .bookingId(BOOKING_ID)
                .build();
        when(eventRepository.findByProviderEventId(EVENT_ID)).thenReturn(Optional.of(stored));

        ProviderEventService.IngestResult result = service.ingestEventDetailed(
                EVENT_ID,
                "PAYMENT_CONFIRMED",
                BOOKING_ID,
                null,
                "{}",
                null,
                null);

        assertThat(result.outcome()).isEqualTo(ProviderEventService.IngestOutcome.RETRYABLE_FAILURE);
        assertThat(result.reason()).contains("Missing providerAuthorizationId");
    }

    @Test
    @DisplayName("M6: webhook without authId when secret active allows payout event")
    void webhookWithoutAuthId_whenSecretActive_allowsPayoutEvent() {
        ReflectionTestUtils.setField(service, "webhookSecret", "prod-webhook-secret");

        ProviderEvent stored = ProviderEvent.builder()
                .providerEventId(EVENT_ID)
                .eventType("PAYOUT.COMPLETED")
                .bookingId(BOOKING_ID)
                .build();
        when(eventRepository.findByProviderEventId(EVENT_ID)).thenReturn(Optional.of(stored));
        when(payoutLedgerRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        ProviderEventService.IngestResult result = service.ingestEventDetailed(
                EVENT_ID,
                "PAYOUT.COMPLETED",
                BOOKING_ID,
                null,
                "{}",
                null,
                null);

        assertThat(result.outcome()).isEqualTo(ProviderEventService.IngestOutcome.PROCESSED);
        assertThat(result.processed()).isTrue();
    }
}
