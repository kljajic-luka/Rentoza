package org.example.rentoza.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.example.rentoza.car.Car;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.review.Review;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import org.example.rentoza.util.AttributeEncryptor;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "email"),
                @UniqueConstraint(columnNames = "phone")
        },
        indexes = {
                @Index(name = "idx_user_email", columnList = "email"),
                @Index(name = "idx_user_phone", columnList = "phone"),
                @Index(name = "idx_user_google_id", columnList = "google_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    public static final String GOOGLE_PLACEHOLDER_LAST_NAME = "GooglePlaceholder";
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Size(min = 3, max = 50)
    @Column(nullable = false)
    private String firstName;

    @Size(min = 3, max = 50)
    @Column(nullable = false)
    private String lastName;

    @Email
    @Column(unique = true, nullable = false)
    private String email;

    // Note: Password is stored as BCrypt hash, so don't validate regex pattern here.
    // Pattern validation should be applied to the input DTO before hashing.
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Column(name = "auth_provider", nullable = false, length = 20)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @Column(name = "google_id", unique = true, length = 100)
    private String googleId;

    /**
     * Supabase Auth UUID - links to auth.users(id).
     * CRITICAL: Required for RLS policies. Set during Supabase registration.
     * RLS policies check: WHERE auth_uid = auth.uid()
     */
    @Column(name = "auth_uid", unique = true)
    private java.util.UUID authUid;

    @Pattern(regexp = "^[0-9]{8,15}$")
    @Column(unique = true)
    private String phone;

    /**
     * User's date of birth.
     * ENTERPRISE-GRADE: Store DOB instead of age - age changes every birthday.
     * 
     * <p>Data sources (in priority order):
     * <ol>
     *   <li>Driver license OCR extraction (verified, trusted)</li>
     *   <li>Manual entry during profile edit (self-reported)</li>
     *   <li>Registration form (optional)</li>
     * </ol>
     * 
     * <p>Used for:
     * <ul>
     *   <li>Booking eligibility (must be 21+ for most cars)</li>
     *   <li>Insurance premium calculation</li>
     *   <li>Risk scoring</li>
     * </ul>
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;
    
    /**
     * Whether DOB was verified from official document (license OCR).
     * Verified DOB cannot be changed by user, only by admin.
     */
    @Column(name = "dob_verified", nullable = false)
    private boolean dobVerified = false;

    /**
     * @deprecated Use {@link #getAge()} instead which calculates from {@link #dateOfBirth}.
     * Kept for backward compatibility during migration.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @Min(value = 18, message = "Age must be at least 18")
    @Max(value = 120, message = "Age must be less than 120")
    @Column(name = "age")
    private Integer age;

    @Column(length = 500)
    private String avatarUrl;

    @Size(max = 300, message = "Bio must be maximum 300 characters")
    @Column(length = 300)
    private String bio;

    @Column(name = "user_role", length = 50)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean locked = false;

    /**
     * Registration completion status.
     * INCOMPLETE for Google OAuth users who haven't completed profile.
     * ACTIVE after successful registration completion.
     */
    @Column(name = "registration_status", nullable = false, length = 20)
    private RegistrationStatus registrationStatus = RegistrationStatus.ACTIVE;

    // ========== USER MODERATION FIELDS (Admin Infrastructure) ==========
    
    /**
     * If true, user is banned and cannot login or perform any actions.
     * Set by Admin via /api/admin/users/{id}/ban endpoint.
     */
    @Column(nullable = false)
    private boolean banned = false;
    
    /**
     * Admin-provided reason for the ban (audit trail).
     * Example: "Multiple fraudulent booking attempts"
     */
    @Column(name = "ban_reason", length = 500)
    private String banReason;
    
    /**
     * Timestamp when the user was banned.
     * Used for ban duration tracking and audit logs.
     */
    @Column(name = "banned_at")
    private LocalDateTime bannedAt;
    
    /**
     * Admin user who performed the ban action.
     * Foreign key to users.id for accountability.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "banned_by")
    private User bannedBy;

    // ========== OWNER VERIFICATION FIELDS (Serbian Compliance) ==========
    
    /**
     * Owner type: INDIVIDUAL (requires JMBG) or LEGAL_ENTITY (requires PIB).
     */
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType = OwnerType.INDIVIDUAL;
    
    /**
     * Poreski Identifikacioni Broj (Tax ID) - 9 digits.
     * Required for LEGAL_ENTITY owners.
     * Stored encrypted in database.
     */
    /**
     * Poreski Identifikacioni Broj (Tax ID) - 9 digits.
     * Required for LEGAL_ENTITY owners.
     * Stored encrypted in database.
     */
    @Column(name = "pib_encrypted", length = 255)
    @JsonIgnore
    @Convert(converter = AttributeEncryptor.class)
    private String pib;

    /**
     * Hash of PIB for uniqueness checks/searching (SHA-256).
     */
    @Column(name = "pib_hash", unique = true, length = 64)
    @JsonIgnore
    private String pibHash;
    
    /**
     * Jedinstveni Matični Broj Građana (Personal ID) - 13 digits.
     * Required for INDIVIDUAL owners.
     * Stored encrypted in database.
     */
    @Column(name = "jmbg_encrypted", length = 255)
    @JsonIgnore
    @Convert(converter = AttributeEncryptor.class)
    private String jmbg;

    /**
     * Hash of JMBG for uniqueness checks/searching (SHA-256).
     */
    @Column(name = "jmbg_hash", unique = true, length = 64)
    @JsonIgnore
    private String jmbgHash;
    
    /**
     * Whether owner's identity has been verified by admin.
     * REQUIRED: Must be true before car can be approved.
     */
    @Column(name = "is_identity_verified", nullable = false)
    private Boolean isIdentityVerified = false;
    
    /**
     * When owner identity was verified by admin.
     */
    @Column(name = "identity_verified_at")
    private LocalDateTime identityVerifiedAt;
    
    /**
     * Admin user who performed identity verification.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identity_verified_by")
    private User identityVerifiedBy;
    
    /**
     * Bank account number for payouts (encrypted).
     * Must match owner's name/company for compliance.
     */
    @Column(name = "bank_account_number_encrypted", length = 255)
    @JsonIgnore
    @Convert(converter = AttributeEncryptor.class)
    private String bankAccountNumber;

    /**
     * When the owner submitted identity verification for admin review.
     * Used for admin queue ordering (newest first).
     */
    @Column(name = "owner_verification_submitted_at")
    private LocalDateTime ownerVerificationSubmittedAt;

    // ========== RENTER DRIVER LICENSE VERIFICATION FIELDS ==========
    
    /**
     * Driver license verification status (independent from owner verification).
     * State machine: NOT_STARTED → PENDING_REVIEW → APPROVED/REJECTED
     * Booking requires: APPROVED + non-expired license
     */
    @Column(name = "driver_license_status", nullable = false, length = 20)
    private DriverLicenseStatus driverLicenseStatus = DriverLicenseStatus.NOT_STARTED;
    
    /**
     * Driver license number (encrypted, like JMBG/PIB).
     * Extracted from OCR or entered manually.
     */
    @Column(name = "driver_license_number_encrypted", length = 255)
    @JsonIgnore
    @Convert(converter = AttributeEncryptor.class)
    private String driverLicenseNumber;
    
    /**
     * Hash of driver license number for uniqueness checks (SHA-256).
     * Prevents same license being used by multiple accounts.
     */
    @Column(name = "driver_license_number_hash", unique = true, length = 64)
    @JsonIgnore
    private String driverLicenseNumberHash;
    
    /**
     * Driver license expiry date.
     * CRITICAL: Must be valid (future) for booking eligibility.
     * Re-validated at booking creation AND at check-in.
     */
    @Column(name = "driver_license_expiry_date")
    private java.time.LocalDate driverLicenseExpiryDate;
    
    /**
     * Country that issued the driver license (ISO 3166-1 alpha-3).
     * e.g., 'SRB' for Serbia, 'HRV' for Croatia, 'DEU' for Germany.
     */
    @Column(name = "driver_license_country", length = 3)
    private String driverLicenseCountry;
    
    /**
     * How long user has held a valid license (in months).
     * Used for risk scoring (short tenure = higher risk).
     */
    @Column(name = "driver_license_tenure_months")
    private Integer driverLicenseTenureMonths;
    
    /**
     * When driver license was verified by admin/system.
     */
    @Column(name = "driver_license_verified_at")
    private LocalDateTime driverLicenseVerifiedAt;
    
    /**
     * Admin/system who verified the driver license.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_license_verified_by")
    private User driverLicenseVerifiedBy;
    
    /**
     * Risk level for this user (impacts verification requirements).
     * LOW = auto-approve at 95%, MEDIUM = 90% + liveness, HIGH = manual review.
     */
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel = RiskLevel.MEDIUM;
    
    /**
     * When risk level was last evaluated.
     */
    @Column(name = "last_risk_evaluation_at")
    private LocalDateTime lastRiskEvaluationAt;
    
    /**
     * When renter submitted driver license for verification.
     * Used for admin queue ordering (oldest first).
     */
    @Column(name = "renter_verification_submitted_at")
    private LocalDateTime renterVerificationSubmittedAt;
    
    /**
     * Driver license categories (e.g., 'B', 'B,C', 'B+E').
     * Extracted from OCR.
     */
    @Column(name = "driver_license_categories", length = 50)
    private String driverLicenseCategories;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // ========================================================================
    // H3 FIX: Changed CascadeType.ALL to safer cascade options
    // ========================================================================
    // BEFORE: CascadeType.ALL caused cascade deletion of cars/bookings/reviews
    // when user was deleted - catastrophic data loss risk!
    // 
    // AFTER: No cascade DELETE - service layer enforces safe deletion checks.
    // Persist/merge still work normally for creating/updating child entities.
    // 
    // User deletion is now protected by UserService.deleteUser() validation.
    // ========================================================================

    /**
     * Cars owned by this user.
     * CASCADE: Only PERSIST/MERGE to allow saving new cars with owner.
     * DELETE protection: UserService.deleteUser() checks for owned cars.
     */
    @OneToMany(mappedBy = "owner", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Car> cars;

    /**
     * Bookings made by this user as renter.
     * NO CASCADE: Bookings should never be cascade-deleted with user.
     * Historical booking data must be preserved for audit/financial reconciliation.
     */
    @OneToMany(mappedBy = "renter", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Booking> bookings;

    /**
     * Reviews written by this user.
     * CASCADE: Only PERSIST/MERGE for convenience.
     * Reviews are preserved even if user is deleted (soft-delete pattern).
     */
    @OneToMany(mappedBy = "reviewer", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Review> reviewsGiven;

    /**
     * Reviews received by this user.
     * NO CASCADE: Reviews about user should never be auto-deleted.
     */
    @OneToMany(mappedBy = "reviewee", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Review> reviewsReceived;

    @PrePersist
    @PreUpdate
    public void normalize() {
        if (email != null) email = email.toLowerCase();
        if (phone != null) phone = phone.replaceAll("[^0-9]", "");
    }
    
    // ========== HELPER METHODS ==========
    
    /**
     * Check if this user is verified as car owner.
     * Required before car can be approved.
     */
    public boolean isVerifiedOwner() {
        return Boolean.TRUE.equals(isIdentityVerified) && (
            (ownerType == OwnerType.INDIVIDUAL && jmbg != null) ||
            (ownerType == OwnerType.LEGAL_ENTITY && pib != null)
        );
    }
    
    /**
     * Get masked PIB for display (GDPR compliance).
     * Example: "123456789" → "1234***89"
     */
    public String getMaskedPib() {
        if (pib == null || pib.length() < 5) return "****";
        return pib.substring(0, 4) + "***" + pib.substring(pib.length() - 2);
    }
    
    /**
     * Get masked JMBG for display (GDPR compliance).
     * Example: "1234567890123" → "123***90123"
     */
    public String getMaskedJmbg() {
        if (jmbg == null || jmbg.length() < 8) return "****";
        return jmbg.substring(0, 3) + "***" + jmbg.substring(jmbg.length() - 5);
    }

    /**
     * Get masked bank account for display.
     * Example: "RS351050081231231231" -> "RS35**************1231"
     */
    public String getMaskedBankAccountNumber() {
        if (bankAccountNumber == null || bankAccountNumber.isBlank()) return null;
        String value = bankAccountNumber.trim();
        if (value.length() <= 6) return "****";
        int keepPrefix = Math.min(4, value.length());
        int keepSuffix = Math.min(4, value.length() - keepPrefix);
        String prefix = value.substring(0, keepPrefix);
        String suffix = value.substring(value.length() - keepSuffix);
        return prefix + "*".repeat(Math.max(0, value.length() - keepPrefix - keepSuffix)) + suffix;
    }
    
    // ========== RENTER VERIFICATION HELPER METHODS ==========
    
    /**
     * Check if user is verified as renter for booking.
     * Requires APPROVED status AND non-expired license.
     */
    public boolean isVerifiedRenter() {
        return driverLicenseStatus == DriverLicenseStatus.APPROVED
            && !isDriverLicenseExpired();
    }
    
    /**
     * Check if driver license is expired.
     */
    public boolean isDriverLicenseExpired() {
        return driverLicenseExpiryDate != null 
            && driverLicenseExpiryDate.isBefore(java.time.LocalDate.now());
    }
    
    /**
     * Check if driver license will expire within N days.
     */
    public boolean willDriverLicenseExpireWithin(int days) {
        if (driverLicenseExpiryDate == null) return false;
        java.time.LocalDate warningDate = java.time.LocalDate.now().plusDays(days);
        return driverLicenseExpiryDate.isBefore(warningDate) 
            && driverLicenseExpiryDate.isAfter(java.time.LocalDate.now());
    }
    
    /**
     * Check if user can submit/resubmit driver license documents.
     */
    public boolean canSubmitDriverLicense() {
        return driverLicenseStatus.canResubmit();
    }
    
    /**
     * Get masked driver license number for display (GDPR compliance).
     * Example: "AB123456" → "AB12****56"
     */
    public String getMaskedDriverLicenseNumber() {
        if (driverLicenseNumber == null || driverLicenseNumber.length() < 5) return "****";
        int len = driverLicenseNumber.length();
        return driverLicenseNumber.substring(0, 4) + "****" + driverLicenseNumber.substring(len - 2);
    }
    
    /**
     * Get days until driver license expires (negative if expired).
     */
    public long getDaysUntilDriverLicenseExpiry() {
        if (driverLicenseExpiryDate == null) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.now(), driverLicenseExpiryDate);
    }
    
    // ========== AGE CALCULATION METHODS (Enterprise-Grade) ==========
    
    /**
     * Calculate user's current age from date of birth.
     * 
     * <p>This is the PREFERRED method for age checks. Unlike the deprecated
     * {@link #age} field, this always returns the correct current age.
     * 
     * @return User's age in years, or null if DOB not set
     */
    public Integer getAge() {
        if (dateOfBirth == null) {
            // Fallback to legacy age field during migration period
            return age;
        }
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }
    
    /**
     * Check if user meets minimum age requirement.
     * 
     * <p>Standard car rentals require 21+, premium/luxury may require 25+.
     * 
     * @param minimumAge Minimum age requirement (e.g., 21, 25)
     * @return true if user meets requirement or DOB is verified via license
     */
    public boolean meetsAgeRequirement(int minimumAge) {
        Integer currentAge = getAge();
        if (currentAge == null) {
            // If DOB verified through license approval, trust the verification
            // (admin verified license which checks DOB)
            return dobVerified || driverLicenseStatus == DriverLicenseStatus.APPROVED;
        }
        return currentAge >= minimumAge;
    }
    
    /**
     * Check if user is old enough for standard car rentals (21+).
     * 
     * @return true if user is 21 or older
     */
    public boolean isEligibleForStandardRental() {
        return meetsAgeRequirement(21);
    }
    
    /**
     * Check if user is old enough for premium/luxury rentals (25+).
     * 
     * @return true if user is 25 or older
     */
    public boolean isEligibleForPremiumRental() {
        return meetsAgeRequirement(25);
    }
    
    /**
     * Check if date of birth is available (from any source).
     */
    public boolean hasDateOfBirth() {
        return dateOfBirth != null;
    }
    
    /**
     * Check if age data is verified (from official document).
     * Verified DOB should not be editable by user.
     */
    public boolean isAgeVerified() {
        return dobVerified && dateOfBirth != null;
    }
    
    /**
     * Set date of birth from verified source (OCR extraction).
     * Also marks as verified to prevent user tampering.
     * 
     * @param dob Date of birth extracted from official document
     */
    public void setVerifiedDateOfBirth(LocalDate dob) {
        this.dateOfBirth = dob;
        this.dobVerified = true;
    }
}
