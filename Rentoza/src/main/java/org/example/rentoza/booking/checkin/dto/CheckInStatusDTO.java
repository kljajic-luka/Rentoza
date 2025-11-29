package org.example.rentoza.booking.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.BookingStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for check-in status.
 * 
 * <p>Provides complete view of check-in progress for both host and guest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInStatusDTO {

    private Long bookingId;
    private String checkInSessionId;
    private BookingStatus status;

    // Phase completion
    private boolean hostCheckInComplete;
    private boolean guestCheckInComplete;
    private boolean handshakeReady;

    // Timestamps
    private LocalDateTime checkInOpenedAt;
    private LocalDateTime hostCompletedAt;
    private LocalDateTime guestCompletedAt;
    private LocalDateTime handshakeCompletedAt;

    // Host data (visible to guest after host completes)
    private List<CheckInPhotoDTO> vehiclePhotos;
    private Integer odometerReading;
    private Integer fuelLevelPercent;

    // Remote handoff
    private boolean lockboxAvailable;
    private boolean geofenceValid;
    private Integer geofenceDistanceMeters;

    // Deadlines
    private LocalDateTime tripStartScheduled;
    private LocalDateTime noShowDeadline;
    private Long minutesUntilNoShow;

    // Role-specific flags
    private boolean isHost;
    private boolean isGuest;

    // Car info (for display)
    private CarSummaryDTO car;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarSummaryDTO {
        private Long id;
        private String brand;
        private String model;
        private Integer year;
        private String imageUrl;
    }
}
