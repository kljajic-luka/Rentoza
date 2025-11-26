package org.example.rentoza.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.BanUserRequestDTO;
import org.example.rentoza.admin.dto.UserModerationResponseDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin User Management Controller
 * 
 * Provides administrative capabilities for user moderation:
 * - Ban users (with reason for audit trail)
 * - Unban users
 * - List banned users
 * - View user moderation history
 * 
 * Security:
 * - All endpoints require ROLE_ADMIN
 * - Actions are logged with admin user ID
 * - bannedBy foreign key tracks accountability
 * 
 * @see User#banned
 * @see User#banReason
 * @see User#bannedAt
 * @see User#bannedBy
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    
    // Dedicated security audit logger for admin actions
    private static final org.slf4j.Logger securityLog = 
            org.slf4j.LoggerFactory.getLogger("SECURITY_AUDIT");

    /**
     * Ban a user from the platform.
     * 
     * Sets the user's banned flag to true and records:
     * - Reason for ban (required)
     * - Timestamp of ban
     * - Admin who performed the action
     * 
     * Banned users cannot:
     * - Login (checked in JwtAuthFilter/CustomUserDetailsService)
     * - Create bookings
     * - Access any authenticated endpoints
     * 
     * @param id User ID to ban
     * @param request Ban details (reason is required)
     * @return Updated user moderation status
     */
    @PutMapping("/{id}/ban")
    @Transactional
    public ResponseEntity<UserModerationResponseDTO> banUser(
            @PathVariable Long id,
            @Valid @RequestBody BanUserRequestDTO request) {
        
        User targetUser = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        
        // Prevent self-ban (admin protection)
        if (targetUser.getId().equals(currentUser.id())) {
            return ResponseEntity.badRequest()
                    .body(UserModerationResponseDTO.error("Cannot ban yourself"));
        }
        
        // Prevent banning other admins (requires super-admin in future)
        if (targetUser.getRole().name().equals("ADMIN")) {
            return ResponseEntity.badRequest()
                    .body(UserModerationResponseDTO.error("Cannot ban other admins"));
        }
        
        // Check if already banned
        if (targetUser.isBanned()) {
            return ResponseEntity.badRequest()
                    .body(UserModerationResponseDTO.error("User is already banned"));
        }
        
        // Get admin user for audit trail
        User admin = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
        
        // Apply ban
        targetUser.setBanned(true);
        targetUser.setBanReason(request.getReason());
        targetUser.setBannedAt(LocalDateTime.now());
        targetUser.setBannedBy(admin);
        
        userRepository.save(targetUser);
        
        // Security audit log
        securityLog.info("USER_BANNED: targetUserId={}, targetEmail={}, adminId={}, adminEmail={}, reason={}",
                targetUser.getId(), 
                maskEmail(targetUser.getEmail()),
                admin.getId(),
                maskEmail(admin.getEmail()),
                request.getReason());
        
        log.info("User banned: userId={}, by adminId={}", id, currentUser.id());
        
        return ResponseEntity.ok(UserModerationResponseDTO.fromUser(targetUser));
    }

    /**
     * Unban a user, restoring their platform access.
     * 
     * Clears all ban-related fields:
     * - banned = false
     * - banReason = null
     * - bannedAt = null
     * - bannedBy = null
     * 
     * @param id User ID to unban
     * @return Updated user moderation status
     */
    @PutMapping("/{id}/unban")
    @Transactional
    public ResponseEntity<UserModerationResponseDTO> unbanUser(@PathVariable Long id) {
        
        User targetUser = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        
        // Check if actually banned
        if (!targetUser.isBanned()) {
            return ResponseEntity.badRequest()
                    .body(UserModerationResponseDTO.error("User is not banned"));
        }
        
        // Store previous ban info for audit log
        String previousReason = targetUser.getBanReason();
        User previousBanner = targetUser.getBannedBy();
        
        // Clear ban
        targetUser.setBanned(false);
        targetUser.setBanReason(null);
        targetUser.setBannedAt(null);
        targetUser.setBannedBy(null);
        
        userRepository.save(targetUser);
        
        // Security audit log
        securityLog.info("USER_UNBANNED: targetUserId={}, targetEmail={}, adminId={}, previousReason={}, previousBannerId={}",
                targetUser.getId(),
                maskEmail(targetUser.getEmail()),
                currentUser.id(),
                previousReason,
                previousBanner != null ? previousBanner.getId() : "N/A");
        
        log.info("User unbanned: userId={}, by adminId={}", id, currentUser.id());
        
        return ResponseEntity.ok(UserModerationResponseDTO.fromUser(targetUser));
    }

    /**
     * List all currently banned users.
     * 
     * Returns paginated list of banned users with:
     * - User basic info (id, email, name)
     * - Ban details (reason, timestamp, admin who banned)
     * 
     * @return List of banned users
     */
    @GetMapping("/banned")
    public ResponseEntity<List<UserModerationResponseDTO>> listBannedUsers() {
        List<User> bannedUsers = userRepository.findByBannedTrue();
        
        List<UserModerationResponseDTO> response = bannedUsers.stream()
                .map(UserModerationResponseDTO::fromUser)
                .collect(Collectors.toList());
        
        log.debug("Listed {} banned users, requested by adminId={}", 
                response.size(), currentUser.id());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get moderation status for a specific user.
     * 
     * @param id User ID
     * @return User's current moderation status
     */
    @GetMapping("/{id}/moderation-status")
    public ResponseEntity<UserModerationResponseDTO> getModerationStatus(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        
        return ResponseEntity.ok(UserModerationResponseDTO.fromUser(user));
    }

    /**
     * Mask email for logging (privacy protection).
     * Example: john.doe@example.com -> j***@example.com
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@");
        if (parts[0].length() <= 1) {
            return "***@" + parts[1];
        }
        return parts[0].charAt(0) + "***@" + parts[1];
    }
}
