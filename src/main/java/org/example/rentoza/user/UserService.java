package org.example.rentoza.user;

import org.example.rentoza.user.dto.UserProfileDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public User register(UserRegisterDTO dto) {
        if (repo.findByEmail(dto.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User();
        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setPassword(encoder.encode(dto.getPassword()));
        user.setRole(Role.USER); // ✅ now sets enum directly

        return repo.save(user);
    }
    public User updateProfile(String email, UserProfileDTO dto) {
        // Find existing user
        User user = repo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update fields selectively
        if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
            user.setFullName(dto.getFullName());
        }

        if (dto.getPhone() != null && !dto.getPhone().isBlank()) {
            user.setPhone(dto.getPhone());
        }

        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            user.setPassword(encoder.encode(dto.getPassword())); // encode new password
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
