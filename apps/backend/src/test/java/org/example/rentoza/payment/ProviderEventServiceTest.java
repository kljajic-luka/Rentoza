package org.example.rentoza.payment;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
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

    @InjectMocks
    private ProviderEventService service;

    private static final Long BOOKING_ID = 100L;
    private static final String AUTH_ID = "auth-3ds-001";
    private static final String EVENT_ID = "evt-001";

    @BeforeEach
    void setUp() {
        // No webhook secret → skip HMAC verification, no fail-closed behavior
        ReflectionTestUtils.setField(service, "webhookSecret", "");
        // Not a duplicate event
        lenient().when(eventRepository.existsByProviderEventId(any())).thenReturn(false);
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
    }
}
