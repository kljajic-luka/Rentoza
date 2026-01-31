package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.example.rentoza.user.dto.UserProfileDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;
import org.example.rentoza.user.dto.UpdateProfileRequestDTO;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.ArrayList;

@Service
public class UserService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }
    public User getOrThrow(String email) {
        return repo.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + email));
    }
    public User register(UserRegisterDTO dto) {
        if (repo.findByEmail(dto.getEmail()).isPresent()) {
            throw new BadRequestException("Email already registered");
        }

        if (dto.getPhone() != null && repo.findByPhone(dto.getPhone()).isPresent()) {
            throw new BadRequestException("Phone number already registered");
        }

        User user = new User();
        user.setFirstName(dto.getFirstName());
        user.setLastName(dto.getLastName());
        user.setEmail(dto.getEmail().toLowerCase());
        user.setPhone(dto.getPhone());
        user.setPassword(encoder.encode(dto.getPassword()));

        if ("OWNER".equalsIgnoreCase(dto.getRole())) {
            user.setRole(Role.OWNER);
        } else {
            user.setRole(Role.USER);
        }

        user.setEnabled(true);
        user.setLocked(false);
        user.setCars(new ArrayList<>());
        user.setBookings(new ArrayList<>());
        user.setReviewsGiven(new ArrayList<>());
        user.setReviewsReceived(new ArrayList<>());

        return repo.save(user);
    }

    @Transactional
    public User updateProfile(String email, UserProfileDTO dto) {
        User user = getOrThrow(email);
        boolean changed = false;
        // ✅ FIRST NAME
        if (dto.getFirstName() != null) {
            String firstName = dto.getFirstName().trim();
            if (!firstName.isBlank() && !firstName.equals(user.getFirstName())) {
                if (firstName.length() < 2 || firstName.length() > 50) {
                    throw new BadRequestException("First name must be between 2 and 50 characters");
                }
                user.setFirstName(firstName);
                changed = true;
            }
        }

        // ✅ LAST NAME
        if (dto.getLastName() != null) {
            String lastName = dto.getLastName().trim();
            if (!lastName.isBlank() && !lastName.equals(user.getLastName())) {
                if (lastName.length() < 2 || lastName.length() > 50) {
                    throw new BadRequestException("Last name must be between 2 and 50 characters");
                }
                user.setLastName(lastName);
                changed = true;
            }
        }

        if (dto.getPhone() != null) {
            String raw = dto.getPhone().trim();
            String sanitized = raw.replaceAll("[^0-9]", ""); // keep digits only

            if (!sanitized.isBlank() && !sanitized.equals(user.getPhone())) {
                if (!sanitized.matches("^[0-9]{8,15}$")) {
                    throw new BadRequestException("Phone must contain only digits (8–15 characters)");
                }
                // ensure unique (excluding current user)
                if (repo.existsByPhoneAndIdNot(sanitized, user.getId())) {
                    throw new BadRequestException("Phone number is already in use");
                }
                user.setPhone(sanitized);
                changed = true;
            }
        }

        if (dto.getPassword() != null) {
            String newPass = dto.getPassword().trim();
            if (!newPass.isBlank()) {
                if (newPass.length() < 8 ||
                        !newPass.matches(".*[A-Z].*") ||
                        !newPass.matches(".*[a-z].*") ||
                        !newPass.matches(".*\\d.*")) {
                    throw new BadRequestException(
                            "Password must be at least 8 chars and include uppercase, lowercase, and a number"
                    );
                }
                user.setPassword(encoder.encode(newPass));
                changed = true;
            }
        }


        if (!changed) {
            return user;
        }

        return repo.saveAndFlush(user);
    }

    public Optional<User> getUserByEmail(String email) {
        return repo.findByEmail(email);
    }

    public Optional<User> getUserById(Long id) {
        return repo.findById(id);
    }

    public UserResponseDTO toUserResponse(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null when building response");
        }

        return UserResponseDTO.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .age(user.getAge())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .registrationStatus(user.getRegistrationStatus() != null 
                        ? user.getRegistrationStatus().name() 
                        : "ACTIVE")
                .ownerType(user.getOwnerType() != null 
                        ? user.getOwnerType().name() 
                        : null)
                .build();
    }

    /**
     * Update user's avatar URL after profile picture upload.
     * Used by ProfilePictureController after successful image processing.
     *
     * @param userId    The user's ID
     * @param avatarUrl The new avatar URL (can be null to remove)
     * @throws EntityNotFoundException if user not found
     */
    @Transactional
    public void updateAvatarUrl(Long userId, String avatarUrl) {
        User user = repo.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with ID: " + userId));

        // Validate URL length if not null
        if (avatarUrl != null && avatarUrl.length() > 500) {
            throw new BadRequestException("Avatar URL exceeds maximum length of 500 characters");
        }

        user.setAvatarUrl(avatarUrl);
        repo.saveAndFlush(user);
    }

    public boolean passwordMatches(String raw, String encoded) {
        return encoder.matches(raw, encoded);
    }

    /**
     * Secure partial profile update - only allows updating safe, non-identity fields.
     * This enforces the trust-first identity model where sensitive fields like
     * firstName, lastName, email, and role require verification/admin approval.
     *
     * @param email The authenticated user's email (from JWT)
     * @param dto   Contains only editable fields: phone, avatarUrl, bio
     * @return Updated user entity
     * @throws EntityNotFoundException if user not found
     * @throws BadRequestException     if validation fails or phone already in use
     */
    @Transactional
    public User updateProfileSecure(String email, UpdateProfileRequestDTO dto) {
        User user = getOrThrow(email);
        boolean changed = false;

        // ✅ PHONE - validate and check uniqueness
        if (dto.getPhone() != null) {
            String sanitized = dto.getPhone().trim().replaceAll("[^0-9]", "");
            if (!sanitized.isBlank() && !sanitized.equals(user.getPhone())) {
                if (!sanitized.matches("^[0-9]{8,15}$")) {
                    throw new BadRequestException("Phone must contain 8-15 digits");
                }
                // Check uniqueness excluding current user
                if (repo.existsByPhoneAndIdNot(sanitized, user.getId())) {
                    throw new BadRequestException("Phone number is already in use");
                }
                user.setPhone(sanitized);
                changed = true;
            }
        }

        // ✅ AVATAR URL - basic validation
        if (dto.getAvatarUrl() != null) {
            String avatarUrl = dto.getAvatarUrl().trim();
            if (!avatarUrl.equals(user.getAvatarUrl())) {
                if (avatarUrl.length() > 500) {
                    throw new BadRequestException("Avatar URL must be maximum 500 characters");
                }
                user.setAvatarUrl(avatarUrl.isBlank() ? null : avatarUrl);
                changed = true;
            }
        }

        // ✅ BIO - validate length
        if (dto.getBio() != null) {
            String bio = dto.getBio().trim();
            if (!bio.equals(user.getBio())) {
                if (bio.length() > 300) {
                    throw new BadRequestException("Bio must be maximum 300 characters");
                }
                user.setBio(bio.isBlank() ? null : bio);
                changed = true;
            }
        }

        // ✅ LAST NAME - allow change only for Google placeholder users
        if (dto.getLastName() != null) {
            String lastName = dto.getLastName().trim();
            if (lastName.isBlank() || lastName.length() < 3 || lastName.length() > 50) {
                throw new BadRequestException("Last name must be between 3 and 50 characters");
            }
            if (!User.GOOGLE_PLACEHOLDER_LAST_NAME.equals(user.getLastName())) {
                throw new BadRequestException("Changing last name requires identity verification");
            }
            if (lastName.equals(User.GOOGLE_PLACEHOLDER_LAST_NAME)) {
                throw new BadRequestException("Please provide your actual last name");
            }
            if (!lastName.equals(user.getLastName())) {
                user.setLastName(lastName);
                changed = true;
            }
        }
        
        // ✅ DATE OF BIRTH - allow manual entry only if not already verified via OCR
        if (dto.getDateOfBirth() != null) {
            java.time.LocalDate dob = dto.getDateOfBirth();
            
            // Check if DOB is already verified (from license OCR) - cannot override
            if (user.isDobVerified()) {
                throw new BadRequestException(
                    "Datum rođenja je već verifikovan putem vozačke dozvole i ne može se promeniti"
                );
            }
            
            // Validate DOB is in the past
            if (!dob.isBefore(java.time.LocalDate.now())) {
                throw new BadRequestException("Datum rođenja mora biti u prošlosti");
            }
            
            // Validate user is at least 18 years old
            int age = java.time.Period.between(dob, java.time.LocalDate.now()).getYears();
            if (age < 18) {
                throw new BadRequestException("Morate imati najmanje 18 godina");
            }
            
            // Validate user is not unreasonably old (sanity check)
            if (age > 120) {
                throw new BadRequestException("Unesite validan datum rođenja");
            }
            
            if (!dob.equals(user.getDateOfBirth())) {
                user.setDateOfBirth(dob);
                // Note: NOT setting dobVerified=true for self-reported DOB
                // Only OCR-extracted DOB from verified license gets dobVerified=true
                changed = true;
            }
        }

        if (!changed) {
            return user; // No changes, return existing user
        }

        return repo.saveAndFlush(user);
    }
    
    // ========================================================================
    // H3 FIX: Safe User Deletion with Pre-Condition Checks
    // ========================================================================
    // BEFORE: CascadeType.ALL on User entity would cascade-delete cars, bookings,
    // and reviews when user was deleted - catastrophic data loss.
    // 
    // AFTER: CascadeType removed from User. This method enforces business rules
    // that prevent user deletion when they have active obligations.
    // ========================================================================
    
    /**
     * Safely delete a user with comprehensive pre-condition validation.
     * 
     * <p>Business Rules Enforced:
     * <ul>
     *   <li>Cannot delete user with active or pending bookings (as renter OR owner)</li>
     *   <li>Cannot delete user who owns cars (must delete/transfer cars first)</li>
     *   <li>Historical booking/review data is preserved (user becomes "deleted user")</li>
     * </ul>
     * 
     * <p>Note: This method performs HARD DELETE. For production, consider implementing
     * soft-delete (setting a 'deleted' flag) to preserve referential integrity.
     * 
     * @param userId The user ID to delete
     * @throws EntityNotFoundException if user not found
     * @throws IllegalStateException if user has blocking conditions
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = repo.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        
        // ========================================================================
        // CHECK 1: User has active/pending bookings AS RENTER
        // ========================================================================
        // Users cannot be deleted while they have ongoing rentals
        if (user.getBookings() != null) {
            long activeRenterBookings = user.getBookings().stream()
                    .filter(b -> b.getStatus() != null && isBlockingBookingStatus(b.getStatus()))
                    .count();
            if (activeRenterBookings > 0) {
                throw new IllegalStateException(String.format(
                        "Ne možete obrisati korisnika sa %d aktivnih/čekajućih rezervacija. " +
                        "Otkažite ili završite sve rezervacije prvo.",
                        activeRenterBookings
                ));
            }
        }
        
        // ========================================================================
        // CHECK 2: User has active/pending bookings AS OWNER (via their cars)
        // ========================================================================
        if (user.getCars() != null && !user.getCars().isEmpty()) {
            for (var car : user.getCars()) {
                // Note: This requires Car to have getBookings() - if not, we check via repository
                // For now, we block any user with cars (simpler and safer approach)
            }
            throw new IllegalStateException(String.format(
                    "Ne možete obrisati vlasnika sa %d vozila. " +
                    "Obrišite ili prenesite vlasništvo nad vozilima prvo.",
                    user.getCars().size()
            ));
        }
        
        // ========================================================================
        // CHECK 3: User is an admin - prevent admin deletion via this method
        // ========================================================================
        if (user.getRole() == Role.ADMIN) {
            throw new IllegalStateException(
                    "Admin korisnici ne mogu biti obrisani putem ove metode. " +
                    "Kontaktirajte sistem administratora."
            );
        }
        
        // ========================================================================
        // SAFE TO DELETE
        // ========================================================================
        // At this point, user has no blocking conditions. Their reviews will remain
        // in the system (mapped to deleted user) for historical integrity.
        //
        // Future enhancement: Implement soft-delete pattern instead of hard delete
        // e.g., user.setDeleted(true); user.setDeletedAt(Instant.now());
        
        repo.delete(user);
    }
    
    /**
     * Check if booking status blocks user deletion.
     */
    private boolean isBlockingBookingStatus(org.example.rentoza.booking.BookingStatus status) {
        return switch (status) {
            case PENDING_APPROVAL, ACTIVE, APPROVED, PENDING_CHECKOUT, 
                 CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE, CHECK_IN_DISPUTE,
                 IN_TRIP, CHECKOUT_OPEN, CHECKOUT_GUEST_COMPLETE, CHECKOUT_HOST_COMPLETE,
                 CHECKOUT_DAMAGE_DISPUTE -> true;
            case COMPLETED, CANCELLED, DECLINED, EXPIRED, EXPIRED_SYSTEM, NO_SHOW_HOST, NO_SHOW_GUEST -> false;
        };
    }
}
