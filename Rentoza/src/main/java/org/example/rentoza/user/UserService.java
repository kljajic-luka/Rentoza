package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.example.rentoza.user.dto.UserProfileDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;
import org.example.rentoza.user.dto.UpdateProfileRequestDTO;
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

        if (!changed) {
            return user; // No changes, return existing user
        }

        return repo.saveAndFlush(user);
    }
}
