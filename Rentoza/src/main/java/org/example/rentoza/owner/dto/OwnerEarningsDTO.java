package org.example.rentoza.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * DTO for owner earnings page
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OwnerEarningsDTO {
    private Double totalEarnings;
    private Double monthlyEarnings;
    private Double yearlyEarnings;
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
        private Double earnings;
        private Integer bookingCount;
    }
}
