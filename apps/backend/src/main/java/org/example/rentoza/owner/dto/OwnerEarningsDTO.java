package org.example.rentoza.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for owner earnings page
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OwnerEarningsDTO {
    private BigDecimal totalEarnings;
    private BigDecimal monthlyEarnings;
    private BigDecimal yearlyEarnings;
    private Integer totalBookings;
    private List<CarEarningDTO> carEarnings;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarEarningDTO {
        private Long carId;
        private String carBrand;
        private String carModel;
        private BigDecimal earnings;
        private Integer bookingCount;
        private List<BookingDetailDTO> bookingDetails; // Added: individual booking details
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingDetailDTO {
        private Long bookingId;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal totalPrice;
        private String status;
    }
}
