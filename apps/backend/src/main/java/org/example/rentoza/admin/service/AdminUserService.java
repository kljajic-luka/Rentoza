package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminAuditLogDto;
import org.example.rentoza.admin.dto.AdminUserDetailDto;
import org.example.rentoza.admin.dto.AdminUserDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.deprecated.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.UserDeviceTokenRepository;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.gdpr.GdprService;
import org.example.rentoza.user.trust.AccountTrustSnapshot;
import org.example.rentoza.user.trust.AccountTrustStateService;
import org.example.rentoza.security.password.PasswordHistoryRepository;
import org.example.rentoza.security.password.PasswordResetTokenRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin User Management Service.
 * 
 * <p>Provides comprehensive user management operations for admin panel:
 * <ul>
 *   <li>User listing with search and filters</li>
 *   <li>User detail with related data</li>
 *   <li>User deletion with cascade cleanup</li>
 *   <li>Risk score calculation</li>
 * </ul>
 * 
 * <p><b>SECURITY:</b> All operations are audited via {@link AdminAuditService}.
 * 
 * @see AdminUserRepository
 * @see AdminAuditService
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AdminUserService {
    
    private final AdminUserRepository userRepo;
    private final BookingRepository bookingRepo;
    private final CarRepository carRepo;
    private final ReviewRepository reviewRepo;
    private final DamageClaimRepository damageClaimRepo;
    private final CancellationSettlementService cancellationSettlementService;
    private final AdminAuditService auditService;
    private final AccountTrustStateService accountTrustStateService;
    private final GdprService gdprService;
    private final RefreshTokenServiceEnhanced refreshTokenService;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    
    // ==================== USER LISTING ====================
    
    /**
     * List all users with pagination.
     * 
     * @param pageable Pagination parameters
     * @return Paginated user list
     */
    @Transactional(readOnly = true)
    public Page<AdminUserDto> listUsers(Pageable pageable) {
        Page<User> users = userRepo.findAllUsers(pageable);
        return users.map(this::toAdminUserDto);
    }
    
    /**
     * Search users by email, name, or phone.
     * 
     * @param searchTerm Search string
     * @param pageable Pagination parameters
     * @return Matching users
     */
    @Transactional(readOnly = true)
    public Page<AdminUserDto> searchUsers(String searchTerm, Pageable pageable) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return listUsers(pageable);
        }
        Page<User> users = userRepo.searchUsers(searchTerm.trim(), pageable);
        return users.map(this::toAdminUserDto);
    }
    
    /**
     * List banned users with pagination.
     * 
     * @param pageable Pagination parameters
     * @return Paginated banned user list
     */
    @Transactional(readOnly = true)
    public Page<AdminUserDto> listBannedUsers(Pageable pageable) {
        Page<User> users = userRepo.findBannedUsersPaged(pageable);
        return users.map(this::toAdminUserDto);
    }

    /**
     * List users with pending DOB correction requests.
     * SECURITY (M-9): Operations queue for admin review.
     *
     * @param pageable Pagination parameters
     * @return Paginated list of users with pending DOB corrections
     */
    @Transactional(readOnly = true)
    public Page<AdminUserDto> listPendingDobCorrections(Pageable pageable) {
        Page<User> users = userRepo.findPendingDobCorrections(pageable);
        return users.map(this::toAdminUserDto);
    }
    
    // ==================== USER DETAIL ====================
    
    /**
     * Get detailed user information including related data.
     * 
     * @param userId User ID
     * @return Full user detail DTO
     * @throws ResourceNotFoundException if user not found
     */
    @Transactional(readOnly = true)
    public AdminUserDetailDto getUserDetail(Long userId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        
        AdminUserDetailDto dto = AdminUserDetailDto.fromEntity(user);
        
        // Add booking statistics
        List<Booking> bookings = bookingRepo.findByRenterId(userId);
        dto.setTotalBookings(bookings.size());
        dto.setCompletedBookings((int) bookings.stream()
            .filter(b -> b.getStatus() == BookingStatus.COMPLETED).count());
        dto.setCancelledBookings((int) bookings.stream()
            .filter(b -> b.getStatus() == BookingStatus.CANCELLED).count());
        
        // Add car statistics (for owners)
        if (user.getRole() == Role.OWNER || user.getRole() == Role.ADMIN) {
            List<Car> cars = carRepo.findByOwnerId(userId);
            dto.setTotalCars(cars.size());
            dto.setActiveCars((int) cars.stream().filter(Car::isAvailable).count());
        }
        
        // Add risk score
        dto.setRiskScore(calculateRiskScore(user));
        dto.setRiskFactors(getRiskFactors(user));
        
        // Add recent admin actions on this user
        List<AdminAuditLogDto> recentActions = auditService.getResourceHistory(
            ResourceType.USER, userId);
        dto.setRecentAdminActions(recentActions);
        
        return dto;
    }
    
    // ==================== USER MODERATION ====================
    
    /**
     * Ban a user from the platform.
     * 
     * @param userId User to ban
     * @param reason Reason for ban
     * @param admin Admin performing the action
     * @throws ResourceNotFoundException if user not found
     */
    @PreAuthorize("hasRole('ADMIN')")
    public void banUser(Long userId, String reason, User admin) {
        User targetUser = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
            
        // Prevent banning admins
        if (targetUser.getRole() == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot ban other admins");
        }
        
        String beforeState = auditService.toJson(AdminUserDetailDto.fromEntity(targetUser));
        
        targetUser.setBanned(true);
        targetUser.setBanReason(reason);
        targetUser.setBannedAt(org.example.rentoza.config.timezone.SerbiaTimeZone.now());
        userRepo.save(targetUser);
        
        // Audit log
        auditService.logAction(
            admin,
            AdminAction.USER_BANNED,
            ResourceType.USER,
            userId,
            beforeState,
            auditService.toJson(AdminUserDetailDto.fromEntity(targetUser)),
            reason
        );
        
        log.info("User {} banned by admin {}. Reason: {}", userId, admin.getId(), reason);
    }
    
    /**
     * Unban a user.
     * 
     * @param userId User to unban
     * @param admin Admin performing the action
     * @throws ResourceNotFoundException if user not found
     */
    public void unbanUser(Long userId, User admin) {
        User targetUser = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
            
        String beforeState = auditService.toJson(AdminUserDetailDto.fromEntity(targetUser));
        
        targetUser.setBanned(false);
        targetUser.setBanReason(null);
        targetUser.setBannedAt(null);
        userRepo.save(targetUser);
        
        // Audit log
        auditService.logAction(
            admin,
            AdminAction.USER_UNBANNED,
            ResourceType.USER,
            userId,
            beforeState,
            auditService.toJson(AdminUserDetailDto.fromEntity(targetUser)),
            "Unbanned by admin"
        );
        
        log.info("User {} unbanned by admin {}", userId, admin.getId());
    }
    
    /**
    * Delete user with full cascade cleanup.
     * 
     * <p><b>CASCADE OPERATIONS:</b>
     * <ol>
     *   <li>Cancel all pending/confirmed bookings</li>
     *   <li>Anonymize reviews (keep content, remove user reference)</li>
     *   <li>Archive/deactivate user's cars</li>
    *   <li>Revoke active sessions and device tokens</li>
    *   <li>Anonymize retained user row via GDPR tombstone path</li>
     *   <li>Create immutable audit log</li>
     * </ol>
     * 
     * @param userId User to delete
     * @param reason Admin-provided reason
     * @param admin Admin performing deletion
     * @throws ResourceNotFoundException if user not found
     * @throws IllegalArgumentException if trying to delete admin
     */
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteUser(Long userId, String reason, User admin) {
        User targetUser = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        
        // Prevent self-deletion
        if (targetUser.getId().equals(admin.getId())) {
            throw new IllegalArgumentException("Cannot delete yourself");
        }
        
        // Prevent admin deletion
        if (targetUser.getRole() == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot delete other admins");
        }
        
        // Capture before state for audit
        String beforeState = auditService.toJson(AdminUserDetailDto.fromEntity(targetUser));
        
        log.info("Admin {} deleting user {} ({}). Reason: {}",
            admin.getEmail(), userId, targetUser.getEmail(), reason);

        // H-7 FIX: Audit FIRST — even if cascade fails, the intent is logged
        auditService.logAction(
            admin,
            AdminAction.USER_DELETED,
            ResourceType.USER,
            userId,
            beforeState,
            null,  // afterState is null for deletes
            reason
        );

        // Then cascade
        cancelUserBookings(targetUser);
        deactivateUserCars(targetUser);
        anonymizeUserReviews(targetUser);
        revokeLiveAccess(targetUser);
        gdprService.permanentlyDeleteUser(userId);

        log.info("User {} successfully anonymized by admin {}", userId, admin.getId());
    }
    
    // ==================== RISK SCORING ====================
    
    /**
     * Calculate user risk score (0-100).
     * Higher score = higher fraud/abuse risk.
     * 
     * <p><b>FACTORS:</b>
     * <ul>
     *   <li>Ban history: +40 points</li>
     *   <li>Account locked: +20 points</li>
     *   <li>High cancellation rate: +15 points</li>
     *   <li>Disputes: +10 points each (max 30)</li>
     *   <li>New account (< 30 days): +10 points</li>
     *   <li>No verified phone: +5 points</li>
     * </ul>
     * 
     * @param user User to analyze
     * @return Risk score 0-100
     */
    public Integer calculateRiskScore(User user) {
        int score = 0;
        
        // Ban history (major red flag)
        if (user.isBanned()) {
            score += 40;
        }
        
        // Account locked
        if (user.isLocked()) {
            score += 20;
        }
        
        // New account (potential scammer)
        if (user.getCreatedAt() != null) {
            long daysSinceCreation = ChronoUnit.DAYS.between(
                user.getCreatedAt(), Instant.now());
            if (daysSinceCreation < 30) {
                score += 10;
            }
        }
        
        // No verified phone
        if (user.getPhone() == null || user.getPhone().isEmpty()) {
            score += 5;
        }
        
        // Check booking history for cancellations
        List<Booking> bookings = bookingRepo.findByRenterId(user.getId());
        if (!bookings.isEmpty()) {
            long cancelledCount = bookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CANCELLED)
                .count();
            double cancellationRate = (double) cancelledCount / bookings.size();
            if (cancellationRate > 0.3) {  // > 30% cancellation rate
                score += 15;
            }
        }
        
        // Check dispute history
        try {
            long disputeCount = damageClaimRepo.countByGuestId(user.getId());
            score += Math.min((int) disputeCount * 10, 30);  // Max 30 points for disputes
        } catch (Exception e) {
            log.debug("Could not count disputes for user {}: {}", user.getId(), e.getMessage());
        }
        
        return Math.min(score, 100);  // Cap at 100
    }
    
    /**
     * Get list of risk factors for a user.
     * Human-readable explanations for the risk score.
     * 
     * @param user User to analyze
     * @return List of risk factor descriptions
     */
    public List<String> getRiskFactors(User user) {
        List<String> factors = new ArrayList<>();
        
        if (user.isBanned()) {
            factors.add("Account currently banned" + 
                (user.getBanReason() != null ? ": " + user.getBanReason() : ""));
        }
        
        if (user.isLocked()) {
            factors.add("Account is locked");
        }
        
        if (user.getCreatedAt() != null) {
            long daysSinceCreation = ChronoUnit.DAYS.between(
                user.getCreatedAt(), Instant.now());
            if (daysSinceCreation < 30) {
                factors.add("New account (less than 30 days old)");
            }
        }
        
        if (user.getPhone() == null || user.getPhone().isEmpty()) {
            factors.add("No verified phone number");
        }
        
        // Check cancellation rate
        List<Booking> bookings = bookingRepo.findByRenterId(user.getId());
        if (!bookings.isEmpty()) {
            long cancelledCount = bookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CANCELLED)
                .count();
            double cancellationRate = (double) cancelledCount / bookings.size();
            if (cancellationRate > 0.3) {
                factors.add(String.format("High cancellation rate (%.0f%%)", cancellationRate * 100));
            }
        }
        
        return factors;
    }
    
    // ==================== PRIVATE HELPERS ====================
    
    /**
     * Convert User to AdminUserDto with booking/car counts.
     */
    private AdminUserDto toAdminUserDto(User user) {
        AccountTrustSnapshot trustSnapshot = accountTrustStateService.snapshot(user);
        AdminUserDto dto = AdminUserDto.fromEntity(user);
        dto.setAccountAccessState(trustSnapshot.accountAccessState().name());
        dto.setRegistrationCompletionState(trustSnapshot.registrationCompletionState().name());
        dto.setRenterVerificationState(trustSnapshot.renterVerificationState().name());
        dto.setCanAuthenticate(trustSnapshot.canAuthenticate());
        dto.setCanBookAsRenter(trustSnapshot.canBookAsRenter());
        dto.setRiskScore(calculateRiskScore(user));
        dto.setBookingsCount(bookingRepo.countByRenterId(user.getId()));
        if (user.getRole() == Role.OWNER || user.getRole() == Role.ADMIN) {
            dto.setCarsCount(carRepo.countByOwnerId(user.getId()));
        }
        return dto;
    }
    
    /**
     * Cancel all pending/confirmed bookings for a user.
     */
    private void cancelUserBookings(User user) {
        List<Booking> activeBookings = bookingRepo.findByRenterIdAndStatusIn(
            user.getId(), 
            List.of(BookingStatus.PENDING_APPROVAL, BookingStatus.ACTIVE, BookingStatus.CHECK_IN_OPEN)
        );
        
        for (Booking booking : activeBookings) {
            if (booking.getStatus() == BookingStatus.PENDING_APPROVAL) {
                booking.setStatus(BookingStatus.CANCELLED);
                bookingRepo.save(booking);
            } else {
                cancellationSettlementService.beginFullRefundSettlement(
                        booking,
                        CancelledBy.SYSTEM,
                        CancellationReason.SYSTEM_ADMIN_ACTION,
                        "User deletion cascade",
                        "ADMIN_DELETE_FULL_REFUND"
                );
            }
            log.debug("Cancelled booking {} for deleted user {}", 
                booking.getId(), user.getId());
        }
        
        log.info("Cancelled {} active bookings for user {}", 
            activeBookings.size(), user.getId());
    }
    
    /**
     * Deactivate all cars owned by user.
     */
    private void deactivateUserCars(User user) {
        List<Car> cars = carRepo.findByOwnerId(user.getId());
        
        for (Car car : cars) {
            car.setAvailable(false);
            carRepo.save(car);
            log.debug("Deactivated car {} for deleted user {}", 
                car.getId(), user.getId());
        }
        
        log.info("Deactivated {} cars for user {}", cars.size(), user.getId());
    }
    
    /**
     * Anonymize reviews given/received by user.
     * Keeps review content but removes user reference.
     */
    private void anonymizeUserReviews(User user) {
        // Reviews given by user
        int givenCount = reviewRepo.anonymizeReviewsByReviewerId(user.getId());
        
        // Reviews received by user
        int receivedCount = reviewRepo.anonymizeReviewsByRevieweeId(user.getId());
        
        log.info("Anonymized {} given and {} received reviews for user {}", 
            givenCount, receivedCount, user.getId());
    }

    /**
     * Remove active authentication artifacts before the user email/auth mapping is anonymized.
     */
    private void revokeLiveAccess(User user) {
        refreshTokenService.revokeAll(user.getEmail(), "ADMIN_DELETE_TOMBSTONE");
        userDeviceTokenRepository.deleteByUserId(user.getId());
        passwordResetTokenRepository.deleteByUserId(user.getId());
        passwordHistoryRepository.deleteByUserId(user.getId());

        log.info("Revoked live access artifacts for user {}", user.getId());
    }
}
