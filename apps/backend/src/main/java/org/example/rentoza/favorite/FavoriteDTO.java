package org.example.rentoza.favorite;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for Favorite entity with embedded car information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteDTO {
    private Long id;
    private Long userId;
    private Long carId;

    // Embedded car information for convenience
    private String carBrand;
    private String carModel;
    private Integer carYear;
    private BigDecimal carPricePerDay;
    private String carLocation;
    private String carImageUrl;
    private Boolean carAvailable;

    private Instant createdAt;
}
