package org.example.rentoza.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.JwtUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for renter/admin payment operations.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/payments/bookings/{bookingId}/reauthorize} — renter or admin reauth (P1-2)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Renter and admin payment management")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final BookingPaymentService bookingPaymentService;

    // ── Request body ──────────────────────────────────────────────────────────

    /** Request body for reauthorization endpoints. */
    public record ReauthorizeRequest(String paymentMethodId) {}

    // ── Reauth endpoint ───────────────────────────────────────────────────────

    /**
     * Re-authorize the booking payment for bookings in {@code REAUTH_REQUIRED} state.
     *
     * <h2>Who can call this</h2>
     * <ul>
     *   <li><b>Renter</b> — the guest whose card must be re-charged.</li>
     *   <li><b>Admin</b> — operational recovery via back-office tooling.</li>
     * </ul>
     *
     * <h2>When to call this</h2>
     * <p>The booking is transitioned to {@code REAUTH_REQUIRED} by the
     * {@code PaymentLifecycleScheduler.reauthExpiredBookings()} job when the existing
     * card authorization is expiring within 48 hours. The renter is notified by that
     * scheduler; this endpoint is the action they take in response.
     *
     * <p>On success the booking returns to {@code AUTHORIZED} charge lifecycle state
     * with a fresh provider authorization ID and updated expiry.
     *
     * @param bookingId       booking to reauthorize
     * @param request         contains the renter's current {@code paymentMethodId}
     * @param principal       authenticated caller (must be the renter or admin)
     * @return result map with {@code success}, {@code errorCode?} and {@code errorMessage?}
     */
    @PostMapping("/bookings/{bookingId}/reauthorize")
    @PreAuthorize("@bookingSecurity.canAccessBooking(#bookingId, authentication.principal.id) or hasRole('ADMIN')")
    @Operation(
            summary = "Reauthorize booking payment",
            description = "Re-issues a card authorization for a booking in REAUTH_REQUIRED state. "
                    + "Called by renter when their prior authorization has expired near the trip start."
    )
    public ResponseEntity<Map<String, Object>> reauthorizeBookingPayment(
            @PathVariable Long bookingId,
            @RequestBody ReauthorizeRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal) {

        log.info("[PaymentController] Reauth requested for booking {} by user {}",
                bookingId, principal != null ? principal.getId() : "unknown");

        if (request == null || request.paymentMethodId() == null || request.paymentMethodId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errorCode", "MISSING_PAYMENT_METHOD",
                    "errorMessage", "paymentMethodId is required"
            ));
        }

        PaymentProvider.PaymentResult result =
                bookingPaymentService.reauthorizeBookingPayment(bookingId, request.paymentMethodId());

        if (result.isSuccess()) {
            log.info("[PaymentController] Reauth succeeded for booking {} \u2014 new authId={}",
                    bookingId, result.getAuthorizationId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authorizationId", result.getAuthorizationId() != null ? result.getAuthorizationId() : ""
            ));
        } else if ("REDIRECT_REQUIRED".equals(result.getStatus() != null ? result.getStatus().name() : "")) {
            log.info("[PaymentController] Reauth for booking {} requires 3DS redirect: {}",
                    bookingId, result.getRedirectUrl());
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "status", "REDIRECT_REQUIRED",
                    "redirectUrl", result.getRedirectUrl() != null ? result.getRedirectUrl() : ""
            ));
        } else {
            log.warn("[PaymentController] Reauth failed for booking {}: {} ({})",
                    bookingId, result.getErrorMessage(), result.getErrorCode());
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "errorCode", result.getErrorCode() != null ? result.getErrorCode() : "UNKNOWN",
                    "errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "Reauthorization failed"
            ));
        }
    }
}
