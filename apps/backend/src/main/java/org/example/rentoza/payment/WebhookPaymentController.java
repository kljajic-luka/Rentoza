package org.example.rentoza.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Receives asynchronous payment provider webhook callbacks.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>No authentication — the endpoint is intentionally public so that payment providers
 *       can call it without credentials. Security is enforced via HMAC signature
 *       verification inside {@link ProviderEventService}.</li>
 *   <li>Always returns {@code 200 OK} so the provider does not retry on processing
 *       errors. Duplicate events are silently acknowledged.</li>
 *   <li>The raw body is forwarded as-is without parsing — the service layer owns
 *       interpretation logic.</li>
 * </ul>
 *
 * <h2>Expected Headers</h2>
 * <ul>
 *   <li>{@code X-Monri-Event-Id} — provider-assigned globally unique event ID (required)</li>
 *   <li>{@code X-Monri-Event-Type} — event type string (required)</li>
 *   <li>{@code X-Monri-Booking-Id} — booking ID if determinable by the provider (optional)</li>
 *   <li>{@code X-Monri-Signature} — HMAC-SHA256 of the request body (optional in dev/test)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/webhooks/payment")
@RequiredArgsConstructor
@Slf4j
public class WebhookPaymentController {

    private final ProviderEventService providerEventService;

    /** Mirrors ProviderEventService config — used to enforce event-ID requirement when auth is active. */
    @Value("${app.payment.webhook.secret:}")
    private String webhookSecret;

    /**
     * Entry point for all provider webhook notifications.
     *
     * @param eventId       {@code X-Monri-Event-Id} header
     * @param eventType     {@code X-Monri-Event-Type} header
     * @param bookingIdStr  {@code X-Monri-Booking-Id} header (numeric, optional)
     * @param authId        {@code X-Monri-Auth-Id} header — provider authorization ID
     *                      (optional but required for accurate transaction-scoped routing)
     * @param signature     {@code X-Monri-Signature} header (HMAC, optional)
     * @param rawBody       raw JSON request body
     * @return 200 OK always (provider must receive 2xx to stop retrying)
     */
    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader(value = "X-Monri-Event-Id",  required = false) String eventId,
            @RequestHeader(value = "X-Monri-Event-Type", required = false) String eventType,
            @RequestHeader(value = "X-Monri-Booking-Id", required = false) String bookingIdStr,
            @RequestHeader(value = "X-Monri-Auth-Id",   required = false) String authId,
            @RequestHeader(value = "X-Monri-Signature",  required = false) String signature,
            @RequestBody String rawBody) {

        // P0-FIX: When HMAC verification is active, a missing event-id cannot be safely
        // deduplicated and is structurally invalid — reject it rather than synthesising an ID
        // that would bypass deduplication on replays.
        if (eventId == null || eventId.isBlank()) {
            if (webhookSecret != null && !webhookSecret.isBlank()) {
                log.error("[Webhook] REJECTED: Missing X-Monri-Event-Id while signature verification is active");
                return ResponseEntity.ok(Map.of(
                        "status",    "rejected",
                        "reason",    "MISSING_EVENT_ID",
                        "processed", false
                ));
            }
            // Dev/test mode: synthesise an ID so the handler can proceed without a real provider.
            eventId = "synthetic_" + UUID.randomUUID().toString().replace("-", "");
            log.warn("[Webhook] Missing X-Monri-Event-Id (dev/test only) — assigned synthetic id={}", eventId);
        }

        // Parse optional booking id
        Long bookingId = null;
        if (bookingIdStr != null && !bookingIdStr.isBlank()) {
            try {
                bookingId = Long.parseLong(bookingIdStr);
            } catch (NumberFormatException ex) {
                log.warn("[Webhook] Invalid X-Monri-Booking-Id '{}' — ignoring", bookingIdStr);
            }
        }

        log.info("[Webhook] Received event={} type={} bookingId={} authId={}", eventId, eventType, bookingId, authId);

        try {
            boolean processed = providerEventService.ingestEvent(
                    eventId, eventType, bookingId, authId, rawBody, signature);

            return ResponseEntity.ok(Map.of(
                    "status",    "ok",
                    "eventId",   eventId,
                    "processed", processed
            ));
        } catch (Exception ex) {
            // Never surface 5xx to the provider — it would trigger unbounded retries
            log.error("[Webhook] Unhandled error processing event={}: {}", eventId, ex.getMessage(), ex);
            return ResponseEntity.ok(Map.of(
                    "status",    "error",
                    "eventId",   eventId,
                    "processed", false
            ));
        }
    }
}
