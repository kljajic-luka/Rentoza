package org.example.rentoza.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Custom authentication principal that includes user ID alongside email and roles.
 * Replaces the default UserDetails to enable service-level RLS without additional lookups.
 * 
 * <p>This principal is set in SecurityContext by JwtAuthFilter after successful JWT validation.
 * Services can inject this via @AuthenticationPrincipal or access via SecurityContextHolder.
 * 
 * <p>Design rationale:
 * - Includes userId for direct ownership checks (no email→ID lookup)
 * - Implements UserDetails for Spring Security compatibility
 * - Immutable record for thread safety
 * - Roles stored as strings for easy admin checks
 * 
 * @param id User's database primary key
 * @param email User's email (username in Spring Security terms)
 * @param roles List of role names (e.g., ["USER", "OWNER", "ADMIN"])
 * @param authorities Spring Security authorities (auto-generated from roles)
 */
public record JwtUserPrincipal(
        Long id,
        String email,
        List<String> roles,
        Collection<? extends GrantedAuthority> authorities
) implements UserDetails {

    /**
     * Factory method to create principal from user ID, email, and roles.
     * Automatically converts roles to Spring Security authorities with ROLE_ prefix.
     * 
     * @param id User's database ID
     * @param email User's email
     * @param roles List of role names (e.g., "USER", "OWNER", "ADMIN")
     * @return Fully constructed JwtUserPrincipal
     */
    public static JwtUserPrincipal create(Long id, String email, List<String> roles) {
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
        
        return new JwtUserPrincipal(id, email, roles, authorities);
    }

    /**
     * Checks if user has ADMIN role.
     * Useful for service-level admin bypass logic.
     * 
     * @return true if user has ADMIN role
     */
    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    /**
     * Checks if user has specific role (without ROLE_ prefix).
     * 
     * @param role Role name (e.g., "OWNER", "USER")
     * @return true if user has the specified role
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    // ========== UserDetails Implementation ==========

    @Override
    public String getUsername() {
        return email;
    }

    public Long getId(){return  id;}

    @Override
    public String getPassword() {
        // Not used in JWT authentication (password already verified during login)
        return null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String toString() {
        return "JwtUserPrincipal{id=%d, email='%s', roles=%s}".formatted(id, email, roles);
    }
}
