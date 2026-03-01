package org.example.rentoza.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Mock 3DS2 Access Control Server (ACS) endpoint.
 *
 * <p>Simulates the bank challenge page that a real payment gateway (e.g. Monri) would
 * redirect the customer to for Strong Customer Authentication (SCA). Only active
 * when the mock payment provider is enabled — never loaded in production.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /mock/acs/challenge?token=…} — renders a minimal HTML challenge page
 *       with Approve / Decline buttons.</li>
 *   <li>{@code POST /mock/acs/complete} — fires a synthetic
 *       {@link ProviderEventService#ingestEvent webhook event} and redirects the browser
 *       back to the frontend.</li>
 * </ul>
 *
 * <p><b>NEVER USE IN PRODUCTION.</b>
 */
@RestController
@RequestMapping("/mock/acs")
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "MOCK")
@Slf4j
class MockAcsController {

    private final MockPaymentProvider mockProvider;
    private final ProviderEventService providerEventService;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    MockAcsController(MockPaymentProvider mockProvider,
                      ProviderEventService providerEventService) {
        this.mockProvider = mockProvider;
        this.providerEventService = providerEventService;
    }

    /**
     * Render a minimal HTML 3DS challenge page.
     *
     * <p>The page shows the booking context and two buttons: Approve (green) and
     * Decline (red). Each button submits a form POST to {@code /mock/acs/complete}.
     */
    @GetMapping("/challenge")
    public ResponseEntity<String> challenge(@RequestParam String token) {
        MockScaSession session = mockProvider.loadScaSession(token);
        if (session == null) {
            log.warn("[MockAcs] Unknown SCA token: {}", token);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorPage("Invalid or expired 3DS challenge token."));
        }

        log.info("[MockAcs] Serving challenge page for token={} booking={} authId={}",
                token, session.bookingId, session.providerAuthorizationId);

        String html = challengePage(token, session.bookingId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /**
     * Process the user's challenge decision (approve or decline).
     *
     * <p>Fires a synthetic webhook via {@link ProviderEventService#ingestEvent} using
     * the same HMAC-signed payload format the real webhook controller expects, then
     * redirects the user's browser back to the frontend booking page.
     */
    @PostMapping("/complete")
    public ResponseEntity<Void> complete(@RequestParam String token,
                                         @RequestParam String action) {
        MockScaSession session = mockProvider.loadScaSession(token);
        if (session == null) {
            log.warn("[MockAcs] Unknown SCA token on complete: {}", token);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        boolean approved = "approve".equalsIgnoreCase(action);
        String eventType = approved ? "PAYMENT_CONFIRMED" : "PAYMENT_FAILED";
        String eventId = "mock_3ds_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String payload = String.format(
                "{\"type\":\"%s\",\"booking_id\":%d,\"auth_id\":\"%s\",\"timestamp\":\"%s\",\"source\":\"mock_acs\"}",
                eventType, session.bookingId, session.providerAuthorizationId, Instant.now().toString());
        String signature = mockProvider.computeHmac(payload);

        log.info("[MockAcs] 3DS challenge {} for booking={} authId={} token={}",
                approved ? "APPROVED" : "DECLINED",
                session.bookingId, session.providerAuthorizationId, token);

        try {
            providerEventService.ingestEvent(
                    eventId,
                    eventType,
                    session.bookingId,
                    session.providerAuthorizationId,
                    payload,
                    signature);
        } catch (Exception e) {
            log.error("[MockAcs] Failed to fire synthetic webhook for booking={}: {}",
                    session.bookingId, e.getMessage(), e);
        }

        // Redirect browser back to frontend booking page
        String status = approved ? "confirmed" : "failed";
        String redirectUrl = frontendUrl + "/bookings/" + session.bookingId
                + "?payment=" + status;

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    // ── HTML templates ───────────────────────────────────────────────────────

    private static String challengePage(String token, Long bookingId) {
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>3D Secure Verifikacija — Rentoza (Test)</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            background: #f0f2f5;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            min-height: 100vh;
                        }
                        .card {
                            background: white;
                            border-radius: 12px;
                            box-shadow: 0 4px 24px rgba(0,0,0,0.1);
                            padding: 40px;
                            max-width: 420px;
                            width: 90%%;
                            text-align: center;
                        }
                        .badge {
                            display: inline-block;
                            background: #fff3cd;
                            color: #856404;
                            padding: 4px 12px;
                            border-radius: 4px;
                            font-size: 12px;
                            font-weight: 600;
                            letter-spacing: 0.5px;
                            margin-bottom: 16px;
                        }
                        h1 { font-size: 20px; color: #1a1a2e; margin-bottom: 8px; }
                        .subtitle { color: #666; font-size: 14px; margin-bottom: 24px; }
                        .booking-ref {
                            background: #f8f9fa;
                            border: 1px solid #e9ecef;
                            border-radius: 8px;
                            padding: 12px;
                            margin-bottom: 24px;
                            font-size: 13px;
                            color: #495057;
                        }
                        .buttons { display: flex; gap: 12px; }
                        .btn {
                            flex: 1;
                            padding: 14px 24px;
                            border: none;
                            border-radius: 8px;
                            font-size: 16px;
                            font-weight: 600;
                            cursor: pointer;
                            transition: opacity 0.2s;
                        }
                        .btn:hover { opacity: 0.85; }
                        .btn-approve { background: #28a745; color: white; }
                        .btn-decline { background: #dc3545; color: white; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="badge">TEST OKRUZENJE</div>
                        <h1>3D Secure Verifikacija</h1>
                        <p class="subtitle">Potvrdite transakciju za nastavak</p>
                        <div class="booking-ref">Rezervacija #%d</div>
                        <div class="buttons">
                            <form method="POST" action="/mock/acs/complete" style="flex:1;display:flex;">
                                <input type="hidden" name="token" value="%s">
                                <input type="hidden" name="action" value="approve">
                                <button type="submit" class="btn btn-approve" style="flex:1;">Odobri</button>
                            </form>
                            <form method="POST" action="/mock/acs/complete" style="flex:1;display:flex;">
                                <input type="hidden" name="token" value="%s">
                                <input type="hidden" name="action" value="decline">
                                <button type="submit" class="btn btn-decline" style="flex:1;">Odbij</button>
                            </form>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(bookingId, token, token);
    }

    private static String errorPage(String message) {
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>Greska - 3DS</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            background: #f0f2f5;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            min-height: 100vh;
                        }
                        .error {
                            background: white;
                            border-radius: 12px;
                            box-shadow: 0 4px 24px rgba(0,0,0,0.1);
                            padding: 40px;
                            max-width: 420px;
                            text-align: center;
                            color: #dc3545;
                        }
                    </style>
                </head>
                <body><div class="error"><h2>%s</h2></div></body>
                </html>
                """.formatted(message);
    }
}
