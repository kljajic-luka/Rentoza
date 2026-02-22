package org.example.rentoza.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDate;

/**
 * Booking eligibility check response DTO.
 * 
 * <p>Quick check used by frontend before showing booking form.
 * Returns simple yes/no with reason if blocked.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingEligibilityDTO {
    
    /**
     * Whether user can book a car.
     */
    private boolean eligible;
    
    /**
     * If not eligible, reason code for frontend handling.
     */
    private EligibilityBlockReason blockReason;
    
    /**
     * Human-readable message for display.
     */
    private String message;
    
    /**
     * Serbian message for UI.
     */
    private String messageSr;
    
    /**
     * If license expiry is the issue, when it expires.
     */
    private LocalDate licenseExpiryDate;
    
    /**
     * If verification pending, estimated wait time.
     */
    private String estimatedWaitTime;
    
    /**
     * Action URL for remediation (e.g., /profile/verification).
     */
    private String actionUrl;
    
    /**
     * Action button label.
     */
    private String actionLabel;
    
    // ==================== FACTORY METHODS ====================
    
    /**
     * User is eligible to book.
     */
    public static BookingEligibilityDTO eligible() {
        return BookingEligibilityDTO.builder()
            .eligible(true)
            .message("Ready to book")
            .messageSr("Spremni za rezervaciju")
            .build();
    }
    
    /**
     * User needs to start verification.
     */
    public static BookingEligibilityDTO needsVerification() {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.LICENSE_NOT_VERIFIED)
            .message("Driver's license verification required before booking")
            .messageSr("Vozačka dozvola mora biti verifikovana pre rezervacije")
            .actionUrl("/verify-license")
            .actionLabel("Verifikujte vozačku dozvolu")
            .build();
    }
    
    /**
     * User verification is pending review.
     */
    public static BookingEligibilityDTO pendingReview(String waitTime) {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.VERIFICATION_PENDING)
            .message("Your license verification is being reviewed")
            .messageSr("Vaša vozačka dozvola se pregleda")
            .estimatedWaitTime(waitTime)
            .actionUrl("/verify-license")
            .actionLabel("Pogledajte status")
            .build();
    }
    
    /**
     * User verification was rejected.
     */
    public static BookingEligibilityDTO rejected(String reason) {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.VERIFICATION_REJECTED)
            .message("Your license verification was rejected: " + reason)
            .messageSr("Vaša verifikacija je odbijena: " + reason)
            .actionUrl("/verify-license")
            .actionLabel("Ponovo podnesite dokumente")
            .build();
    }
    
    /**
     * User's license is expired.
     */
    public static BookingEligibilityDTO licenseExpired(LocalDate expiryDate) {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.LICENSE_EXPIRED)
            .message("Your driver's license has expired")
            .messageSr("Vaša vozačka dozvola je istekla")
            .licenseExpiryDate(expiryDate)
            .actionUrl("/verify-license")
            .actionLabel("Ažurirajte vozačku dozvolu")
            .build();
    }
    
    /**
     * User's license will expire before trip end.
     */
    public static BookingEligibilityDTO licenseExpiresDuringTrip(LocalDate expiryDate, LocalDate tripEnd) {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.LICENSE_EXPIRES_DURING_TRIP)
            .message("Your license expires (" + expiryDate + ") before trip end (" + tripEnd + ")")
            .messageSr("Vaša dozvola ističe (" + expiryDate + ") pre kraja putovanja (" + tripEnd + ")")
            .licenseExpiryDate(expiryDate)
            .actionUrl("/verify-license")
            .actionLabel("Ažurirajte vozačku dozvolu")
            .build();
    }
    
    /**
     * User is suspended (fraud/abuse).
     */
    public static BookingEligibilityDTO suspended() {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.ACCOUNT_SUSPENDED)
            .message("Your verification is suspended. Please contact support.")
            .messageSr("Vaša verifikacija je suspendovana. Kontaktirajte podršku.")
            .actionUrl("/support")
            .actionLabel("Kontaktirajte podršku")
            .build();
    }
    
    /**
     * User doesn't meet age requirement.
     */
    public static BookingEligibilityDTO underAge(int requiredAge) {
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.UNDER_AGE)
            .message("Drivers must be at least " + requiredAge + " years old")
            .messageSr("Vozači moraju imati najmanje " + requiredAge + " godina")
            .build();
    }
    
    /**
     * User's license tenure is less than required minimum (2 years).
     * H1 FIX: Platform spec requires minimum 2 years of license tenure.
     */
    public static BookingEligibilityDTO licenseTenureTooShort(int currentTenureMonths, int requiredTenureMonths, LocalDate eligibleFrom) {
        String eligibleDateStr = eligibleFrom != null 
            ? ". Eligible from: " + eligibleFrom 
            : "";
        String eligibleDateStrSr = eligibleFrom != null 
            ? ". Možete rezervisati od: " + eligibleFrom 
            : "";
            
        return BookingEligibilityDTO.builder()
            .eligible(false)
            .blockReason(EligibilityBlockReason.LICENSE_TENURE_TOO_SHORT)
            .message(String.format(
                "Driver's license must be at least %d years old. Current tenure: %d months%s",
                requiredTenureMonths / 12, currentTenureMonths, eligibleDateStr))
            .messageSr(String.format(
                "Vozačka dozvola mora biti stara najmanje %d godine. Trenutni staž: %d meseci%s",
                requiredTenureMonths / 12, currentTenureMonths, eligibleDateStrSr))
            .actionUrl("/verify-license")
            .actionLabel("Pogledajte detalje")
            .build();
    }
    
    /**
     * Reasons why booking might be blocked.
     */
    public enum EligibilityBlockReason {
        /** License not verified yet */
        LICENSE_NOT_VERIFIED,
        
        /** Verification submitted, awaiting review */
        VERIFICATION_PENDING,
        
        /** Verification was rejected */
        VERIFICATION_REJECTED,
        
        /** License has expired */
        LICENSE_EXPIRED,
        
        /** License expires during requested trip */
        LICENSE_EXPIRES_DURING_TRIP,
        
        /** Account is suspended */
        ACCOUNT_SUSPENDED,
        
        /** User is under minimum age */
        UNDER_AGE,
        
        /** User is banned */
        USER_BANNED,
        
        /** License tenure is less than 2 years (H1 spec requirement) */
        LICENSE_TENURE_TOO_SHORT
    }
}
