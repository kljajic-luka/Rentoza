package org.example.rentoza.user;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * Service for owner identity verification (Serbian compliance).
 * 
 * <p>Implements Modulo 11 validation for:
 * <ul>
 *   <li>JMBG - Jedinstveni matični broj građana (13 digits) for individuals</li>
 *   <li>PIB - Poreski identifikacioni broj (9 digits) for legal entities</li>
 * </ul>
 * 
 * <p>Validation algorithms sourced from Serbian Tax Administration (Poreska Uprava).
 */
@Service
@RequiredArgsConstructor
public class OwnerVerificationService {
    
    private static final Logger log = LoggerFactory.getLogger(OwnerVerificationService.class);
    
    // Pattern for exactly 13 digits
    private static final Pattern JMBG_PATTERN = Pattern.compile("^\\d{13}$");
    
    // Pattern for exactly 9 digits
    private static final Pattern PIB_PATTERN = Pattern.compile("^\\d{9}$");
    
    // Weights for JMBG Modulo 11 algorithm
    private static final int[] JMBG_WEIGHTS = {7, 6, 5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    
    private final UserRepository userRepository;
    private final HashUtil hashUtil;
    
    // ==================== JMBG VALIDATION ====================
    
    /**
     * Validate Serbian JMBG (Personal ID).
     * 
     * <p>Format: 13 digits (DDMMYYYRRBBBK)
     * <ul>
     *   <li>DD - Day of birth</li>
     *   <li>MM - Month of birth</li>
     *   <li>YYY - Last 3 digits of birth year</li>
     *   <li>RR - Region code</li>
     *   <li>BBB - Unique combination + gender indicator</li>
     *   <li>K - Control digit (Modulo 11)</li>
     * </ul>
     * 
     * @param jmbg 13-digit personal ID
     * @return true if valid
     */
    public boolean isValidJmbg(String jmbg) {
        if (jmbg == null || !JMBG_PATTERN.matcher(jmbg).matches()) {
            return false;
        }
        
        // Calculate checksum using Modulo 11
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += Character.getNumericValue(jmbg.charAt(i)) * JMBG_WEIGHTS[i];
        }
        
        int remainder = sum % 11;
        int controlDigit;
        
        if (remainder == 1) {
            // Mathematically impossible to have remainder 1 with valid JMBG
            return false;
        } else if (remainder == 0) {
            controlDigit = 0;
        } else {
            controlDigit = 11 - remainder;
        }
        
        // Verify last digit matches calculated control digit
        return controlDigit == Character.getNumericValue(jmbg.charAt(12));
    }
    
    // ==================== PIB VALIDATION ====================
    
    /**
     * Validate Serbian PIB (Tax ID).
     * 
     * <p>Format: 9 digits
     * <p>Algorithm: Modulo 11 with recursive product method (ISO 7064)
     * 
     * @param pib 9-digit tax ID
     * @return true if valid
     */
    public boolean isValidPib(String pib) {
        if (pib == null || !PIB_PATTERN.matcher(pib).matches()) {
            return false;
        }
        
        // Calculate checksum using Modulo 11 recursive product
        int product = 10;
        for (int i = 0; i < 8; i++) {
            int digit = Character.getNumericValue(pib.charAt(i));
            int sum = (digit + product) % 10;
            if (sum == 0) sum = 10;
            product = (sum * 2) % 11;
        }
        
        int controlDigit = (11 - product) % 10;
        
        // Verify last digit matches calculated control digit
        return controlDigit == Character.getNumericValue(pib.charAt(8));
    }
    
    // ==================== OWNER REGISTRATION ====================
    
    /**
     * Submit owner verification request (Individual).
     * 
     * @param userId User ID
     * @param jmbg 13-digit personal ID
     * @param bankAccountNumber Optional bank account for payouts
     * @throws ValidationException if JMBG is invalid
     */
    @Transactional
    public void submitIndividualVerification(Long userId, String jmbg, String bankAccountNumber) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        
        if (!isValidJmbg(jmbg)) {
            throw new ValidationException("Invalid JMBG format or checksum");
        }
        
        // Block re-submission if already pending or verified
        if (user.getJmbg() != null) {
             throw new ValidationException("Zahtev za verifikaciju je već poslat.");
        }
        
        // Check if JMBG already registered to another user (using Hash)
        String jmbgHash = hashUtil.hash(jmbg);
        
        // Note: For own-profile update checks, we compare hashes too
        if (userRepository.existsByJmbgHash(jmbgHash)) {
             // If hash exists, we must check if it belongs to current user
             // BUT existsByJmbgHash returns true if ANY user has it.
             // We need to check if that user is NOT the current user.
             // Easier: Just load user by hash? Or check if current user's hash matches?
             // If user already has this JMBG (re-submission of same), it's fine.
             // But JMBG is encrypted in DB, so user.getJmbg() returns decrypted.
             // user.getJmbgHash() is the hash.
             String currentHash = user.getJmbgHash();
             if (currentHash == null || !currentHash.equals(jmbgHash)) {
                 // It's a new JMBG for this user (or first time).
                 // If it exists in DB, it belongs to someone else.
                 throw new ValidationException("JMBG already registered to another user");
             }
        }
        
        user.setOwnerType(OwnerType.INDIVIDUAL);
        user.setJmbg(jmbg); // Encrypted automatically by Converter
        user.setJmbgHash(jmbgHash);
        user.setBankAccountNumber(bankAccountNumber);
        user.setOwnerVerificationSubmittedAt(LocalDateTime.now());
        // Not verified yet - admin must verify
        user.setIsIdentityVerified(false);
        
        userRepository.save(user);
        log.info("Individual verification submitted for userId={}, JMBG masked={}", 
            userId, user.getMaskedJmbg());
    }
    
    /**
     * Submit owner verification request (Legal Entity).
     * 
     * @param userId User ID
     * @param pib 9-digit tax ID
     * @param bankAccountNumber Bank account for payouts
     * @throws ValidationException if PIB is invalid
     */
    @Transactional
    public void submitLegalEntityVerification(Long userId, String pib, String bankAccountNumber) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        
        if (!isValidPib(pib)) {
            throw new ValidationException("Invalid PIB format or checksum");
        }

        // Block re-submission if already pending or verified
        if (user.getPib() != null) {
             throw new ValidationException("Zahtev za verifikaciju je već poslat.");
        }
        
        // Check if PIB already registered to another user (using Hash)
        String pibHash = hashUtil.hash(pib);
        
        if (userRepository.existsByPibHash(pibHash)) {
             String currentHash = user.getPibHash();
             if (currentHash == null || !currentHash.equals(pibHash)) {
                 throw new ValidationException("PIB already registered to another user");
             }
        }
        
        user.setOwnerType(OwnerType.LEGAL_ENTITY);
        user.setPib(pib); // Encrypted automatically
        user.setPibHash(pibHash);
        user.setBankAccountNumber(bankAccountNumber);
        user.setOwnerVerificationSubmittedAt(LocalDateTime.now());
        // Not verified yet - admin must verify
        user.setIsIdentityVerified(false);
        
        userRepository.save(user);
        log.info("Legal entity verification submitted for userId={}, PIB masked={}", 
            userId, user.getMaskedPib());
    }
    
    // ==================== ADMIN VERIFICATION ====================
    
    /**
     * Admin verifies owner identity (after document review).
     * 
     * @param userId User to verify
     * @param admin Admin performing verification
     */
    @Transactional
    public void approveIdentityVerification(Long userId, User admin) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        
        // Validate that user has submitted required ID
        if (user.getOwnerType() == OwnerType.INDIVIDUAL && user.getJmbg() == null) {
            throw new ValidationException("User has not submitted JMBG");
        }
        if (user.getOwnerType() == OwnerType.LEGAL_ENTITY && user.getPib() == null) {
            throw new ValidationException("User has not submitted PIB");
        }
        
        user.setIsIdentityVerified(true);
        user.setIdentityVerifiedAt(LocalDateTime.now());
        user.setIdentityVerifiedBy(admin);
        
        userRepository.save(user);
        log.info("Identity verified for userId={} by adminId={}", userId, admin.getId());
    }
    
    /**
     * Admin rejects owner identity verification.
     * 
     * @param userId User to reject
     * @param reason Rejection reason
     */
    @Transactional
    public void rejectIdentityVerification(Long userId, String reason) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        
        // Clear submitted data (user must resubmit)
        user.setJmbg(null);
        user.setJmbgHash(null);
        user.setPib(null);
        user.setPibHash(null);
        user.setIsIdentityVerified(false);
        user.setIdentityVerifiedAt(null);
        user.setIdentityVerifiedBy(null);
        user.setOwnerVerificationSubmittedAt(null);
        
        userRepository.save(user);
        log.warn("Identity verification rejected for userId={}, reason={}", userId, reason);
    }
}
