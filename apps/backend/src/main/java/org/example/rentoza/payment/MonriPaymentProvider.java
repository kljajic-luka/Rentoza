package org.example.rentoza.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.PostConstruct;

/**
 * H-1/H-2: Production Monri payment provider implementation.
 *
 * <h2>Monri Integration Details</h2>
 * <ul>
 *   <li>Uses Monri's REST API for authorize, capture, refund, void operations</li>
 *   <li>Supports redirect-based 3DS2 authentication flow</li>
 *   <li>Every operation carries an idempotency key in the {@code X-Idempotency-Key} header</li>
 *   <li>Response codes are mapped to the 5-state {@link ProviderOutcome} model</li>
 *   <li>Network errors → RETRYABLE_FAILURE; HTTP 4xx → TERMINAL_FAILURE (with exception for 409/429)</li>
 * </ul>
 *
 * <h2>Payout (H-8)</h2>
 * <p>Payouts use Monri's transfer/disbursement API. Since bank transfers are inherently
 * async, the payout method returns {@link ProviderOutcome#PENDING} and the actual
 * completion is confirmed via webhook callback.
 *
 * <h2>Configuration</h2>
 * <pre>
 *   app.payment.provider=MONRI
 *   app.payment.monri.api-url=https://ipgtest.monri.com  (test) / https://ipg.monri.com (prod)
 *   app.payment.monri.merchant-key=...
 *   app.payment.monri.authenticity-token=...
 *   app.payment.monri.auth-expiry-hours=120   (default 5 days)
 * </pre>
 *
 * @see MockPaymentProvider
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "MONRI")
@Slf4j
public class MonriPaymentProvider implements PaymentProvider {

    private static final String PROVIDER_NAME = "MONRI";

    // Terminal Monri response codes that must NOT be retried
    private static final Set<String> TERMINAL_RESPONSE_CODES = Set.of(
            "declined", "card_declined", "insufficient_funds", "expired_card",
            "fraud_suspected", "lost_card", "stolen_card", "invalid_card",
            "invalid_amount", "invalid_merchant", "restricted_card",
            "security_violation", "transaction_not_permitted"
    );

    // Monri status codes that indicate success
    private static final Set<String> SUCCESS_STATUSES = Set.of(
            "approved", "captured", "voided", "refunded"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.payment.monri.api-url:https://ipgtest.monri.com}")
    private String apiUrl;

    @Value("${app.payment.monri.merchant-key}")
    private String merchantKey;

    @Value("${app.payment.monri.authenticity-token}")
    private String authenticityToken;

    @Value("${app.payment.monri.auth-expiry-hours:120}")
    private int authExpiryHours;

    @Value("${app.payment.monri.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.payment.monri.read-timeout-ms:30000}")
    private int readTimeoutMs;

    public MonriPaymentProvider(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void applyTimeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        restTemplate.setRequestFactory(factory);
        log.info("[Monri] HTTP timeouts configured: connect={}ms read={}ms", connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // =========================================================================
    // AUTHORIZE
    // =========================================================================

    @Override
    public ProviderResult authorize(PaymentRequest request, String idempotencyKey) {
        log.info("[Monri] Authorize: booking={} amount={} {} ikey={}",
                request.getBookingId(), request.getAmount(), request.getCurrency(), idempotencyKey);

        try {
            Map<String, Object> txn = new java.util.HashMap<>();
            txn.put("amount", toCents(request.getAmount()));
            txn.put("currency", defaultCurrency(request.getCurrency()));
            txn.put("order_number", orderNumber(request, idempotencyKey));
            txn.put("transaction_type", "authorize");
            txn.put("token", nullSafe(request.getPaymentMethodId()));
            if (request.getClientIp() != null && !request.getClientIp().isBlank()) {
                txn.put("ip", request.getClientIp());
            }

            Map<String, Object> body = Map.of(
                    "transaction", txn,
                    "authenticity_token", authenticityToken
            );

            ResponseEntity<JsonNode> response = post("/v2/payment/new", body, idempotencyKey);
            return parseAuthResponse(response, request.getAmount());

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, "authorize", idempotencyKey);
        } catch (HttpServerErrorException e) {
            return handleHttpServerError(e, "authorize", idempotencyKey);
        } catch (ResourceAccessException e) {
            return handleNetworkError(e, "authorize", idempotencyKey);
        } catch (Exception e) {
            log.error("[Monri] Unexpected error in authorize ikey={}: {}", idempotencyKey, e.getMessage(), e);
            return ProviderResult.retryableFailure("PROVIDER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // CAPTURE
    // =========================================================================

    @Override
    public ProviderResult capture(String authorizationId, BigDecimal amount, String idempotencyKey) {
        log.info("[Monri] Capture: authId={} amount={} ikey={}", authorizationId, amount, idempotencyKey);

        try {
            Map<String, Object> body = Map.of(
                    "transaction", Map.of(
                            "amount", toCents(amount),
                            "currency", "RSD"
                    ),
                    "authenticity_token", authenticityToken
            );

            ResponseEntity<JsonNode> response = post(
                    "/v2/transactions/" + authorizationId + "/capture", body, idempotencyKey);
            return parseCaptureResponse(response, amount);

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, "capture", idempotencyKey);
        } catch (HttpServerErrorException e) {
            return handleHttpServerError(e, "capture", idempotencyKey);
        } catch (ResourceAccessException e) {
            return handleNetworkError(e, "capture", idempotencyKey);
        } catch (Exception e) {
            log.error("[Monri] Unexpected error in capture ikey={}: {}", idempotencyKey, e.getMessage(), e);
            return ProviderResult.retryableFailure("PROVIDER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // CHARGE (authorize + capture in one call)
    // =========================================================================

    @Override
    public ProviderResult charge(PaymentRequest request, String idempotencyKey) {
        log.info("[Monri] Charge (purchase): booking={} amount={} ikey={}",
                request.getBookingId(), request.getAmount(), idempotencyKey);

        try {
            Map<String, Object> txn = new java.util.HashMap<>();
            txn.put("amount", toCents(request.getAmount()));
            txn.put("currency", defaultCurrency(request.getCurrency()));
            txn.put("order_number", orderNumber(request, idempotencyKey));
            txn.put("transaction_type", "purchase");
            txn.put("token", nullSafe(request.getPaymentMethodId()));
            if (request.getClientIp() != null && !request.getClientIp().isBlank()) {
                txn.put("ip", request.getClientIp());
            }

            Map<String, Object> body = Map.of(
                    "transaction", txn,
                    "authenticity_token", authenticityToken
            );

            ResponseEntity<JsonNode> response = post("/v2/payment/new", body, idempotencyKey);
            return parsePurchaseResponse(response, request.getAmount());

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, "charge", idempotencyKey);
        } catch (HttpServerErrorException e) {
            return handleHttpServerError(e, "charge", idempotencyKey);
        } catch (ResourceAccessException e) {
            return handleNetworkError(e, "charge", idempotencyKey);
        } catch (Exception e) {
            log.error("[Monri] Unexpected error in charge ikey={}: {}", idempotencyKey, e.getMessage(), e);
            return ProviderResult.retryableFailure("PROVIDER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // REFUND
    // =========================================================================

    @Override
    public ProviderResult refund(String providerTransactionId, BigDecimal amount,
                                  String reason, String idempotencyKey) {
        log.info("[Monri] Refund: txnId={} amount={} reason='{}' ikey={}",
                providerTransactionId, amount, reason, idempotencyKey);

        try {
            Map<String, Object> body = Map.of(
                    "transaction", Map.of(
                            "amount", toCents(amount),
                            "currency", "RSD"
                    ),
                    "authenticity_token", authenticityToken
            );

            ResponseEntity<JsonNode> response = post(
                    "/v2/transactions/" + providerTransactionId + "/refund", body, idempotencyKey);
            return parseRefundResponse(response, amount);

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, "refund", idempotencyKey);
        } catch (HttpServerErrorException e) {
            return handleHttpServerError(e, "refund", idempotencyKey);
        } catch (ResourceAccessException e) {
            return handleNetworkError(e, "refund", idempotencyKey);
        } catch (Exception e) {
            log.error("[Monri] Unexpected error in refund ikey={}: {}", idempotencyKey, e.getMessage(), e);
            return ProviderResult.retryableFailure("PROVIDER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // RELEASE AUTHORIZATION (VOID)
    // =========================================================================

    @Override
    public ProviderResult releaseAuthorization(String authorizationId, String idempotencyKey) {
        log.info("[Monri] Void/Release: authId={} ikey={}", authorizationId, idempotencyKey);

        try {
            Map<String, Object> body = Map.of(
                    "authenticity_token", authenticityToken
            );

            ResponseEntity<JsonNode> response = post(
                    "/v2/transactions/" + authorizationId + "/void", body, idempotencyKey);

            JsonNode responseBody = response.getBody();
            if (responseBody == null) {
                return ProviderResult.retryableFailure("EMPTY_RESPONSE", "No response body from Monri void");
            }

            String status = safeText(responseBody, "status");
            if ("approved".equals(status) || "voided".equals(status)) {
                return ProviderResult.releaseSuccess(authorizationId);
            }

            String responseCode = safeText(responseBody, "response_code");
            return classifyFailure(responseCode, status, "release");

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, "release", idempotencyKey);
        } catch (HttpServerErrorException e) {
            return handleHttpServerError(e, "release", idempotencyKey);
        } catch (ResourceAccessException e) {
            return handleNetworkError(e, "release", idempotencyKey);
        } catch (Exception e) {
            log.error("[Monri] Unexpected error in release ikey={}: {}", idempotencyKey, e.getMessage(), e);
            return ProviderResult.retryableFailure("PROVIDER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // PAYOUT (H-8: Async transfer path)
    // =========================================================================

    /**
     * H-8: Initiate a marketplace disbursement to a host's bank account.
     *
     * <p>Real bank transfers are inherently async — the gateway accepts the request
     * and confirms completion later via webhook. This method returns
     * {@link ProviderOutcome#PENDING} on acceptance, enabling the scheduler to
     * monitor for the completion webhook.
     *
     * <p>The idempotency key ensures that crash-recovery replays don't create
     * duplicate transfers at the bank.
     */
    @Override
    public ProviderResult payout(PaymentRequest request, String idempotencyKey) {
        log.info("[Monri] Payout: userId={} amount={} {} ikey={}",
                request.getUserId(), request.getAmount(), request.getCurrency(), idempotencyKey);

        // C3: Validate Monri recipient ID — using our internal userId would route money
        // to the wrong Monri sub-account or fail with a cryptic 404.
        String recipientId = request.getRecipientId();
        if (recipientId == null || recipientId.isBlank()) {
            log.error("[Monri] REJECTED payout: no Monri recipientId for userId={} booking={}. "
                    + "Host must complete Monri onboarding before payouts are possible.",
                    request.getUserId(), request.getBookingId());
            return ProviderResult.terminalFailure("RECIPIENT_NOT_ONBOARDED",
                    "Host has not completed Monri onboarding — monriRecipientId is missing");
        }

        try {
            Map<String, Object> body = Map.of(
                    "payout", Map.of(
                            "amount", toCents(request.getAmount()),
                            "currency", defaultCurrency(request.getCurrency()),
                            "recipient_id", recipientId,
                            "order_number", "payout_" + request.getBookingId(),
                            "description", nullSafe(request.getDescription())
                    ),
                    "authenticity_token", authenticityToken
            );

            ResponseEntity<JsonNode> response = post("/v2/payouts", body, idempotencyKey);
            JsonNode responseBody = response.getBody();

            if (responseBody == null) {
                return ProviderResult.retryableFailure("EMPTY_RESPONSE", "No response body from Monri payout");
            }

            String status = safeText(responseBody, "status");
            String payoutId = safeText(responseBody, "id");

            // H-8: Bank transfers return "pending" or "processing" status
            if ("approved".equals(status) || "completed".equals(status)) {
                // Immediate success (rare for bank transfers)
                return ProviderResult.builder()
                        .outcome(ProviderOutcome.SUCCESS)
                        .providerTransactionId(payoutId)
                        .amount(request.getAmount())
                        .currency(defaultCurrency(request.getCurrency()))
                        .rawProviderStatus(status)
                        .build();
            } else if ("pending".equals(status) || "processing".equals(status)) {
                // Expected path: async confirmation via webhook
                return ProviderResult.builder()
                        .outcome(ProviderOutcome.PENDING)
                        .providerTransactionId(payoutId)
                        .amount(request.getAmount())
                        .rawProviderStatus(status)
                        .build();
            }

            String responseCode = safeText(responseBody, "response_code");
            return classifyFailure(responseCode, status, "payout");

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, "payout", idempotencyKey);
        } catch (HttpServerErrorException e) {
            return handleHttpServerError(e, "payout", idempotencyKey);
        } catch (ResourceAccessException e) {
            return handleNetworkError(e, "payout", idempotencyKey);
        } catch (Exception e) {
            log.error("[Monri] Unexpected error in payout ikey={}: {}", idempotencyKey, e.getMessage(), e);
            return ProviderResult.retryableFailure("PROVIDER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // RESPONSE PARSERS
    // =========================================================================

    private ProviderResult parseAuthResponse(ResponseEntity<JsonNode> response, BigDecimal amount) {
        JsonNode body = response.getBody();
        if (body == null) {
            return ProviderResult.retryableFailure("EMPTY_RESPONSE", "No response body from Monri authorize");
        }

        String status = safeText(body, "status");
        String responseCode = safeText(body, "response_code");

        // 3DS2 redirect required
        if ("action_required".equals(status) || body.has("acs_url")) {
            String redirectUrl = safeText(body, "acs_url");
            String sessionToken = safeText(body, "authenticity_token");
            if (redirectUrl == null || redirectUrl.isBlank()) {
                redirectUrl = safeText(body, "redirect_url");
            }
            return ProviderResult.redirectRequired(
                    redirectUrl, sessionToken,
                    Instant.now().plus(15, ChronoUnit.MINUTES));
        }

        if ("approved".equals(status)) {
            String authId = safeText(body, "id");
            Instant expiresAt = Instant.now().plus(authExpiryHours, ChronoUnit.HOURS);
            return ProviderResult.authSuccess(authId, amount, expiresAt);
        }

        return classifyFailure(responseCode, status, "authorize");
    }

    private ProviderResult parseCaptureResponse(ResponseEntity<JsonNode> response, BigDecimal amount) {
        JsonNode body = response.getBody();
        if (body == null) {
            return ProviderResult.retryableFailure("EMPTY_RESPONSE", "No response body from Monri capture");
        }

        String status = safeText(body, "status");
        if ("approved".equals(status) || "captured".equals(status)) {
            String txnId = safeText(body, "id");
            return ProviderResult.captureSuccess(txnId, amount);
        }

        String responseCode = safeText(body, "response_code");
        return classifyFailure(responseCode, status, "capture");
    }

    private ProviderResult parsePurchaseResponse(ResponseEntity<JsonNode> response, BigDecimal amount) {
        JsonNode body = response.getBody();
        if (body == null) {
            return ProviderResult.retryableFailure("EMPTY_RESPONSE", "No response body from Monri purchase");
        }

        String status = safeText(body, "status");

        // 3DS2 redirect
        if ("action_required".equals(status) || body.has("acs_url")) {
            String redirectUrl = safeText(body, "acs_url");
            if (redirectUrl == null || redirectUrl.isBlank()) {
                redirectUrl = safeText(body, "redirect_url");
            }
            String sessionToken = safeText(body, "authenticity_token");
            return ProviderResult.redirectRequired(
                    redirectUrl, sessionToken,
                    Instant.now().plus(15, ChronoUnit.MINUTES));
        }

        if ("approved".equals(status)) {
            String txnId = safeText(body, "id");
            return ProviderResult.captureSuccess(txnId, amount);
        }

        String responseCode = safeText(body, "response_code");
        return classifyFailure(responseCode, status, "charge");
    }

    private ProviderResult parseRefundResponse(ResponseEntity<JsonNode> response, BigDecimal amount) {
        JsonNode body = response.getBody();
        if (body == null) {
            return ProviderResult.retryableFailure("EMPTY_RESPONSE", "No response body from Monri refund");
        }

        String status = safeText(body, "status");
        if ("approved".equals(status) || "refunded".equals(status)) {
            String refundId = safeText(body, "id");
            return ProviderResult.refundSuccess(refundId, amount);
        }

        String responseCode = safeText(body, "response_code");
        return classifyFailure(responseCode, status, "refund");
    }

    // =========================================================================
    // FAILURE CLASSIFICATION (H-5 contract mapping)
    // =========================================================================

    /**
     * Map Monri response codes to the 5-state ProviderOutcome model.
     *
     * <p>Terminal failures (card_declined, insufficient_funds, etc.) produce
     * TERMINAL_FAILURE. Unknown or ambiguous codes default to RETRYABLE_FAILURE
     * with audit logging — this ensures we never silently swallow a new error code.
     */
    private ProviderResult classifyFailure(String responseCode, String rawStatus, String operation) {
        if (responseCode == null) responseCode = "unknown";

        if (TERMINAL_RESPONSE_CODES.contains(responseCode.toLowerCase())) {
            String normalized = normalizeErrorCode(responseCode);
            log.warn("[Monri] Terminal failure in {}: code={} status={}", operation, responseCode, rawStatus);
            return ProviderResult.terminalFailure(normalized,
                    "Monri terminal: " + responseCode + " (" + rawStatus + ")");
        }

        // Default: treat as retryable — this is the safe default for unknown codes
        log.warn("[Monri] Retryable failure in {}: code={} status={} — treating as retryable. "
                + "If this code should be terminal, add it to TERMINAL_RESPONSE_CODES.",
                operation, responseCode, rawStatus);
        return ProviderResult.retryableFailure("GATEWAY_ERROR",
                "Monri retryable: " + responseCode + " (" + rawStatus + ")");
    }

    /**
     * Normalize Monri response codes to the application's error code convention.
     * E.g., "card_declined" → "CARD_DECLINED", "insufficient_funds" → "INSUFFICIENT_FUNDS".
     */
    private String normalizeErrorCode(String monriCode) {
        if (monriCode == null) return "UNKNOWN";
        return monriCode.toUpperCase().replace("-", "_");
    }

    // =========================================================================
    // HTTP ERROR HANDLERS
    // =========================================================================

    private ProviderResult handleHttpClientError(HttpClientErrorException e, String operation, String ikey) {
        int status = e.getStatusCode().value();

        // 409 Conflict: idempotency key reuse — operation already processed
        if (status == 409) {
            log.info("[Monri] 409 Conflict in {} ikey={} — idempotent duplicate", operation, ikey);
            // Try to parse the response for the original result
            try {
                JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
                String txnId = safeText(body, "id");
                if (txnId != null) {
                    // P1-FIX: Set both providerTransactionId and providerAuthorizationId.
                    // For authorize operations, the caller reads providerAuthorizationId
                    // to populate booking auth references. Missing it breaks capture/release.
                    return ProviderResult.builder()
                            .outcome(ProviderOutcome.SUCCESS)
                            .providerTransactionId(txnId)
                            .providerAuthorizationId(txnId)
                            .rawProviderStatus("IDEMPOTENT_DUPLICATE")
                            .build();
                }
            } catch (Exception parseEx) {
                log.debug("[Monri] Could not parse 409 response body: {}", parseEx.getMessage());
            }
            return ProviderResult.retryableFailure("IDEMPOTENT_CONFLICT",
                    "409 Conflict — retry with same idempotency key");
        }

        // 429 Too Many Requests: rate limit — retryable
        if (status == 429) {
            log.warn("[Monri] 429 Rate Limited in {} ikey={}", operation, ikey);
            return ProviderResult.retryableFailure("RATE_LIMITED",
                    "Monri rate limit exceeded — retry after backoff");
        }

        // Other 4xx: terminal (bad request, unauthorized, forbidden, not found)
        log.error("[Monri] HTTP {} in {} ikey={}: {}", status, operation, ikey, e.getMessage());
        return ProviderResult.terminalFailure("HTTP_" + status, e.getMessage());
    }

    private ProviderResult handleHttpServerError(HttpServerErrorException e, String operation, String ikey) {
        int status = e.getStatusCode().value();
        log.error("[Monri] HTTP {} server error in {} ikey={}: {}", status, operation, ikey, e.getMessage());
        return ProviderResult.retryableFailure("GATEWAY_SERVER_ERROR",
                "Monri server error " + status + " — retryable");
    }

    private ProviderResult handleNetworkError(ResourceAccessException e, String operation, String ikey) {
        log.error("[Monri] Network error in {} ikey={}: {}", operation, ikey, e.getMessage());
        return ProviderResult.retryableFailure("NETWORK_ERROR",
                "Monri unreachable: " + e.getMessage());
    }

    // =========================================================================
    // HTTP HELPERS
    // =========================================================================

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "WP3-v2 " + merchantKey);
        headers.set("X-Idempotency-Key", idempotencyKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(apiUrl + path, HttpMethod.POST, entity, JsonNode.class);
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    /**
     * Convert BigDecimal amount to cents (Monri expects integer amounts in minor units).
     *
     * <p>Uses {@code long} to avoid integer overflow for amounts > ~21.4M RSD.
     * Rounds HALF_UP to handle amounts with more than 2 decimal places gracefully.
     */
    private static long toCents(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static String defaultCurrency(String currency) {
        return currency != null && !currency.isBlank() ? currency : "RSD";
    }

    private static String orderNumber(PaymentRequest request, String idempotencyKey) {
        return request.getOrderReference() != null ? request.getOrderReference()
                : "booking_" + request.getBookingId() + "_" + idempotencyKey.substring(0, Math.min(16, idempotencyKey.length()));
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static String safeText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }
}
