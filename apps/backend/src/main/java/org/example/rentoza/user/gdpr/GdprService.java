package org.example.rentoza.user.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GDPR Service - Implements data subject rights under EU GDPR.
 * 
 * <p>Handles:
 * <ul>
 *   <li>Data export (Article 15)</li>
 *   <li>Account deletion (Article 17)</li>
 *   <li>Consent management (Article 7)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GdprService {

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final CarRepository carRepository;
    private final ReviewRepository reviewRepository;
    private final ConsentRepository consentRepository;
    private final PasswordEncoder passwordEncoder;
    private final DataAccessLogRepository dataAccessLogRepository;
    private final ChatServiceClient chatServiceClient;

    // Rate limiting: track last export time per user
    private final Map<Long, LocalDateTime> lastExportTime = new ConcurrentHashMap<>();
    private static final int EXPORT_RATE_LIMIT_HOURS = 24;

    // ==================== Data Export ====================

    @Transactional(readOnly = true)
    public UserDataExportDTO exportUserData(Long userId) {
        // Rate limit check
        LocalDateTime lastExport = lastExportTime.get(userId);
        if (lastExport != null && lastExport.plusHours(EXPORT_RATE_LIMIT_HOURS).isAfter(LocalDateTime.now())) {
            throw new GdprRateLimitException(
                    "Export rate limited",
                    lastExport.plusHours(EXPORT_RATE_LIMIT_HOURS)
            );
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserDataExportDTO export = new UserDataExportDTO();
        export.setExportDate(LocalDateTime.now());
        export.setDataSubjectId(userId);

        // Personal information
        export.setProfile(exportProfile(user));

        // Bookings
        export.setBookings(exportBookings(userId));

        // Reviews
        export.setReviews(exportReviews(userId));

        // Cars (if owner)
        export.setCars(exportCars(user));

        // Consent history
        export.setConsentHistory(exportConsentHistory(userId));

        // GAP-3: Include chat data from chat-service
        try {
            Map<String, Object> chatData = chatServiceClient.exportUserChatData(userId);
            if (!chatData.isEmpty()) {
                export.setChatData(chatData);
            }
        } catch (Exception e) {
            log.warn("GDPR: Failed to include chat data in export for user {} — " +
                    "chat data excluded", userId, e);
        }

        // Track export time
        lastExportTime.put(userId, LocalDateTime.now());
        log.info("GDPR: Data export completed for user {}", userId);

        return export;
    }

    private UserDataExportDTO.ProfileData exportProfile(User user) {
        UserDataExportDTO.ProfileData profile = new UserDataExportDTO.ProfileData();
        profile.setEmail(user.getEmail());
        profile.setFirstName(user.getFirstName());
        profile.setLastName(user.getLastName());
        profile.setPhone(user.getPhone());
        profile.setDateOfBirth(user.getDateOfBirth());
        profile.setRole(user.getRole().name());
        profile.setCreatedAt(toLocalDateTime(user.getCreatedAt()));
        profile.setLastLogin(toLocalDateTime(user.getUpdatedAt())); // Approximation
        return profile;
    }

    private List<UserDataExportDTO.BookingData> exportBookings(Long userId) {
        return bookingRepository.findByRenterId(userId).stream()
                .map(b -> {
                    UserDataExportDTO.BookingData data = new UserDataExportDTO.BookingData();
                    data.setBookingId(b.getId());
                    data.setCarDescription(b.getCar().getBrand() + " " + b.getCar().getModel());
                    data.setStartDate(b.getStartDate().atStartOfDay());
                    data.setEndDate(b.getEndDate().atStartOfDay());
                    data.setStatus(b.getStatus().name());
                    data.setTotalPrice(b.getTotalPrice());
                    return data;
                })
                .collect(Collectors.toList());
    }

    private List<UserDataExportDTO.ReviewData> exportReviews(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return Collections.emptyList();
        
        return reviewRepository.findByReviewerEmailIgnoreCase(user.getEmail()).stream()
                .map(r -> {
                    UserDataExportDTO.ReviewData data = new UserDataExportDTO.ReviewData();
                    data.setReviewId(r.getId());
                    data.setRating(r.getRating());
                    data.setComment(r.getComment());
                    data.setCreatedAt(toLocalDateTime(r.getCreatedAt()));
                    return data;
                })
                .collect(Collectors.toList());
    }

    private List<UserDataExportDTO.CarData> exportCars(User user) {
        return carRepository.findByOwner(user).stream()
                .map(c -> {
                    UserDataExportDTO.CarData data = new UserDataExportDTO.CarData();
                    data.setCarId(c.getId());
                    data.setMake(c.getBrand());
                    data.setModel(c.getModel());
                    data.setYear(c.getYear());
                    data.setListedDate(toLocalDateTime(c.getCreatedAt()));
                    data.setStatus(c.getListingStatus() != null ? c.getListingStatus().name() : "ACTIVE");
                    return data;
                })
                .collect(Collectors.toList());
    }

    private List<UserDataExportDTO.ConsentRecord> exportConsentHistory(Long userId) {
        return consentRepository.findByUserIdOrderByTimestampDesc(userId).stream()
                .map(c -> {
                    UserDataExportDTO.ConsentRecord record = new UserDataExportDTO.ConsentRecord();
                    record.setConsentType(c.getConsentType());
                    record.setGranted(c.isGranted());
                    record.setTimestamp(c.getTimestamp());
                    record.setIpAddress(maskIpAddress(c.getIpAddress()));
                    return record;
                })
                .collect(Collectors.toList());
    }

    // ==================== Account Deletion ====================

    @Transactional
    public AccountDeletionResultDTO initiateAccountDeletion(Long userId, String email, 
                                                            AccountDeletionRequestDTO request) {
        // Verify email matches
        if (!email.equalsIgnoreCase(request.getConfirmEmail())) {
            throw new EmailMismatchException("Email confirmation does not match");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check for active bookings
        List<Booking> activeBookings = bookingRepository.findByRenterId(userId).stream()
                .filter(b -> b.getStatus() == BookingStatus.PENDING_APPROVAL || 
                             b.getStatus() == BookingStatus.APPROVED ||
                             b.getStatus() == BookingStatus.IN_TRIP)
                .toList();

        if (!activeBookings.isEmpty()) {
            throw new ActiveBookingsException(
                    "Cannot delete account with active bookings",
                    activeBookings.stream().map(Booking::getId).collect(Collectors.toList())
            );
        }

        // Schedule deletion (soft delete)
        LocalDate deletionDate = LocalDate.now().plusDays(30);
        user.setDeletionScheduledAt(deletionDate.atStartOfDay());
        user.setDeletionReason(request.getReason());
        userRepository.save(user);

        log.warn("GDPR: Account deletion scheduled for user {} on {}", userId, deletionDate);

        AccountDeletionResultDTO result = new AccountDeletionResultDTO();
        result.setMessage("Account deletion scheduled");
        result.setDeletionDate(deletionDate);
        result.setGracePeriodDays(30);
        result.setCanCancel(true);

        return result;
    }

    @Transactional
    public void cancelAccountDeletion(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getDeletionScheduledAt() == null) {
            throw new NoDeletionPendingException("No deletion request pending");
        }

        user.setDeletionScheduledAt(null);
        user.setDeletionReason(null);
        userRepository.save(user);

        log.info("GDPR: Account deletion cancelled for user {}", userId);
    }

    /**
     * Permanently anonymize user data (called by scheduled job after grace period).
     *
     * <p>GAP-6 fix: clears pseudonymous hash columns (jmbgHash, pibHash, driverLicenseNumberHash)
     * which are SHA-256 identifiers under GDPR Recital 26.
     * <p>GAP-7 fix: sets password to a valid but unknowable BCrypt hash instead of plaintext "DELETED".
     */
    @Transactional
    public void permanentlyDeleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Anonymize personal data
        user.setEmail("deleted_" + userId + "@anonymized.rentoza.rs");
        user.setFirstName("Deleted");
        user.setLastName("User");
        user.setPhone(null);
        user.setDateOfBirth(null);
        user.setDriverLicenseNumber(null);
        user.setJmbg(null);
        user.setPib(null);
        user.setAvatarUrl(null);
        user.setBio(null);
        user.setGoogleId(null);
        user.setAuthUid(null);
        user.setBankAccountNumber(null);
        user.setMonriRecipientId(null);
        user.setConsentIp(null);
        user.setConsentUserAgent(null);

        // GAP-6: Clear pseudonymous hash columns — SHA-256 hashes are still
        // linkable identifiers under GDPR Recital 26 if the original value is known.
        user.setJmbgHash(null);
        user.setPibHash(null);
        user.setDriverLicenseNumberHash(null);

        // GAP-7: Set password to a valid BCrypt hash of a random UUID.
        // Prevents authentication bypass risks from storing plaintext "DELETED".
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setPasswordChangedAt(Instant.now());
        user.setEnabled(false);
        user.setLocked(true);
        user.setRegistrationStatus(RegistrationStatus.DELETED);
        user.setDeletionScheduledAt(null);
        user.setBanned(false);
        user.setBanReason(null);
        user.setBannedAt(null);
        user.setBannedBy(null);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLoginAt(null);
        user.setLastFailedLoginIp(null);
        user.setDeleted(true);

        userRepository.save(user);

        // Delete consent records (no longer needed)
        consentRepository.deleteByUserId(userId);

        // GAP-3: Propagate deletion to chat-service (anonymize messages)
        // Non-blocking: chat-service unavailability must not prevent GDPR erasure
        try {
            boolean chatAnonymized = chatServiceClient.anonymizeUserChatData(userId);
            if (!chatAnonymized) {
                log.warn("GDPR: Chat data anonymization for user {} may be incomplete — " +
                        "manual reconciliation required", userId);
            }
        } catch (Exception e) {
            log.error("GDPR: Chat-service GDPR propagation failed for user {} — " +
                    "ALERT: manual chat data anonymization required", userId, e);
        }

        log.warn("GDPR: User {} permanently anonymized (hash columns cleared)", userId);
    }

    // ==================== Consent Management ====================

    @Transactional(readOnly = true)
    public ConsentPreferencesDTO getConsentPreferences(Long userId) {
        List<UserConsent> consents = consentRepository.findLatestByUserId(userId);

        ConsentPreferencesDTO preferences = new ConsentPreferencesDTO();
        preferences.setUserId(userId);
        preferences.setMarketingEmails(getConsentValue(consents, "MARKETING_EMAILS"));
        preferences.setSmsNotifications(getConsentValue(consents, "SMS_NOTIFICATIONS"));
        preferences.setAnalyticsTracking(getConsentValue(consents, "ANALYTICS_TRACKING"));
        preferences.setThirdPartySharing(getConsentValue(consents, "THIRD_PARTY_SHARING"));
        preferences.setLastUpdated(getLatestConsentTime(consents));

        return preferences;
    }

    /**
     * Update consent preferences with real request provenance.
     *
     * <p>GAP-4 fix: captures actual client IP and User-Agent instead of hardcoded "0.0.0.0".
     * GDPR Article 7(1) requires demonstrable evidence that consent was given.
     *
     * @param userId    the user updating their consent
     * @param preferences the new consent settings
     * @param ipAddress real client IP from HttpServletRequest
     * @param userAgent User-Agent header from HttpServletRequest
     * @return updated consent preferences
     */
    @Transactional
    public ConsentPreferencesDTO updateConsentPreferences(Long userId, ConsentPreferencesDTO preferences,
                                                          String ipAddress, String userAgent) {
        LocalDateTime now = LocalDateTime.now();

        saveConsent(userId, "MARKETING_EMAILS", preferences.isMarketingEmails(), now, ipAddress, userAgent);
        saveConsent(userId, "SMS_NOTIFICATIONS", preferences.isSmsNotifications(), now, ipAddress, userAgent);
        saveConsent(userId, "ANALYTICS_TRACKING", preferences.isAnalyticsTracking(), now, ipAddress, userAgent);
        saveConsent(userId, "THIRD_PARTY_SHARING", preferences.isThirdPartySharing(), now, ipAddress, userAgent);

        log.info("GDPR: Consent preferences updated for user {} from IP {}", userId, ipAddress);
        return getConsentPreferences(userId);
    }

    private void saveConsent(Long userId, String type, boolean granted, LocalDateTime timestamp,
                             String ip, String userAgent) {
        UserConsent consent = new UserConsent();
        consent.setUserId(userId);
        consent.setConsentType(type);
        consent.setGranted(granted);
        consent.setTimestamp(timestamp);
        consent.setIpAddress(ip);
        consent.setUserAgent(userAgent);
        consentRepository.save(consent);
    }

    private boolean getConsentValue(List<UserConsent> consents, String type) {
        return consents.stream()
                .filter(c -> c.getConsentType().equals(type))
                .findFirst()
                .map(UserConsent::isGranted)
                .orElse(false);
    }

    private LocalDateTime getLatestConsentTime(List<UserConsent> consents) {
        return consents.stream()
                .map(UserConsent::getTimestamp)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    // ==================== Data Access Log ====================

    /**
     * Query real data access audit log entries for a user.
     *
     * <p>GAP-5 fix: replaces the previous hardcoded stub that returned fabricated
     * sample data regardless of user. Now queries the persisted {@link DataAccessLog}
     * table and returns factual user-specific access records.
     *
     * @param userId the user whose access log to retrieve
     * @param days   number of days to look back
     * @return real data access log entries, mapped to DTOs
     */
    @Transactional(readOnly = true)
    public List<DataAccessLogEntry> getDataAccessLog(Long userId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<DataAccessLog> logs = dataAccessLogRepository.findByUserIdAndTimestampAfter(userId, since);
        return logs.stream()
                .map(entry -> new DataAccessLogEntry(
                        entry.getTimestamp(),
                        entry.getAction(),
                        entry.getDescription(),
                        entry.getSource()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Record a data access event for audit trail purposes.
     *
     * @param userId       the user whose data was accessed
     * @param accessorId   who accessed it (null for system operations)
     * @param accessorType USER, ADMIN, or SYSTEM
     * @param action       e.g., VIEW_PROFILE, EXPORT_DATA, VIEW_DOCUMENT
     * @param description  human-readable description
     * @param source       e.g., Web App, Admin Panel, API
     * @param ipAddress    accessor's IP (nullable)
     */
    @Transactional
    public void logDataAccess(Long userId, Long accessorId, String accessorType,
                              String action, String description, String source,
                              String ipAddress) {
        DataAccessLog entry = DataAccessLog.of(userId, accessorId, accessorType,
                action, description, source, ipAddress);
        dataAccessLogRepository.save(entry);
    }

    // ==================== Helpers ====================

    private String maskIpAddress(String ip) {
        if (ip == null) return null;
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".xxx.xxx";
        }
        return "xxx.xxx.xxx.xxx";
    }

    /**
     * Converts Instant to LocalDateTime in Serbia timezone.
     */
    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, ZoneId.of("Europe/Belgrade"));
    }
}
