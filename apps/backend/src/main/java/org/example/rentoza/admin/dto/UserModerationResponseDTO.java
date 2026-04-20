package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.user.User;

import java.time.LocalDateTime;

/**
 * Response DTO for user moderation operations.
 * 
 * Contains user basic info plus moderation status.
 * Used for ban/unban operations and listing banned users.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModerationResponseDTO {

    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    
    // Moderation status
    private boolean banned;
    private String banReason;
    private LocalDateTime bannedAt;
    private Long bannedById;
    private String bannedByEmail;
    
    // Operation result
    private boolean success;
    private String message;

    /**
     * Create DTO from User entity.
     */
    public static UserModerationResponseDTO fromUser(User user) {
        UserModerationResponseDTO dto = UserModerationResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .banned(user.isBanned())
                .banReason(user.getBanReason())
                .bannedAt(user.getBannedAt())
                .success(true)
                .message(user.isBanned() ? "User is banned" : "User is active")
                .build();
        
        // Populate bannedBy info if available
        if (user.getBannedBy() != null) {
            dto.setBannedById(user.getBannedBy().getId());
            dto.setBannedByEmail(user.getBannedBy().getEmail());
        }
        
        return dto;
    }

    /**
     * Create error response.
     */
    public static UserModerationResponseDTO error(String message) {
        return UserModerationResponseDTO.builder()
                .success(false)
                .message(message)
                .build();
    }
}
