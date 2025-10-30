package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.example.rentoza.user.dto.UserProfileDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;
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

    public boolean passwordMatches(String raw, String encoded) {
        return encoder.matches(raw, encoded);
    }
}
