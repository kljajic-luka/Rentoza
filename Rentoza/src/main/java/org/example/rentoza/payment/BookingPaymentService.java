package org.example.rentoza.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtension;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.payment.PaymentProvider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Service for orchestrating booking-related payments.
 * 
 * <h2>Payment Flows</h2>
 * <ul>
 *   <li><b>Booking:</b> Full payment at booking time</li>
 *   <li><b>Security Deposit:</b> Authorized at check-in, released/captured at checkout</li>
 *   <li><b>Damage Charge:</b> Charged against deposit or separate charge</li>
 *   <li><b>Late Fee:</b> Additional charge for late returns</li>
 *   <li><b>Extension:</b> Additional charge for trip extensions</li>
 * </ul>
 */
@Service
@Slf4j
public class BookingPaymentService {

    private static final String DEFAULT_CURRENCY = "RSD";

    private final PaymentProvider paymentProvider;
    private final BookingRepository bookingRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final TripExtensionRepository extensionRepository;

    // Metrics
    private final Counter paymentSuccessCounter;
    private final Counter paymentFailedCounter;
    private final Counter depositAuthorizedCounter;
    private final Counter depositReleasedCounter;

    @Value("${app.payment.deposit.amount-rsd:30000}")
    private int defaultDepositAmountRsd;

    public BookingPaymentService(
            PaymentProvider paymentProvider,
            BookingRepository bookingRepository,
            DamageClaimRepository damageClaimRepository,
            TripExtensionRepository extensionRepository,
            MeterRegistry meterRegistry) {
        this.paymentProvider = paymentProvider;
        this.bookingRepository = bookingRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.extensionRepository = extensionRepository;

        this.paymentSuccessCounter = Counter.builder("payment.success")
                .description("Successful payments")
                .register(meterRegistry);

        this.paymentFailedCounter = Counter.builder("payment.failed")
                .description("Failed payments")
                .register(meterRegistry);

        this.depositAuthorizedCounter = Counter.builder("payment.deposit.authorized")
                .description("Deposits authorized")
                .register(meterRegistry);

        this.depositReleasedCounter = Counter.builder("payment.deposit.released")
                .description("Deposits released")
                .register(meterRegistry);
    }

    // ========== BOOKING PAYMENT ==========

    /**
     * Process initial booking payment.
     */
    @Transactional
    public PaymentResult processBookingPayment(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(booking.getTotalPrice())
                .currency(DEFAULT_CURRENCY)
                .description("Rezervacija #" + bookingId)
                .type(PaymentType.BOOKING_PAYMENT)
                .paymentMethodId(paymentMethodId)
                .build();

        PaymentResult result = paymentProvider.charge(request);

        if (result.isSuccess()) {
            booking.setPaymentStatus("PAID");
            booking.setPaymentVerificationRef(result.getTransactionId());
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} paid successfully: {}", bookingId, result.getTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Booking {} payment failed: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    // ========== SECURITY DEPOSIT ==========

    /**
     * Authorize security deposit at check-in.
     * Amount is held but not captured until checkout.
     */
    @Transactional
    public PaymentResult authorizeDeposit(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);

        BigDecimal depositAmount = BigDecimal.valueOf(defaultDepositAmountRsd);

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(depositAmount)
                .currency(DEFAULT_CURRENCY)
                .description("Depozit za rezervaciju #" + bookingId)
                .type(PaymentType.SECURITY_DEPOSIT)
                .paymentMethodId(paymentMethodId)
                .build();

        PaymentResult result = paymentProvider.authorize(request);

        if (result.isSuccess()) {
            // Store authorization ID in booking for later capture/release
            // Note: In production, create a separate payment_transactions table
            booking.setPaymentStatus("DEPOSIT_AUTHORIZED");
            bookingRepository.save(booking);
            depositAuthorizedCounter.increment();
            log.info("[Payment] Deposit authorized for booking {}: {}", bookingId, result.getAuthorizationId());
        } else {
            log.warn("[Payment] Deposit authorization failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    /**
     * Release security deposit (no damage).
     * Called at checkout when no issues.
     */
    @Transactional
    public PaymentResult releaseDeposit(Long bookingId, String authorizationId) {
        Booking booking = getBooking(bookingId);

        PaymentResult result = paymentProvider.releaseAuthorization(authorizationId);

        if (result.isSuccess()) {
            booking.setPaymentStatus("DEPOSIT_RELEASED");
            bookingRepository.save(booking);
            depositReleasedCounter.increment();
            log.info("[Payment] Deposit released for booking {}", bookingId);
        }

        return result;
    }

    // ========== DAMAGE CHARGES ==========

    /**
     * Charge damage to guest.
     * Captures from deposit if available, otherwise charges directly.
     */
    @Transactional
    public PaymentResult chargeDamage(Long claimId, String authorizationIdOrPaymentMethod) {
        DamageClaim claim = damageClaimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava štete nije pronađena"));

        BigDecimal amount = claim.getApprovedAmount();
        Long bookingId = claim.getBooking().getId();

        // Try to capture from deposit first
        PaymentResult result = paymentProvider.capture(authorizationIdOrPaymentMethod, amount);

        if (result.isSuccess()) {
            claim.markPaid(result.getTransactionId());
            damageClaimRepository.save(claim);
            paymentSuccessCounter.increment();
            log.info("[Payment] Damage charge processed for claim {}: {}", claimId, result.getTransactionId());
        } else {
            // If capture fails (no auth or insufficient), try direct charge
            PaymentRequest request = PaymentRequest.builder()
                    .bookingId(bookingId)
                    .userId(claim.getGuest().getId())
                    .amount(amount)
                    .currency(DEFAULT_CURRENCY)
                    .description("Naknada za štetu - Rezervacija #" + bookingId)
                    .type(PaymentType.DAMAGE_CHARGE)
                    .paymentMethodId(authorizationIdOrPaymentMethod)
                    .build();

            result = paymentProvider.charge(request);

            if (result.isSuccess()) {
                claim.markPaid(result.getTransactionId());
                damageClaimRepository.save(claim);
                paymentSuccessCounter.increment();
                log.info("[Payment] Damage direct charge for claim {}: {}", claimId, result.getTransactionId());
            } else {
                paymentFailedCounter.increment();
                log.warn("[Payment] Damage charge failed for claim {}: {}", claimId, result.getErrorMessage());
            }
        }

        return result;
    }

    // ========== LATE FEES ==========

    /**
     * Charge late return fee.
     */
    @Transactional
    public PaymentResult chargeLateReturnFee(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);

        if (booking.getLateFeeAmount() == null || booking.getLateFeeAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return PaymentResult.builder()
                    .success(true)
                    .amount(BigDecimal.ZERO)
                    .status(PaymentStatus.CAPTURED)
                    .build();
        }

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(booking.getLateFeeAmount())
                .currency(DEFAULT_CURRENCY)
                .description("Naknada za kašnjenje - Rezervacija #" + bookingId)
                .type(PaymentType.LATE_FEE)
                .paymentMethodId(paymentMethodId)
                .build();

        PaymentResult result = paymentProvider.charge(request);

        if (result.isSuccess()) {
            paymentSuccessCounter.increment();
            log.info("[Payment] Late fee charged for booking {}: {}", bookingId, result.getTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Late fee charge failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    // ========== EXTENSION PAYMENT ==========

    /**
     * Charge trip extension fee.
     */
    @Transactional
    public PaymentResult chargeExtension(Long extensionId, String paymentMethodId) {
        TripExtension extension = extensionRepository.findById(extensionId)
                .orElseThrow(() -> new ResourceNotFoundException("Produženje nije pronađeno"));

        Long bookingId = extension.getBooking().getId();

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(extension.getBooking().getRenter().getId())
                .amount(extension.getAdditionalCost())
                .currency(DEFAULT_CURRENCY)
                .description("Produženje rezervacije #" + bookingId + " (" + extension.getAdditionalDays() + " dana)")
                .type(PaymentType.EXTENSION_PAYMENT)
                .paymentMethodId(paymentMethodId)
                .build();

        PaymentResult result = paymentProvider.charge(request);

        if (result.isSuccess()) {
            paymentSuccessCounter.increment();
            log.info("[Payment] Extension charged for booking {}: {}", bookingId, result.getTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Extension charge failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    // ========== REFUNDS ==========

    /**
     * Process refund for cancellation or other reasons.
     */
    @Transactional
    public PaymentResult processRefund(Long bookingId, BigDecimal amount, String reason) {
        Booking booking = getBooking(bookingId);

        if (booking.getPaymentVerificationRef() == null) {
            return PaymentResult.builder()
                    .success(false)
                    .errorMessage("Nema uplate za povraćaj")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        PaymentResult result = paymentProvider.refund(
                booking.getPaymentVerificationRef(),
                amount,
                reason
        );

        if (result.isSuccess()) {
            paymentSuccessCounter.increment();
            log.info("[Payment] Refund processed for booking {}: {} RSD", bookingId, amount);
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Refund failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    // ========== HELPERS ==========

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
    }
}


