package org.example.rentoza.auth.oauth2;

import org.example.rentoza.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Wrapper class that adapts our User entity to Spring Security's OAuth2User interface.
 * This allows the User to be used as the authenticated principal in OAuth2 flows.
 */
public class OAuth2UserPrincipal implements OAuth2User {

    private final User user;
    private final Map<String, Object> attributes;

    public OAuth2UserPrincipal(User user, Map<String, Object> attributes) {
        this.user = user;
        this.attributes = attributes;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(
            new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );
    }

    @Override
    public String getName() {
        return user.getEmail();
    }

    /**
     * Get the underlying User entity
     */
    public User getUser() {
        return user;
    }

    /**
     * Get user email
     */
    public String getEmail() {
        return user.getEmail();
    }

    /**
     * Get user ID
     */
    public Long getId() {
        return user.getId();
    }
}
