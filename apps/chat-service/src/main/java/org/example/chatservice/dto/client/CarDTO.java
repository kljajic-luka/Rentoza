package org.example.chatservice.dto.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for car information from the main backend
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarDTO {
    private Long id;
    private String brand;
    private String model;
    private Integer year;
}
