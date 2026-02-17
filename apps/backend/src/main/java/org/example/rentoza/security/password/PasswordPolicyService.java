package org.example.rentoza.security.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service enforcing password policies to Turo standard:
 * <ul>
 *   <li>Minimum 8 characters</li>
 *   <li>At least 1 uppercase letter</li>
 *   <li>At least 1 number</li>
 *   <li>At least 1 special character</li>
 *   <li>Cannot reuse last 3 passwords</li>
 * </ul>
 *
 * @since Phase 3 - Security Hardening
 */
@Service
public class PasswordPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicyService.class);

    /** Number of previous passwords to check for reuse */
    private static final int PASSWORD_HISTORY_COUNT = 3;

    private static final int MIN_LENGTH = 8;
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("[^A-Za-z0-9]");

    private final PasswordHistoryRepository passwordHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordPolicyService(PasswordHistoryRepository passwordHistoryRepository) {
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * Validate password strength against Turo standard.
     *
     * @param password Plain-text password to validate
     * @return List of violation messages (empty = valid)
     */
    public List<String> validatePasswordStrength(String password) {
        List<String> violations = new ArrayList<>();

        if (password == null || password.length() < MIN_LENGTH) {
            violations.add("Password must be at least " + MIN_LENGTH + " characters");
        }

        if (password != null) {
            if (!UPPERCASE_PATTERN.matcher(password).find()) {
                violations.add("Password must contain at least one uppercase letter");
            }
            if (!LOWERCASE_PATTERN.matcher(password).find()) {
                violations.add("Password must contain at least one lowercase letter");
            }
            if (!DIGIT_PATTERN.matcher(password).find()) {
                violations.add("Password must contain at least one number");
            }
            if (!SPECIAL_PATTERN.matcher(password).find()) {
                violations.add("Password must contain at least one special character");
            }
        }

        return violations;
    }

    /**
     * Check if password was recently used by this user.
     *
     * @param userId User's ID
     * @param newPassword Plain-text new password
     * @return true if password was recently used
     */
    public boolean isPasswordReused(Long userId, String newPassword) {
        List<PasswordHistory> recentPasswords =
                passwordHistoryRepository.findLastNPasswords(userId, PASSWORD_HISTORY_COUNT);

        for (PasswordHistory history : recentPasswords) {
            if (passwordEncoder.matches(newPassword, history.getPasswordHash())) {
                log.info("Password reuse detected for userId={}", userId);
                return true;
            }
        }

        return false;
    }

    /**
     * Record a password in history for reuse prevention.
     *
     * @param userId User's ID
     * @param hashedPassword BCrypt-hashed password
     */
    @Transactional
    public void recordPassword(Long userId, String hashedPassword) {
        PasswordHistory entry = new PasswordHistory(userId, hashedPassword);
        passwordHistoryRepository.save(entry);
        log.debug("Password recorded in history for userId={}", userId);
    }

    /**
     * Validate password strength and check reuse in one call.
     *
     * @param userId User's ID (for reuse check)
     * @param newPassword Plain-text new password
     * @return List of violation messages (empty = valid)
     */
    public List<String> validatePasswordChange(Long userId, String newPassword) {
        List<String> violations = validatePasswordStrength(newPassword);

        if (violations.isEmpty() && isPasswordReused(userId, newPassword)) {
            violations.add("Cannot reuse your last " + PASSWORD_HISTORY_COUNT + " passwords");
        }

        return violations;
    }
}
