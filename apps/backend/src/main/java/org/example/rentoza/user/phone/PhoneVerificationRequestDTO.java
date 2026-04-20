package org.example.rentoza.user.phone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * DTO za zahtev verifikacije telefona (POST /request).
 * Klijent ne salje broj — koristi se onaj iz profila ili pending.
 * Ovo telo je prazno/opciono, ali ostavljeno za buducu prosirljivost.
 */
@Data
public class PhoneVerificationRequestDTO {
    // Trenutno prazan — target telefon se cita iz User entiteta.
    // Ako bude potrebno overrideovati (npr. admin), dodati polje ovde.
}
