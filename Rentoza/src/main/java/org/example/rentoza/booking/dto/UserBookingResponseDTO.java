package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserBookingResponseDTO {
    private Long id;
    private Long carId;
    private String carBrand;
    private String carModel;
    private Integer carYear;
    private String carImageUrl;
    private String carLocation;
    private BigDecimal carPricePerDay;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal totalPrice;
    private String status;

    // Review information
    private Boolean hasReview;
    private Integer reviewRating;
    private String reviewComment;
}
