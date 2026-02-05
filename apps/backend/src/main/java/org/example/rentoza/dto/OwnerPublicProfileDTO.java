package org.example.rentoza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerPublicProfileDTO {
    private Long id;
    private String firstName;
    private String lastName; // Full name for public profile
    private String avatarUrl;
    private String joinDate; // e.g., "Joined Oct 2021"
    private String about;
    
    private Double averageRating;
    private int totalTrips;
    
    private String responseTime; // e.g., "1 hour"
    private String responseRate; // e.g., "100%"
    
    private boolean isSuperHost;
    
    private List<ReviewPreviewDTO> recentReviews;
    private List<OwnerCarPreviewDTO> cars;
}
