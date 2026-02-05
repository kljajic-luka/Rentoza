package org.example.rentoza.user.gdpr;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for GDPR Article 15 data export.
 * Contains all personal data associated with a user's account.
 */
@Data
public class UserDataExportDTO {
    
    private LocalDateTime exportDate;
    private Long dataSubjectId;
    private String exportVersion = "1.0";
    
    private ProfileData profile;
    private List<BookingData> bookings;
    private List<ReviewData> reviews;
    private List<CarData> cars;
    private List<ConsentRecord> consentHistory;
    
    @Data
    public static class ProfileData {
        private String email;
        private String firstName;
        private String lastName;
        private String phone;
        private LocalDate dateOfBirth;
        private String role;
        private LocalDateTime createdAt;
        private LocalDateTime lastLogin;
    }
    
    @Data
    public static class BookingData {
        private Long bookingId;
        private String carDescription;
        private LocalDateTime startDate;
        private LocalDateTime endDate;
        private String status;
        private BigDecimal totalPrice;
    }
    
    @Data
    public static class ReviewData {
        private Long reviewId;
        private Integer rating;
        private String comment;
        private LocalDateTime createdAt;
    }
    
    @Data
    public static class CarData {
        private Long carId;
        private String make;
        private String model;
        private Integer year;
        private LocalDateTime listedDate;
        private String status;
    }
    
    @Data
    public static class ConsentRecord {
        private String consentType;
        private boolean granted;
        private LocalDateTime timestamp;
        private String ipAddress;
    }
}
