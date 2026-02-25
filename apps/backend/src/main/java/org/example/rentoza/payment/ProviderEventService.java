package org.example.rentoza.payment;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.payment.PaymentTransaction.PaymentOperation;
import org.example.rentoza.payment.PaymentTransaction.PaymentTransactionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Processes incoming provider webhook events.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li>Deduplicate: if {@link ProviderEvent#getProviderEventId()} already exists → return
 *       immediately (ack to stop provider retries).</li>
 *   <li>Persist the raw event record first (before processing), so a crash during processing
 *       still leaves an audit trail.</li>
 *   <li>Process: transition the associated booking / payment-transaction to the next state.</li>
 *   <li>Mark the event as processed (or set {@link ProviderEvent#setProcessingError}).</li>
 * </ol>
 *
 * <h2>Supported event types</h2>
 * <ul>
 *   <li>{@code PAYMENT_CONFIRMED} — 3DS2 redirect completed or async payment confirmed.
 *       Transitions REDIRECT_REQUIRED / PENDING_CONFIRMATION transactions → SUCCEEDED,
 *       and sets booking's {@link ChargeLifecycleStatus} → AUTHORIZED.</li>
 *   <li>{@code PAYMENT_FAILED} — provider reports hard failure. Transitions transaction
 *       → FAILED_TERMINAL.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProviderEventService {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final ProviderEventRepository eventRepository;
    private final PaymentTransactionRepository txRepository;
    private final BookingRepository bookingRepository;

    @Value("${app.payment.webhook.secret}")
    private String webhookSecret;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /**
     * B5: Fail-fast on missing or dev-mock webhook HMAC secret in production.
     * Without a real HMAC secret, ALL webhook events bypass signature verification,
     * allowing forged payment-confirmed webhooks to mark bookings as paid.
     */
    @PostConstruct
    void validateWebhookSecret() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            if (activeProfile != null && activeProfile.contains("prod")) {
                throw new IllegalStateException(
                        "FATAL: PAYMENT_WEBHOOK_SECRET must be configured in production. " +
                        "Refusing to start without webhook HMAC verification. " +
                        "Set the app.payment.webhook.secret property via environment variable " +
                        "PAYMENT_WEBHOOK_SECRET in Cloud Run secrets.");
            }
            log.warn("[SECURITY] Webhook HMAC secret is blank — signature verification disabled. " +
                     "This MUST NOT reach production.");
        } else if ("dev-mock-webhook-secret-not-for-production".equals(webhookSecret)) {
            if (activeProfile != null && activeProfile.contains("prod")) {
                throw new IllegalStateException(
                        "FATAL: PAYMENT_WEBHOOK_SECRET is set to the dev mock value in production. " +
                        "Set a real HMAC secret via environment variable PAYMENT_WEBHOOK_SECRET.");
            }
            log.warn("[SECURITY] Using dev mock webhook secret — this MUST NOT reach production.");
        }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Ingest a provider webhook event.
     *
     * @param providerEventId         unique event identifier assigned by the provider
     * @param eventType               provider-defined event type string
     * @param bookingId               associated booking (may be {@code null} if not determinable)
     * @param providerAuthorizationId provider authorization ID — when present, only the
     *                                {@link PaymentTransaction} matching this ID is updated
     *                                (P0-4: transaction-scoped webhook processing)
     * @param rawPayload              full raw JSON body
     * @param signatureHeader         optional HMAC signature header from the provider
     * @return {@code true} if the event was freshly processed; {@code false} if it was a duplicate
     */
    @Transactional
    public boolean ingestEvent(String providerEventId,
                               String eventType,
                               Long bookingId,
                               String providerAuthorizationId,
                               String rawPayload,
                               String signatureHeader) {

        // ── 1. Deduplication ──────────────────────────────────────────────────
        if (eventRepository.existsByProviderEventId(providerEventId)) {
            log.info("[Webhook] Duplicate event — skipping providerEventId={} type={}", providerEventId, eventType);
            return false;
        }

        // ── 2. Verify HMAC signature ──────────────────────────────────────────
        // P0-FIX: When webhookSecret is configured, a MISSING signature is treated as
        // a forged request and rejected — not silently processed unauthenticated.
        boolean sigVerified = false;
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            // Secret is configured — signature header is MANDATORY.
            if (signatureHeader == null || signatureHeader.isBlank()) {
                log.warn("[Webhook] SIGNATURE REQUIRED but absent — rejecting providerEventId={} type={}",
                        providerEventId, eventType);
                ProviderEvent rejected = ProviderEvent.builder()
                        .providerEventId(providerEventId)
                        .eventType(eventType)
                        .bookingId(bookingId)
                        .rawPayload(rawPayload)
                        .signatureHeader(null)
                        .signatureVerified(false)
                        .processingError("SIGNATURE_MISSING")
                        .processedAt(Instant.now())
                        .build();
                eventRepository.save(rejected);
                return false;
            }
            sigVerified = verifyHmac(rawPayload, signatureHeader);
            if (!sigVerified) {
                log.warn("[Webhook] SIGNATURE MISMATCH — rejecting providerEventId={} type={}", providerEventId, eventType);
                // Store the event for audit but do NOT process it
                ProviderEvent rejected = ProviderEvent.builder()
                        .providerEventId(providerEventId)
                        .eventType(eventType)
                        .bookingId(bookingId)
                        .rawPayload(rawPayload)
                        .signatureHeader(signatureHeader)
                        .signatureVerified(false)
                        .processingError("SIGNATURE_VERIFICATION_FAILED")
                        .processedAt(Instant.now())
                        .build();
                eventRepository.save(rejected);
                return false;
            }
        }

        // ── 3. Persist raw event record (audit trail before processing) ───────
        ProviderEvent event = ProviderEvent.builder()
                .providerEventId(providerEventId)
                .eventType(eventType)
                .bookingId(bookingId)
                .rawPayload(rawPayload)
                .signatureHeader(signatureHeader)
                .signatureVerified(sigVerified)
                .build();
        event = eventRepository.save(event);

        // ── 4. Process ────────────────────────────────────────────────────────
        String error = null;
        try {
            dispatch(eventType, bookingId, providerAuthorizationId);
        } catch (Exception ex) {
            log.error("[Webhook] Processing error for providerEventId={}: {}", providerEventId, ex.getMessage(), ex);
            error = ex.getMessage();
        }

        // ── 5. Mark processed ─────────────────────────────────────────────────
        event.setProcessedAt(Instant.now());
        event.setProcessingError(error);
        eventRepository.save(event);

        return error == null;
    }

    // ── Dispatcher ───────────────────────────────────────────────────────────

    private void dispatch(String eventType, Long bookingId, String providerAuthorizationId) {
        if (eventType == null) {
            log.warn("[Webhook] Null event type — ignoring");
            return;
        }
        switch (eventType.toUpperCase()) {
            case "PAYMENT_CONFIRMED", "AUTHORIZATION_CONFIRMED" ->
                    handlePaymentConfirmed(bookingId, providerAuthorizationId);
            case "PAYMENT_FAILED", "AUTHORIZATION_FAILED" ->
                    handlePaymentFailed(bookingId, providerAuthorizationId);
            default ->
                    log.info("[Webhook] Unhandled event type '{}' — stored for audit only", eventType);
        }
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    /**
     * Transitions the matching REDIRECT_REQUIRED or PENDING_CONFIRMATION payment transaction
     * to SUCCEEDED, and updates the booking's chargeLifecycleStatus to AUTHORIZED.
     *
     * <p><b>P0-4:</b> When {@code providerAuthorizationId} is provided the handler targets
     * exactly the one {@link PaymentTransaction} whose {@code providerAuthId} matches —
     * preventing a webhook from accidentally flipping a deposit-authorization transaction
     * that shares the same booking but is a different financial event.
     * Falls back to the booking-scoped scan when the header is absent (backward compat).
     */
    private void handlePaymentConfirmed(Long bookingId, String providerAuthorizationId) {
        if (bookingId == null) {
            log.warn("[Webhook] PAYMENT_CONFIRMED without bookingId — cannot process");
            return;
        }

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + bookingId));

        // P0-4: When the provider supplies an authorization ID, locate the exact transaction.
        boolean any = false;
        if (providerAuthorizationId != null && !providerAuthorizationId.isBlank()) {
            Optional<PaymentTransaction> txOpt = txRepository.findByProviderAuthId(providerAuthorizationId);
            if (txOpt.isPresent()) {
                PaymentTransaction tx = txOpt.get();
                if ((tx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                        || tx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION)
                        && tx.getOperation() == PaymentOperation.AUTHORIZE) {
                    tx.setStatus(PaymentTransactionStatus.SUCCEEDED);
                    txRepository.save(tx);
                    log.info("[Webhook] Booking {} tx {} (authId={}) → SUCCEEDED (PAYMENT_CONFIRMED)",
                            bookingId, tx.getId(), providerAuthorizationId);
                    any = true;
                } else {
                    log.info("[Webhook] PAYMENT_CONFIRMED for booking {} — tx {} already in {} ({})",
                            bookingId, tx.getId(), tx.getStatus(), tx.getOperation());
                    any = true; // tx exists but is already in a non-pending state — not an error
                }
            } else {
                log.warn("[Webhook] PAYMENT_CONFIRMED for booking {} — no tx found for providerAuthId={}; "
                        + "falling back to booking-scoped scan", bookingId, providerAuthorizationId);
            }
        }

        // P0-FIX: Fail closed when webhook secret is configured (production mode)
        // and no auth-id was provided. The booking-scoped fallback can mutate the wrong
        // AUTHORIZE transaction when multiple auth flows exist for one booking.
        if (!any) {
            if (webhookSecret != null && !webhookSecret.isBlank()) {
                log.error("[Webhook] PAYMENT_CONFIRMED for booking {} — no providerAuthorizationId provided "
                        + "and webhook secret is configured (prod mode). Refusing booking-scoped fallback "
                        + "to prevent wrong-transaction mutation.", bookingId);
                throw new IllegalStateException(
                        "Webhook PAYMENT_CONFIRMED missing providerAuthorizationId — fail closed in prod");
            }
            // Dev/test fallback: scan all AUTHORIZE transactions for this booking
            log.warn("[Webhook] PAYMENT_CONFIRMED for booking {} — no auth-id header; "
                    + "using booking-scoped fallback (DEV ONLY)", bookingId);
            List<PaymentTransaction> txList = txRepository.findByBookingId(bookingId);
            for (PaymentTransaction tx : txList) {
                if ((tx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                        || tx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION)
                        && tx.getOperation() == PaymentOperation.AUTHORIZE) {
                    tx.setStatus(PaymentTransactionStatus.SUCCEEDED);
                    txRepository.save(tx);
                    log.info("[Webhook] Booking {} tx {} {} → SUCCEEDED (PAYMENT_CONFIRMED, booking-scoped fallback)",
                            bookingId, tx.getId(), tx.getOperation());
                    any = true;
                }
            }
        }

        if (!any) {
            log.warn("[Webhook] PAYMENT_CONFIRMED for booking {} but no matching tx found", bookingId);
        }

        // Advance booking's charge lifecycle to AUTHORIZED.
        // P0-FIX: Also allow REAUTH_REQUIRED → AUTHORIZED so that a successful reauth
        // 3DS redirect webhook restores the booking to AUTHORIZED (unblocking capture).
        ChargeLifecycleStatus current = booking.getChargeLifecycleStatus();
        if (current == null || current == ChargeLifecycleStatus.PENDING
                || current == ChargeLifecycleStatus.REAUTH_REQUIRED) {
            transitionCharge(booking, ChargeLifecycleStatus.AUTHORIZED, "PAYMENT_CONFIRMED webhook");
            booking.setPaymentStatus("AUTHORIZED");
            // On successful reauth, refresh the auth expiry window so capture can proceed.
            if (current == ChargeLifecycleStatus.REAUTH_REQUIRED) {
                booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(168 * 3600L)); // 7-day default
                log.info("[Webhook] Booking {} REAUTH_REQUIRED → AUTHORIZED (auth expiry refreshed)", bookingId);
            } else {
                log.info("[Webhook] Booking {} chargeLifecycleStatus → AUTHORIZED", bookingId);
            }
            bookingRepository.save(booking);
        } else {
            log.info("[Webhook] Booking {} already in state {} — not overwriting on PAYMENT_CONFIRMED",
                    bookingId, current);
        }
    }

    /**
     * Transitions the matching REDIRECT_REQUIRED or PENDING_CONFIRMATION payment transaction
     * to FAILED_TERMINAL.
     *
     * <p><b>P0-4:</b> Uses {@code providerAuthorizationId} for precise transaction targeting
     * when available; falls back to booking-scoped AUTHORIZE scan otherwise.
     */
    private void handlePaymentFailed(Long bookingId, String providerAuthorizationId) {
        if (bookingId == null) {
            log.warn("[Webhook] PAYMENT_FAILED without bookingId — cannot process");
            return;
        }

        boolean any = false;
        if (providerAuthorizationId != null && !providerAuthorizationId.isBlank()) {
            Optional<PaymentTransaction> txOpt = txRepository.findByProviderAuthId(providerAuthorizationId);
            if (txOpt.isPresent()) {
                PaymentTransaction tx = txOpt.get();
                if ((tx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                        || tx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION)
                        && tx.getOperation() == PaymentOperation.AUTHORIZE) {
                    tx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
                    txRepository.save(tx);
                    log.warn("[Webhook] Booking {} tx {} (authId={}) → FAILED_TERMINAL (PAYMENT_FAILED)",
                            bookingId, tx.getId(), providerAuthorizationId);
                    any = true;
                }
            } else {
                log.warn("[Webhook] PAYMENT_FAILED for booking {} — no tx found for providerAuthId={}; "
                        + "falling back to booking-scoped scan", bookingId, providerAuthorizationId);
            }
        }

        // P0-FIX: Fail closed when webhook secret is configured (production mode)
        if (!any) {
            if (webhookSecret != null && !webhookSecret.isBlank()) {
                log.error("[Webhook] PAYMENT_FAILED for booking {} — no providerAuthorizationId provided "
                        + "and webhook secret is configured (prod mode). Refusing booking-scoped fallback.",
                        bookingId);
                throw new IllegalStateException(
                        "Webhook PAYMENT_FAILED missing providerAuthorizationId — fail closed in prod");
            }
            // Dev/test fallback: booking-scoped scan
            log.warn("[Webhook] PAYMENT_FAILED for booking {} — no auth-id header; "
                    + "using booking-scoped fallback (DEV ONLY)", bookingId);
            List<PaymentTransaction> txList = txRepository.findByBookingId(bookingId);
            for (PaymentTransaction tx : txList) {
                if ((tx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                        || tx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION)
                        && tx.getOperation() == PaymentOperation.AUTHORIZE) {
                    tx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
                    txRepository.save(tx);
                    log.warn("[Webhook] Booking {} tx {} {} → FAILED_TERMINAL (PAYMENT_FAILED, booking-scoped fallback)",
                            bookingId, tx.getId(), tx.getOperation());
                }
            }
        }

        Optional<Booking> bookingOpt = bookingRepository.findById(bookingId);
        bookingOpt.ifPresent(booking -> {
            ChargeLifecycleStatus current = booking.getChargeLifecycleStatus();
            if (current == ChargeLifecycleStatus.PENDING) {
                transitionCharge(booking, ChargeLifecycleStatus.CAPTURE_FAILED, "PAYMENT_FAILED webhook");
                booking.setPaymentStatus("PAYMENT_FAILED");
                bookingRepository.save(booking);
                log.warn("[Webhook] Booking {} chargeLifecycleStatus → FAILED (PAYMENT_FAILED webhook)", bookingId);
            }
        });
    }

    // ── Lifecycle transition guard ──────────────────────────────────────────────

    /**
     * Safely transition charge lifecycle status with state-machine validation.
     * Logs a warning on invalid transitions but still proceeds — consistent with
     * BookingPaymentService's guard pattern for full observability.
     */
    private void transitionCharge(Booking booking, ChargeLifecycleStatus target, String context) {
        ChargeLifecycleStatus current = booking.getChargeLifecycleStatus();
        if (current != null && !current.canTransitionTo(target)) {
            log.warn("[Webhook] Invalid charge lifecycle transition {} → {} in {}, booking {}",
                    current, target, context, booking.getId());
        }
        booking.setChargeLifecycleStatus(target);
    }

    // ── HMAC verification ─────────────────────────────────────────────────────

    /**
     * Verifies that {@code signatureHeader} matches the HMAC-SHA256 of {@code payload}
     * using the configured {@code app.payment.webhook.secret}.
     */
    private boolean verifyHmac(String payload, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedHex = HexFormat.of().formatHex(computed);
            // Support both "sha256=<hex>" prefix (Monri style) and bare hex
            String receivedHex = signatureHeader.startsWith("sha256=")
                    ? signatureHeader.substring(7)
                    : signatureHeader;
            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    receivedHex.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("[Webhook] HMAC verification error: {}", e.getMessage(), e);
            return false;
        }
    }
}
