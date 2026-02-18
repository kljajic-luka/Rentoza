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
     * Authorize initial booking payment (hold, not capture).
     * Funds are held on the guest's card and captured later when host approves
     * and trip completes via {@link #captureBookingPayment(Long)}.
     * 
     * <p><b>P0 Fix:</b> Changed from charge() to authorize() — Turo standard requires
     * holding funds, not capturing immediately. This ensures:
     * <ul>
     *   <li>Guest sees a pending hold, not an actual charge</li>
     *   <li>Funds are released automatically if host declines</li>
     *   <li>Capture only happens after trip completion</li>
     * </ul>
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

        // P0 FIX: authorize() not charge() — hold funds without capturing
        PaymentResult result = paymentProvider.authorize(request);

        if (result.isSuccess()) {
            booking.setPaymentStatus("AUTHORIZED");
            booking.setPaymentVerificationRef(result.getAuthorizationId());
            booking.setBookingAuthorizationId(result.getAuthorizationId());
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} authorized successfully: {}", bookingId, result.getAuthorizationId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Booking {} authorization failed: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    /**
     * Capture a previously authorized booking payment.
     * Called after trip completion when funds should actually be transferred.
     * 
     * @param bookingId Booking whose authorized payment should be captured
     * @return Payment result
     */
    @Transactional
    public PaymentResult captureBookingPayment(Long bookingId) {
        Booking booking = getBooking(bookingId);

        String authorizationId = booking.getBookingAuthorizationId();
        if (authorizationId == null || authorizationId.isBlank()) {
            return PaymentResult.builder()
                    .success(false)
                    .errorMessage("No booking authorization to capture")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        PaymentResult result = paymentProvider.capture(authorizationId, booking.getTotalPrice());

        if (result.isSuccess()) {
            booking.setPaymentStatus("CAPTURED");
            booking.setPaymentVerificationRef(result.getTransactionId());
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} payment captured: {}", bookingId, result.getTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Booking {} capture failed: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }

    /**
     * Release a previously authorized booking payment hold.
     * Called when host declines or booking expires.
     * 
     * @param bookingId Booking whose payment hold should be released
     * @return Payment result
     */
    @Transactional
    public PaymentResult releaseBookingPayment(Long bookingId) {
        Booking booking = getBooking(bookingId);

        String authorizationId = booking.getBookingAuthorizationId();
        if (authorizationId == null || authorizationId.isBlank()) {
            log.warn("[Payment] No booking authorization to release for booking {}", bookingId);
            return PaymentResult.builder()
                    .success(true)
                    .status(PaymentStatus.CANCELLED)
                    .build();
        }

        PaymentResult result = paymentProvider.releaseAuthorization(authorizationId);

        if (result.isSuccess()) {
            booking.setPaymentStatus("RELEASED");
            bookingRepository.save(booking);
            log.info("[Payment] Booking {} payment hold released: {}", bookingId, authorizationId);
        } else {
            log.warn("[Payment] Failed to release booking {} payment hold: {}", bookingId, result.getErrorMessage());
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
            // P0 FIX: Persist depositAuthorizationId for later release/capture
            booking.setPaymentStatus("DEPOSIT_AUTHORIZED");
            booking.setDepositAuthorizationId(result.getAuthorizationId());
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
     * 
     * <p><b>BUG-007 / VAL-010:</b> Deposit MUST NOT be released if there are pending damage claims.
     * This prevents the guest from receiving the deposit back while a damage dispute is ongoing.
     * 
     * <p><b>Blocking Statuses:</b>
     * <ul>
     *   <li>PENDING - Awaiting guest response</li>
     *   <li>DISPUTED - Under admin review</li>
     *   <li>ACCEPTED_BY_GUEST - Accepted but payment pending</li>
     *   <li>AUTO_APPROVED - Auto-approved but payment pending</li>
     *   <li>ADMIN_APPROVED - Admin approved but payment pending</li>
     *   <li>ESCALATED - Under senior review</li>
     * </ul>
     * 
     * @param bookingId The booking ID
     * @param authorizationId The deposit authorization ID
     * @return PaymentResult indicating success or failure
     * @throws IllegalStateException if deposit release is blocked by pending claims
     */
    @Transactional
    public PaymentResult releaseDeposit(Long bookingId, String authorizationId) {
        Booking booking = getBooking(bookingId);

        // BUG-007: Check for pending damage claims before releasing deposit
        if (damageClaimRepository.hasClaimsBlockingDepositRelease(bookingId)) {
            log.warn("[Payment] Deposit release BLOCKED for booking {} - pending damage claims exist", bookingId);
            throw new IllegalStateException(
                "Depozit ne može biti vraćen dok postoje nerešene prijave štete. " +
                "Molimo sačekajte razrešenje svih prijava."
            );
        }

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
     * 
     * <p><b>P0 FIX: Refund safety validation.</b>
     * <ul>
     *   <li>Refund amount must not exceed the captured/charged amount</li>
     *   <li>Only refund against captured transactions (not authorizations)</li>
     *   <li>If payment is still AUTHORIZED (not captured), releases the auth hold instead</li>
     * </ul>
     */
    @Transactional
    public PaymentResult processRefund(Long bookingId, BigDecimal amount, String reason) {
        Booking booking = getBooking(bookingId);

        String paymentStatus = booking.getPaymentStatus();
        String paymentRef = booking.getPaymentVerificationRef();

        // P0 FIX: If payment is only AUTHORIZED (not captured), release the hold instead of refunding
        if ("AUTHORIZED".equals(paymentStatus) || "DEPOSIT_AUTHORIZED".equals(paymentStatus)) {
            log.info("[Payment] Booking {} is only authorized (not captured) - releasing authorization instead of refunding", bookingId);
            return releaseBookingPayment(bookingId);
        }

        if (paymentRef == null) {
            return PaymentResult.builder()
                    .success(false)
                    .errorMessage("Nema uplate za povraćaj")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        // P0 FIX: Validate refund amount does not exceed captured amount
        BigDecimal maxRefundable = booking.getTotalPrice();
        if (amount.compareTo(maxRefundable) > 0) {
            log.warn("[Payment] REJECTED: Refund {} exceeds captured amount {} for booking {}", 
                amount, maxRefundable, bookingId);
            return PaymentResult.builder()
                    .success(false)
                    .errorCode("REFUND_EXCEEDS_CAPTURED")
                    .errorMessage(String.format(
                        "Iznos povraćaja (%s RSD) premašuje naplaćeni iznos (%s RSD)", amount, maxRefundable))
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        PaymentResult result = paymentProvider.refund(
                paymentRef,
                amount,
                reason
        );

        if (result.isSuccess()) {
            booking.setPaymentStatus("REFUNDED");
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Refund processed for booking {}: {} RSD", bookingId, amount);
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Refund failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return result;
    }
    
    /**
     * VAL-004: Process full refund for booking (total price).
     * Used when canceling booking due to check-in disputes.
     * 
     * <p><b>P0 FIX:</b> Intelligently handles both authorized and captured states:
     * <ul>
     *   <li>AUTHORIZED → releases the authorization hold</li>
     *   <li>CAPTURED → processes a refund of the total amount</li>
     * </ul>
     */
    @Transactional
    public PaymentResult processFullRefund(Long bookingId, String reason) {
        Booking booking = getBooking(bookingId);
        return processRefund(bookingId, booking.getTotalPrice(), reason);
    }
    
    /**
     * Process cancellation settlement: release authorization or refund captured payment.
     * 
     * <p><b>P0 FIX:</b> Called by the cancellation settlement worker.
     * Determines the correct action based on payment state:
     * <ul>
     *   <li>AUTHORIZED → release hold (no money was charged)</li>
     *   <li>CAPTURED → partial or full refund based on cancellation policy</li>
     *   <li>RELEASED/REFUNDED → no-op (already settled)</li>
     * </ul>
     * 
     * @param bookingId Booking to settle
     * @param refundAmount Amount to refund (from cancellation policy calculation)
     * @param reason Settlement reason
     * @return Payment result
     */
    @Transactional
    public PaymentResult processCancellationSettlement(Long bookingId, BigDecimal refundAmount, String reason) {
        Booking booking = getBooking(bookingId);
        String status = booking.getPaymentStatus();
        
        if ("RELEASED".equals(status) || "REFUNDED".equals(status)) {
            log.info("[Payment] Booking {} already settled (status: {}), skipping", bookingId, status);
            return PaymentResult.builder()
                    .success(true)
                    .status(PaymentStatus.SUCCESS)
                    .build();
        }
        
        if ("AUTHORIZED".equals(status) || "DEPOSIT_AUTHORIZED".equals(status)) {
            // Release the authorization hold - no money was charged
            log.info("[Payment] Booking {} releasing auth hold for cancellation", bookingId);
            PaymentResult releaseResult = releaseBookingPayment(bookingId);
            
            // Also release deposit if present
            String depositAuthId = booking.getDepositAuthorizationId();
            if (depositAuthId != null && !depositAuthId.isBlank()) {
                try {
                    releaseDeposit(bookingId, depositAuthId);
                } catch (Exception e) {
                    log.warn("[Payment] Failed to release deposit for cancelled booking {}: {}", bookingId, e.getMessage());
                }
            }
            return releaseResult;
        }
        
        if ("CAPTURED".equals(status)) {
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                return processRefund(bookingId, refundAmount, reason);
            } else {
                log.info("[Payment] No refund for booking {} (refund amount is zero per cancellation policy)", bookingId);
                return PaymentResult.builder()
                        .success(true)
                        .amount(BigDecimal.ZERO)
                        .status(PaymentStatus.SUCCESS)
                        .build();
            }
        }
        
        log.warn("[Payment] Unknown payment status '{}' for booking {} cancellation settlement", status, bookingId);
        return processRefund(bookingId, refundAmount, reason);
    }
    
    // ========== HOST PAYOUT (Admin-triggered) ==========
    
    /**
     * Process host payout after booking completion.
     * Called by AdminFinancialService for batch payouts.
     * 
     * @param booking Completed booking
     * @param batchReference Batch payout reference for tracking
     * @return Payment result
     */
    @Transactional
    public PaymentResult processHostPayout(Booking booking, String batchReference) {
        log.info("[Payment] Processing host payout for booking {}, batch: {}", 
                 booking.getId(), batchReference);
        
        // Calculate host payout (total - platform fee)
        BigDecimal platformFeeRate = new BigDecimal("0.15"); // 15% platform fee
        BigDecimal platformFee = booking.getTotalAmount().multiply(platformFeeRate);
        BigDecimal hostPayout = booking.getTotalAmount().subtract(platformFee);
        
        // For now, simulate payout (in production, integrate with payment gateway)
        PaymentResult result = PaymentResult.builder()
            .success(true)
            .transactionId(batchReference + "_" + booking.getId())
            .amount(hostPayout)
            .currency(DEFAULT_CURRENCY)
            .status(PaymentStatus.SUCCESS)
            .build();
        
        // Update booking with payout reference
        booking.setPaymentReference(batchReference);
        bookingRepository.save(booking);
        
        paymentSuccessCounter.increment();
        log.info("[Payment] Host payout processed: {} RSD to host {}", 
                 hostPayout, booking.getCar().getOwner().getId());
        
        return result;
    }
    
    /**
     * Process dispute payment after admin approval.
     * Called by AdminDisputeService when damage claim is approved.
     * 
     * @param claim Approved damage claim
     * @return Payment result
     */
    @Transactional
    public PaymentResult processDisputePayment(DamageClaim claim) {
        log.info("[Payment] Processing dispute payment for claim {}", claim.getId());
        
        BigDecimal amount = claim.getApprovedAmount();
        
        // Charge guest for approved damage amount
        // In production, integrate with payment gateway
        PaymentResult result = PaymentResult.builder()
            .success(true)
            .transactionId("DISPUTE_" + claim.getId() + "_" + System.currentTimeMillis())
            .amount(amount)
            .currency(DEFAULT_CURRENCY)
            .status(PaymentStatus.SUCCESS)
            .build();
        
        paymentSuccessCounter.increment();
        log.info("[Payment] Dispute payment processed: {} RSD for claim {}", amount, claim.getId());
        
        return result;
    }

    // ========== HELPERS ==========

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
    }
}




