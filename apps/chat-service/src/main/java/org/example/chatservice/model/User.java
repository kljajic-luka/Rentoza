package org.example.chatservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * User entity (maps to main backend users table)
 * 
 * <p>Minimal representation of users for chat service</p>
 * <p>Full user entity exists in main backend</p>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Entity
@Table(name = "users", schema = "public")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /**
     * Rentoza internal user ID (BIGINT)
     * Primary key, auto-generated
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Supabase Auth user ID (UUID)
     * Maps to auth.users.id in Supabase
     * Used for JWT token validation and RLS policies
     */
    @Column(name = "auth_uid", nullable = false, unique = true)
    private UUID authUid;

    /**
     * User email address
     * Optional - not used by chat service
     */
    @Column(name = "email")
    private String email;

    /**
     * User role from main backend (e.g., USER, OWNER, ADMIN).
     * Required for admin oversight endpoints to grant ROLE_ADMIN authority.
     * Maps to users.user_role column.
     */
    @Column(name = "user_role", length = 50)
    private String role;
}
