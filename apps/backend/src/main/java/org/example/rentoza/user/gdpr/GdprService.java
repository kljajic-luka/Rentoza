package org.example.rentoza.user.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
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
        user.setPassword("DELETED");
        user.setDeleted(true);

        userRepository.save(user);

        // Delete consent records (no longer needed)
        consentRepository.deleteByUserId(userId);

        log.warn("GDPR: User {} permanently anonymized", userId);
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

    @Transactional
    public ConsentPreferencesDTO updateConsentPreferences(Long userId, ConsentPreferencesDTO preferences) {
        LocalDateTime now = LocalDateTime.now();
        String ipAddress = "0.0.0.0"; // Would come from request in real implementation

        saveConsent(userId, "MARKETING_EMAILS", preferences.isMarketingEmails(), now, ipAddress);
        saveConsent(userId, "SMS_NOTIFICATIONS", preferences.isSmsNotifications(), now, ipAddress);
        saveConsent(userId, "ANALYTICS_TRACKING", preferences.isAnalyticsTracking(), now, ipAddress);
        saveConsent(userId, "THIRD_PARTY_SHARING", preferences.isThirdPartySharing(), now, ipAddress);

        log.info("GDPR: Consent preferences updated for user {}", userId);
        return getConsentPreferences(userId);
    }

    private void saveConsent(Long userId, String type, boolean granted, LocalDateTime timestamp, String ip) {
        UserConsent consent = new UserConsent();
        consent.setUserId(userId);
        consent.setConsentType(type);
        consent.setGranted(granted);
        consent.setTimestamp(timestamp);
        consent.setIpAddress(ip);
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

    @Transactional(readOnly = true)
    public List<DataAccessLogEntry> getDataAccessLog(Long userId, int days) {
        // In production, this would query an audit log table
        // For now, return a sample structure
        return List.of(
                new DataAccessLogEntry(
                        LocalDateTime.now().minusDays(1),
                        "Profile viewed",
                        "User logged in and viewed profile",
                        "Web App"
                ),
                new DataAccessLogEntry(
                        LocalDateTime.now().minusDays(3),
                        "Booking created",
                        "New booking request submitted",
                        "Mobile App"
                )
        );
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
