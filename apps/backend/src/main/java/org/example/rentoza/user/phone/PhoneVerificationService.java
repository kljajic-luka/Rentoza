package org.example.rentoza.user.phone;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.trust.PhoneVerificationState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Servis za verifikaciju telefona korisnika putem Supabase OTP toka.
 *
 * <p>Tok:
 * <ol>
 *   <li>Korisnik poziva /request — Supabase salje SMS OTP</li>
 *   <li>Korisnik poziva /confirm sa OTP kodom</li>
 *   <li>Telefon se promovise u kanonski, phoneVerifiedAt se postavlja</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PhoneVerificationService {

    private final UserRepository userRepository;
    private final SupabaseAuthClient supabaseAuthClient;

    /**
     * Vraca status verifikacije telefona.
     */
    @Transactional(readOnly = true)
    public PhoneVerificationStatusDTO getStatus(User user) {
        PhoneVerificationState state = resolveState(user);
        return PhoneVerificationStatusDTO.builder()
                .phone(user.getPhone())
                .pendingPhone(user.getPendingPhone())
                .phoneVerified(user.isPhoneVerified())
                .phoneVerifiedAt(user.getPhoneVerifiedAt())
                .phoneVerificationState(state)
                .canBook(user.isPhoneVerified())
                .canPublish(user.isPhoneVerified())
                .build();
    }

    /**
     * Pokrece OTP verifikaciju. Bira pendingPhone ako postoji, inace phone.
     * Proverava jedinstvenost broja pre slanja.
     */
    @Transactional
    public void requestVerification(User user, String accessToken) {
        String target = user.getPhoneVerificationTarget();
        if (target == null || target.isBlank()) {
            throw new ValidationException("Nema telefona za verifikaciju. Unesite broj u profilu.");
        }

        // Provera jedinstvenosti: ne sme da postoji drugi korisnik sa istim brojem
        // (ni kao phone ni kao pendingPhone)
        if (userRepository.existsByPhoneAndIdNot(target, user.getId())) {
            throw new ValidationException("Ovaj broj telefona je vec u upotrebi.");
        }
        if (userRepository.existsByPendingPhoneAndIdNot(target, user.getId())) {
            throw new ValidationException("Ovaj broj telefona je vec u upotrebi.");
        }

        supabaseAuthClient.updateUserPhone(accessToken, formatToE164(target));
        log.info("Phone OTP requested: userId={}, target={}", user.getId(), maskPhone(target));
    }

    /**
     * Potvrdjuje OTP kod i promovise telefon.
     */
    @Transactional
    public PhoneVerificationStatusDTO confirmOtp(User user, String accessToken, String otpCode) {
        String target = user.getPhoneVerificationTarget();
        if (target == null || target.isBlank()) {
            throw new ValidationException("Nema aktivnog zahteva za verifikaciju.");
        }

        supabaseAuthClient.verifyPhoneOtp(accessToken, formatToE164(target), otpCode);

        // Promocija: ako je pending, postaje kanonski
        if (user.getPendingPhone() != null && !user.getPendingPhone().isBlank()) {
            user.setPhone(user.getPendingPhone());
            user.setPendingPhone(null);
            user.setPendingPhoneUpdatedAt(null);
        }
        user.setPhoneVerifiedAt(SerbiaTimeZone.now());
        userRepository.save(user);

        log.info("Phone verified: userId={}, phone={}", user.getId(), maskPhone(user.getPhone()));
        return getStatus(user);
    }

    /**
     * Ponovo salje OTP za trenutni target.
     */
    @Transactional(readOnly = true)
    public void resendOtp(User user, String accessToken) {
        String target = user.getPhoneVerificationTarget();
        if (target == null || target.isBlank()) {
            throw new ValidationException("Nema aktivnog zahteva za verifikaciju.");
        }
        supabaseAuthClient.resendPhoneOtp(accessToken, formatToE164(target));
        log.info("Phone OTP resent: userId={}, target={}", user.getId(), maskPhone(target));
    }

    // ==================== PRIVATE HELPERS ====================

    private PhoneVerificationState resolveState(User user) {
        if (user.isPhoneVerified()) {
            if (user.getPendingPhone() != null && !user.getPendingPhone().isBlank()) {
                return PhoneVerificationState.PENDING_CHANGE;
            }
            return PhoneVerificationState.VERIFIED;
        }
        return PhoneVerificationState.UNVERIFIED;
    }

    private String formatToE164(String phone) {
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) {
            return digits;
        }
        // Srpski brojevi: 06x... -> +381 6x...
        if (digits.startsWith("0")) {
            return "+381" + digits.substring(1);
        }
        // Vec bez +, pretpostavka da je medjunarodni
        return "+" + digits;
    }

    private String normalizeDigits(String phone) {
        return phone.replaceAll("[^0-9]", "");
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return phone.substring(0, 2) + "****" + phone.substring(phone.length() - 2);
    }
}
