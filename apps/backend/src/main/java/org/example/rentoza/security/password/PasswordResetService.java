package org.example.rentoza.security.password;

import org.example.rentoza.deprecated.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.notification.mail.MailService;
import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service handling the forgot-password and reset-password flows.
 *
 * <p>Implements Turo standard requirements:
 * <ul>
 *   <li>Forgot-password always returns generic response (email enumeration protection)</li>
 *   <li>Reset token expires after 1 hour</li>
 *   <li>One-time use token (stored as SHA-256 hash)</li>
 *   <li>Password reuse check (last 3 passwords)</li>
 *   <li>Password strength validation</li>
 * </ul>
 *
 * @since Phase 3 - Security Hardening
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final SupabaseAuthClient supabaseClient;
    private final RefreshTokenServiceEnhanced refreshTokenService;
    private final MailService mailService;          // nullable when notifications.email.enabled=false
    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.frontend-url:https://rentoza.rs}")
    private String frontendUrl;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            PasswordPolicyService passwordPolicyService,
            PasswordHistoryRepository passwordHistoryRepository,
            SupabaseAuthClient supabaseClient,
            @Autowired(required = false) RefreshTokenServiceEnhanced refreshTokenService,
            @Autowired(required = false) MailService mailService
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.supabaseClient = supabaseClient;
        this.refreshTokenService = refreshTokenService;
        this.mailService = mailService;
    }

    /**
     * Handle forgot-password request.
     *
     * <p>SECURITY: Always returns success regardless of whether the email exists.
     * This prevents email enumeration attacks (Turo standard).
     *
     * <p>If the user exists:
     * <ol>
     *   <li>Invalidate any existing reset tokens for this user</li>
     *   <li>Generate a cryptographically secure token</li>
     *   <li>Store SHA-256 hash of token in DB</li>
     *   <li>Send reset email via Supabase (or custom SMTP)</li>
     * </ol>
     *
     * @param email User's email address
     * @param clientIp IP address of the requester (audit trail)
     */
    @Transactional
    public void requestPasswordReset(String email, String clientIp) {
        log.info("Password reset requested for email={} from IP={}", maskEmail(email), clientIp);

        Optional<User> userOpt = userRepository.findByEmail(email.toLowerCase().trim());

        if (userOpt.isEmpty()) {
            // SECURITY: Log but don't reveal to client
            log.info("Password reset requested for non-existent email (no action taken)");
            return;
        }

        User user = userOpt.get();

        // Don't allow reset for OAuth-only accounts (no local password)
        if ("SUPABASE_OAUTH".equals(user.getPassword())) {
            log.info("Password reset requested for OAuth-only account (no action taken): userId={}", user.getId());
            return;
        }

        // Invalidate all existing tokens
        tokenRepository.invalidateAllForUser(user.getId(), Instant.now());

        // Generate secure random token
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        // Store hashed token
        String tokenHash = sha256(rawToken);
        Instant expiresAt = Instant.now().plusSeconds(PasswordResetToken.EXPIRY_DURATION_SECONDS);

        PasswordResetToken resetToken = new PasswordResetToken(user.getId(), tokenHash, expiresAt, clientIp);
        tokenRepository.save(resetToken);

        // Build reset link with our custom token and send via SMTP
        String resetLink = frontendUrl + "/auth/reset-password?token=" + rawToken;

        if (mailService == null) {
            log.warn("MailService unavailable (notifications.email.enabled=false) — "
                    + "password reset token created but email NOT sent for userId={}", user.getId());
        } else {
            try {
                mailService.sendTemplatedEmail(
                        email,
                        "Resetovanje lozinke - Rentoza",
                        "emails/password-reset",
                        Map.of("resetLink", resetLink)
                );
                log.info("Password reset email sent to userId={}", user.getId());
            } catch (Exception e) {
                log.error("Failed to send password reset email for userId={}: {}", user.getId(), e.getMessage());
                // Don't rethrow — we already stored the token, user can retry
            }
        }
    }

    /**
     * Reset password using a one-time token.
     *
     * @param rawToken The token from the reset link
     * @param newPassword The new password
     * @return true if reset was successful
     * @throws PasswordResetException if token is invalid/expired or password fails validation
     */
    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);

        // Find valid token
        Optional<PasswordResetToken> tokenOpt =
                tokenRepository.findValidToken(tokenHash, Instant.now());

        if (tokenOpt.isEmpty()) {
            log.warn("Invalid or expired password reset token used");
            throw new PasswordResetException("Password reset link is invalid or has expired. Please request a new one.");
        }

        PasswordResetToken resetToken = tokenOpt.get();

        // Find user
        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new PasswordResetException("User account not found."));

        // Validate password strength
        List<String> violations = passwordPolicyService.validatePasswordChange(user.getId(), newPassword);
        if (!violations.isEmpty()) {
            throw new PasswordResetException(String.join(". ", violations));
        }

        // Hash new password
        String hashedPassword = passwordEncoder.encode(newPassword);

        // Update password in Supabase (if user has a Supabase auth_uid)
        if (user.getAuthUid() != null) {
            try {
                supabaseClient.updateUserPassword(user.getAuthUid(), newPassword);
                log.info("Password updated in Supabase for userId={}", user.getId());
            } catch (SupabaseAuthException e) {
                log.error("Failed to update password in Supabase for userId={}: {}", user.getId(), e.getMessage());
                throw new PasswordResetException("Failed to update password. Please try again.");
            }
        }

        // Update local password
        user.setPassword(hashedPassword);
        user.setPasswordChangedAt(Instant.now());
        user.resetFailedLoginAttempts();

        if (refreshTokenService != null) {
            refreshTokenService.revokeAll(user.getEmail(), "PASSWORD_RESET");
        }

        userRepository.save(user);

        // Record in password history
        passwordPolicyService.recordPassword(user.getId(), hashedPassword);

        // Mark token as used (one-time)
        resetToken.markUsed();
        tokenRepository.save(resetToken);

        log.info("Password successfully reset for userId={}", user.getId());
        return true;
    }

    /**
     * SHA-256 hash for token storage.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Mask email for logging (GDPR).
     */
    private String maskEmail(String email) {
        if (email == null) return "null";
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***@" + email.substring(atIndex + 1);
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    /**
     * Exception for password reset failures.
     */
    public static class PasswordResetException extends RuntimeException {
        public PasswordResetException(String message) {
            super(message);
        }
    }
}
