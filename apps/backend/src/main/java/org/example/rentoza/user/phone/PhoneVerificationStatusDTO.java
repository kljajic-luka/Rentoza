package org.example.rentoza.user.phone;

import lombok.Builder;
import lombok.Data;
import org.example.rentoza.user.trust.PhoneVerificationState;

import java.time.LocalDateTime;

/**
 * DTO za status verifikacije telefona.
 */
@Data
@Builder
public class PhoneVerificationStatusDTO {

    private String phone;
    private String pendingPhone;
    private boolean phoneVerified;
    private LocalDateTime phoneVerifiedAt;
    private PhoneVerificationState phoneVerificationState;
    /** Indikator: da li korisnik moze da bukira vozilo */
    private boolean canBook;
    /** Indikator: da li korisnik moze da objavi listing */
    private boolean canPublish;
}
