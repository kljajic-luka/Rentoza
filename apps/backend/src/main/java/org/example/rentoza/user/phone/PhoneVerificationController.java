package org.example.rentoza.user.phone;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Kontroler za verifikaciju telefona korisnika putem SMS OTP-a.
 *
 * <p>Endpointi:
 * <ul>
 *   <li>GET  /status  — trenutno stanje verifikacije</li>
 *   <li>POST /request — pokreni OTP slanje</li>
 *   <li>POST /confirm — potvrdi OTP kod</li>
 *   <li>POST /resend  — ponovo posalji OTP</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users/me/phone-verification")
@PreAuthorize("isAuthenticated()")
@Slf4j
@RequiredArgsConstructor
public class PhoneVerificationController {

    private final PhoneVerificationService phoneVerificationService;
    private final UserService userService;
    private final CurrentUser currentUser;

    @GetMapping("/status")
    public ResponseEntity<PhoneVerificationStatusDTO> getStatus() {
        User user = resolveCurrentUser();
        return ResponseEntity.ok(phoneVerificationService.getStatus(user));
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> requestVerification(HttpServletRequest request) {
        User user = resolveCurrentUser();
        String accessToken = extractAccessToken(request);
        phoneVerificationService.requestVerification(user, accessToken);
        return ResponseEntity.ok(Map.of(
                "message", "OTP kod je poslat na vas telefon.",
                "messageSr", "OTP kod je poslat na vas telefon."
        ));
    }

    @PostMapping("/confirm")
    public ResponseEntity<PhoneVerificationStatusDTO> confirmOtp(
            @Valid @RequestBody PhoneVerificationConfirmDTO dto,
            HttpServletRequest request) {
        User user = resolveCurrentUser();
        String accessToken = extractAccessToken(request);
        PhoneVerificationStatusDTO result = phoneVerificationService.confirmOtp(
                user, accessToken, dto.getOtpCode());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/resend")
    public ResponseEntity<Map<String, String>> resendOtp(HttpServletRequest request) {
        User user = resolveCurrentUser();
        String accessToken = extractAccessToken(request);
        phoneVerificationService.resendOtp(user, accessToken);
        return ResponseEntity.ok(Map.of(
                "message", "OTP kod je ponovo poslat.",
                "messageSr", "OTP kod je ponovo poslat."
        ));
    }

    // ==================== PRIVATE HELPERS ====================

    private User resolveCurrentUser() {
        Long userId = currentUser.id();
        return userService.getUserById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronadjen."));
    }

    /**
     * Izvlaci access_token iz HttpOnly cookie-ja.
     * Supabase ga koristi za identifikaciju korisnika pri phone update/verify.
     */
    private String extractAccessToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (CookieConstants.ACCESS_TOKEN.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        // Fallback: Authorization header (za mobilne klijente)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "Access token nije dostupan. Prijavite se ponovo.");
    }
}
