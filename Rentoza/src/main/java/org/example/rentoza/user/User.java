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
import java.time.LocalDateTime;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @Column(name = "google_id", unique = true, length = 100)
    private String googleId;

    @Pattern(regexp = "^[0-9]{8,15}$")
    @Column(unique = true)
    private String phone;

    @Min(value = 18, message = "Age must be at least 18")
    @Max(value = 120, message = "Age must be less than 120")
    @Column(name = "age")
    private Integer age;

    @Column(length = 500)
    private String avatarUrl;

    @Size(max = 300, message = "Bio must be maximum 300 characters")
    @Column(length = 300, columnDefinition = "VARCHAR(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", length = 50)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean locked = false;

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
    @Enumerated(EnumType.STRING)
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

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Car> cars;

    @OneToMany(mappedBy = "renter", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Booking> bookings;

    @OneToMany(mappedBy = "reviewer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Review> reviewsGiven;

    @OneToMany(mappedBy = "reviewee", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
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
}
