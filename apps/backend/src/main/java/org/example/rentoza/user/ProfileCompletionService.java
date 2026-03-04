package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.network.TrustedProxyIpExtractor;
import org.example.rentoza.user.dto.CompleteProfileRequestDTO;
import org.example.rentoza.user.dto.CompleteProfileResponseDTO;
import org.example.rentoza.util.HashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

import org.example.rentoza.config.timezone.SerbiaTimeZone;

/**
 * Service for handling profile completion after Google OAuth registration.
 * 
 * <p>This service validates role-specific required fields, encrypts sensitive data,
 * generates uniqueness hashes, and updates user registration status from INCOMPLETE to ACTIVE.
 * 
 * <p><b>Field Requirements by Role:</b>
 * <ul>
 *   <li><b>USER:</b> phone, dateOfBirth (21+), driverLicenseNumber, driverLicenseExpiryDate, driverLicenseCountry</li>
 *   <li><b>OWNER (INDIVIDUAL):</b> phone, dateOfBirth (21+), jmbg, agreements, bankAccountNumber (optional)</li>
 *   <li><b>OWNER (LEGAL_ENTITY):</b> phone, dateOfBirth (21+), pib, agreements, bankAccountNumber (required)</li>
 * </ul>
 * 
 * <p><b>Security:</b>
 * <ul>
 *   <li>JMBG, PIB, bankAccountNumber, driverLicenseNumber are stored encrypted via JPA @Convert</li>
 *   <li>Hashes are generated for uniqueness checks (jmbgHash, pibHash, driverLicenseNumberHash)</li>
 *   <li>Duplicate ID detection returns 409 Conflict</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileCompletionService {

    private static final int MINIMUM_AGE = 21;

    private final UserRepository userRepository;
    private final HashUtil hashUtil;
    private final TrustedProxyIpExtractor ipExtractor;
    private final AppProperties appProperties;

    /**
     * Complete user profile with required fields based on their role.
     * 
     * @param userId The authenticated user's ID
     * @param request The profile completion data
     * @return Response with updated user info and ACTIVE status
     * @throws EntityNotFoundException if user not found
     * @throws ProfileCompletionException if validation fails or duplicate ID detected
     */
    @Transactional
    public CompleteProfileResponseDTO completeProfile(Long userId, CompleteProfileRequestDTO request,
                                                       HttpServletRequest httpRequest) {
        log.info("Profile completion requested for userId={}", userId);

        // 1. Find user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        // 2. Idempotency check - if already ACTIVE, just return success
        if (user.getRegistrationStatus() == RegistrationStatus.ACTIVE) {
            log.info("User {} already has ACTIVE status, returning success (idempotent)", userId);
            return buildResponse(user, "Profil je već kompletiran.");
        }

        // 3. Validate age (21+ requirement)
        validateAge(request.getDateOfBirth());

        // 4. Role-specific validation and field population
        List<String> validationErrors = new ArrayList<>();

        if (user.getRole() == Role.USER) {
            validateAndPopulateUserFields(user, request, validationErrors);
        } else if (user.getRole() == Role.OWNER) {
            validateAndPopulateOwnerFields(user, request, validationErrors);
        } else {
            throw new ProfileCompletionException("INVALID_ROLE", 
                    "Profile completion is only available for USER and OWNER roles");
        }

        // 5. If validation errors, throw with details
        if (!validationErrors.isEmpty()) {
            throw new ValidationException(validationErrors);
        }

        // 5b. PHASE 4: Persist owner consent provenance after successful validation
        if (user.getRole() == Role.OWNER) {
            Instant now = Instant.now();
            user.setHostAgreementAcceptedAt(now);
            user.setVehicleInsuranceConfirmedAt(now);
            user.setVehicleRegistrationConfirmedAt(now);
            if (httpRequest != null) {
                user.setConsentIp(ipExtractor.extractClientIp(httpRequest));
                String ua = httpRequest.getHeader("User-Agent");
                user.setConsentUserAgent(ua != null && ua.length() > 500 ? ua.substring(0, 500) : ua);
            }
            user.setConsentPolicyVersion(appProperties.getConsent().getPolicyVersion());
            user.setConsentPolicyHash(appProperties.getConsent().getPolicyHash());
        }

        // 6. Set common fields
        user.setPhone(normalizePhone(request.getPhone()));
        user.setDateOfBirth(request.getDateOfBirth());
        user.setDobVerified(false); // Will be verified when license is OCR'd

        // 7. Update registration status to ACTIVE
        user.setRegistrationStatus(RegistrationStatus.ACTIVE);

        // 8. Save user
        User savedUser = userRepository.save(user);
        log.info("Profile completed successfully for userId={}, role={}, status=ACTIVE", 
                userId, user.getRole());

        return buildResponse(savedUser, "Profil je uspešno kompletiran. Dobrodošli!");
    }

    /**
     * Validate and populate USER (renter) specific fields.
     *
     * License metadata (number, expiry, country) is no longer collected at profile completion.
     * It is sourced exclusively from OCR during document processing at /verify-license,
     * keeping the OAuth and email registration paths symmetric.
     */
    private void validateAndPopulateUserFields(User user, CompleteProfileRequestDTO request,
                                                List<String> errors) {
        // Profile completion captures basic profile only; verification starts after document upload.
        user.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);

        log.debug("USER fields populated: driverLicenseStatus=NOT_STARTED (license metadata deferred to document upload)");
    }

    /**
     * Validate and populate OWNER specific fields.
     */
    private void validateAndPopulateOwnerFields(User user, CompleteProfileRequestDTO request, 
                                                 List<String> errors) {
        // Required: ownerType
        if (request.getOwnerType() == null) {
            // Check if user already has ownerType set (from registration)
            if (user.getOwnerType() == null) {
                errors.add("Tip vlasništva (fizičko lice ili firma) je obavezan");
                return; // Can't continue without knowing owner type
            }
        } else {
            user.setOwnerType(request.getOwnerType());
        }

        OwnerType ownerType = user.getOwnerType();

        if (ownerType == OwnerType.INDIVIDUAL) {
            validateIndividualOwner(user, request, errors);
        } else if (ownerType == OwnerType.LEGAL_ENTITY) {
            validateLegalEntityOwner(user, request, errors);
        }

        // Validate agreements (required for all owners)
        validateOwnerAgreements(request, errors);
    }

    /**
     * Validate INDIVIDUAL owner (Fizičko lice) - requires JMBG.
     */
    private void validateIndividualOwner(User user, CompleteProfileRequestDTO request, 
                                          List<String> errors) {
        // Required: JMBG
        if (isBlank(request.getJmbg())) {
            errors.add("JMBG je obavezan za fizička lica");
        } else if (!request.getJmbg().matches("^[0-9]{13}$")) {
            errors.add("JMBG mora sadržati tačno 13 cifara");
        } else {
            // Check for duplicate JMBG
            String jmbgHash = hashUtil.hash(request.getJmbg());
            if (userRepository.existsByJmbgHashAndIdNot(jmbgHash, user.getId())) {
                throw new DuplicateIdentifierException("JMBG", 
                        "Ovaj JMBG je već registrovan na drugom nalogu");
            }
            user.setJmbg(request.getJmbg());
            user.setJmbgHash(jmbgHash);
        }

        // Optional but recommended: bankAccountNumber
        if (!isBlank(request.getBankAccountNumber())) {
            if (!request.getBankAccountNumber().matches("^RS[0-9]{22}$")) {
                errors.add("Neispravan IBAN format. Mora početi sa RS i imati 22 cifre");
            } else {
                user.setBankAccountNumber(request.getBankAccountNumber());
            }
        }

        log.debug("INDIVIDUAL OWNER fields populated: jmbg=***, bankAccount={}", 
                user.getBankAccountNumber() != null);
    }

    /**
     * Validate LEGAL_ENTITY owner (Firma) - requires PIB and bank account.
     */
    private void validateLegalEntityOwner(User user, CompleteProfileRequestDTO request, 
                                           List<String> errors) {
        // Required: PIB
        if (isBlank(request.getPib())) {
            errors.add("PIB je obavezan za pravna lica");
        } else if (!request.getPib().matches("^[0-9]{9}$")) {
            errors.add("PIB mora sadržati tačno 9 cifara");
        } else {
            // Check for duplicate PIB
            String pibHash = hashUtil.hash(request.getPib());
            if (userRepository.existsByPibHashAndIdNot(pibHash, user.getId())) {
                throw new DuplicateIdentifierException("PIB", 
                        "Ovaj PIB je već registrovan na drugom nalogu");
            }
            user.setPib(request.getPib());
            user.setPibHash(pibHash);
        }

        // Required for LEGAL_ENTITY: bankAccountNumber
        if (isBlank(request.getBankAccountNumber())) {
            errors.add("Broj bankovnog računa (IBAN) je obavezan za pravna lica");
        } else if (!request.getBankAccountNumber().matches("^RS[0-9]{22}$")) {
            errors.add("Neispravan IBAN format. Mora početi sa RS i imati 22 cifre");
        } else {
            user.setBankAccountNumber(request.getBankAccountNumber());
        }

        log.debug("LEGAL_ENTITY OWNER fields populated: pib=***, bankAccount={}", 
                user.getBankAccountNumber() != null);
    }

    /**
     * Validate owner agreement checkboxes.
     */
    private void validateOwnerAgreements(CompleteProfileRequestDTO request, List<String> errors) {
        if (!Boolean.TRUE.equals(request.getAgreesToHostAgreement())) {
            errors.add("Morate prihvatiti uslove korišćenja za vlasnike");
        }
        if (!Boolean.TRUE.equals(request.getConfirmsVehicleInsurance())) {
            errors.add("Morate potvrditi da vaše vozilo ima važeće osiguranje");
        }
        if (!Boolean.TRUE.equals(request.getConfirmsVehicleRegistration())) {
            errors.add("Morate potvrditi da je vozilo registrovano na vaše ime");
        }
    }

    /**
     * Validate age is at least 21 years.
     */
    private void validateAge(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new ProfileCompletionException("INVALID_DOB", "Datum rođenja je obavezan");
        }

        int age = Period.between(dateOfBirth, SerbiaTimeZone.today()).getYears();
        if (age < MINIMUM_AGE) {
            throw new ProfileCompletionException("AGE_REQUIREMENT", 
                    String.format("Morate imati najmanje %d godina. Vaša starost: %d", MINIMUM_AGE, age));
        }
    }

    /**
     * Normalize phone number (remove non-digits).
     */
    private String normalizePhone(String phone) {
        if (phone == null) return null;
        return phone.replaceAll("[^0-9]", "");
    }

    /**
     * Check if string is blank.
     */
    private boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    /**
     * Build response DTO from user entity.
     */
    private CompleteProfileResponseDTO buildResponse(User user, String message) {
        int age = user.getDateOfBirth() != null 
                ? Period.between(user.getDateOfBirth(), SerbiaTimeZone.today()).getYears() 
                : 0;

        return CompleteProfileResponseDTO.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .dateOfBirth(user.getDateOfBirth())
                .calculatedAge(age)
                .role(user.getRole())
                .registrationStatus(user.getRegistrationStatus())
                .ownerType(user.getOwnerType())
                .hasBankAccount(user.getBankAccountNumber() != null && !user.getBankAccountNumber().isBlank())
                .hasDriverLicense(user.getDriverLicenseNumber() != null && !user.getDriverLicenseNumber().isBlank())
                .driverLicenseExpiryDate(user.getDriverLicenseExpiryDate())
                .driverLicenseCountry(user.getDriverLicenseCountry())
                .message(message)
                .build();
    }

    // ========== CUSTOM EXCEPTIONS ==========

    /**
     * Exception for profile completion business rule violations.
     */
    public static class ProfileCompletionException extends RuntimeException {
        private final String errorCode;

        public ProfileCompletionException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * Exception for duplicate identifier (JMBG, PIB, driver license).
     */
    public static class DuplicateIdentifierException extends RuntimeException {
        private final String identifierType;

        public DuplicateIdentifierException(String identifierType, String message) {
            super(message);
            this.identifierType = identifierType;
        }

        public String getIdentifierType() {
            return identifierType;
        }
    }

    /**
     * Exception for multiple validation errors.
     */
    public static class ValidationException extends RuntimeException {
        private final List<String> errors;

        public ValidationException(List<String> errors) {
            super("Validation failed: " + String.join("; ", errors));
            this.errors = errors;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
