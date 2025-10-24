package org.example.rentoza.user;

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

    public User register(UserRegisterDTO dto) {
        // Prevent duplicate email or phone
        if (repo.findByEmail(dto.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }

        if (dto.getPhone() != null && repo.findByPhone(dto.getPhone()).isPresent()) {
            throw new RuntimeException("Phone number already registered");
        }

        // Create new user
        User user = new User();
        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail().toLowerCase());
        user.setPhone(dto.getPhone());
        user.setPassword(encoder.encode(dto.getPassword()));
        user.setRole(Role.USER); // uses enum now
        user.setEnabled(true);
        user.setLocked(false);

        // Initialize empty relations for consistency
        user.setCars(new ArrayList<>());
        user.setBookings(new ArrayList<>());
        user.setReviews(new ArrayList<>());

        return repo.save(user);
    }

    public User updateProfile(String email, UserProfileDTO dto) {
        User user = repo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
            user.setFullName(dto.getFullName());
        }

        if (dto.getPhone() != null && !dto.getPhone().isBlank()) {
            user.setPhone(dto.getPhone());
        }

        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            user.setPassword(encoder.encode(dto.getPassword()));
        }

        return repo.save(user);
    }

    public Optional<User> getUserByEmail(String email) {
        return repo.findByEmail(email);
    }

    public boolean passwordMatches(String raw, String encoded) {
        return encoder.matches(raw, encoded);
    }
}