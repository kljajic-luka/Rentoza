package org.example.rentoza.security.supabase;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity mapping Supabase Auth UUIDs to Rentoza BIGINT user IDs.
 * 
 * <p>This bridge table enables RLS policies to work with the existing
 * BIGINT-based user schema while using Supabase Auth's UUID identifiers.
 * 
 * <p>Relationship:
 * <ul>
 *   <li>One Supabase user maps to exactly one Rentoza user (1:1)</li>
 *   <li>supabase_id: UUID from Supabase Auth (auth.uid())</li>
 *   <li>rentoza_user_id: BIGINT from users.id</li>
 * </ul>
 * 
 * @since Phase 2 - Supabase Auth Migration
 */
@Entity
@Table(name = "supabase_user_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupabaseUserMapping {

    /**
     * Supabase Auth user UUID (primary key).
     * This is the value returned by auth.uid() in Supabase.
     */
    @Id
    @Column(name = "supabase_id", nullable = false, updatable = false)
    private UUID supabaseId;

    /**
     * Rentoza user ID (existing BIGINT).
     * Foreign key reference to users.id.
     */
    @Column(name = "rentoza_user_id", nullable = false, unique = true)
    private Long rentozaUserId;

    /**
     * When the mapping was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the mapping was last updated.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Create a new mapping entry.
     * 
     * @param supabaseId Supabase Auth UUID
     * @param rentozaUserId Rentoza user ID
     * @return New mapping instance
     */
    public static SupabaseUserMapping create(UUID supabaseId, Long rentozaUserId) {
        return SupabaseUserMapping.builder()
                .supabaseId(supabaseId)
                .rentozaUserId(rentozaUserId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
