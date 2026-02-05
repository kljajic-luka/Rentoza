package org.example.rentoza.security;

import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Custom UserDetailsService that loads user from database and returns JwtUserPrincipal.
 * This includes user ID for RLS enforcement without additional lookups.
 * 
 * Security: Checks if user is banned before allowing authentication.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository repo;

    public CustomUserDetailsService(UserRepository repo) {
        this.repo = repo;
    }

    /**
     * Loads user by email and returns JwtUserPrincipal with ID, email, and roles.
     * 
     * Security: Throws DisabledException if user is banned, preventing login.
     * 
     * @param email User's email (username in Spring Security terms)
     * @return JwtUserPrincipal containing user ID, email, and roles
     * @throws UsernameNotFoundException if user not found
     * @throws DisabledException if user is banned
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        // SECURITY: Check if user is banned before allowing authentication
        if (user.isBanned()) {
            throw new DisabledException("Your account has been suspended. Reason: " + 
                    (user.getBanReason() != null ? user.getBanReason() : "Contact support for details."));
        }

        // Return JwtUserPrincipal instead of default UserDetails
        // This enables services to access userId directly from SecurityContext
        return JwtUserPrincipal.create(
                user.getId(),
                user.getEmail(),
                List.of(user.getRole().name())
        );
    }
}

