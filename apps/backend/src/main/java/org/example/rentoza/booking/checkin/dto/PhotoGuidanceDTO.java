package org.example.rentoza.booking.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInPhotoType;

import java.time.Duration;
import java.util.List;

/**
 * DTO providing detailed guidance for capturing a specific photo type.
 * Used by the frontend to guide users through the structured 8-point photo protocol.
 * 
 * <h2>Purpose</h2>
 * <ul>
 *   <li>Provides step-by-step instructions in Serbian and English</li>
 *   <li>Links to silhouette overlay SVG for camera alignment</li>
 *   <li>Specifies expected camera angle/position</li>
 *   <li>Lists common mistakes to avoid</li>
 * </ul>
 * 
 * @since Enterprise Upgrade Phase 2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoGuidanceDTO {
    
    /**
     * The photo type this guidance applies to.
     */
    private CheckInPhotoType photoType;
    
    /**
     * Display name for the photo type.
     * Example: "Prednja strana" (Serbian), "Front Exterior" (English)
     */
    private String displayName;
    
    /**
     * Display name in English.
     */
    private String displayNameEn;
    
    /**
     * Sequence order (1-8 for exterior, 9-12 for interior).
     */
    private int sequenceOrder;
    
    /**
     * Total required photos in this category.
     */
    private int totalInCategory;
    
    /**
     * Category: "exterior" or "interior"
     */
    private String category;
    
    /**
     * Detailed instructions for capturing this photo (Serbian).
     */
    private String instructionsSr;
    
    /**
     * Detailed instructions for capturing this photo (English).
     */
    private String instructionsEn;
    
    /**
     * URL to the silhouette overlay SVG.
     * Displayed semi-transparently over the camera preview.
     */
    private String silhouetteUrl;
    
    /**
     * Expected camera angle/position.
     */
    private PhotoAngle expectedAngle;
    
    /**
     * Estimated time to capture this photo.
     */
    private Duration estimatedDuration;
    
    /**
     * Common mistakes to avoid (in Serbian).
     */
    private List<String> commonMistakesSr;
    
    /**
     * Common mistakes to avoid (in English).
     */
    private List<String> commonMistakesEn;
    
    /**
     * Tips for getting a good photo (in Serbian).
     */
    private List<String> tipsSr;
    
    /**
     * Tips for getting a good photo (in English).
     */
    private List<String> tipsEn;
    
    /**
     * Whether this photo is required (true) or optional (false).
     */
    private boolean required;
    
    /**
     * Minimum distance from vehicle in meters (for exterior photos).
     */
    private Integer minDistanceMeters;
    
    /**
     * Maximum distance from vehicle in meters (for exterior photos).
     */
    private Integer maxDistanceMeters;
    
    /**
     * What should be visible in this photo (checklist items).
     */
    private List<String> visibilityChecklistSr;
    
    /**
     * What should be visible in this photo (checklist items in English).
     */
    private List<String> visibilityChecklistEn;
    
    /**
     * Photo angles enum.
     */
    public enum PhotoAngle {
        FRONT_FACING("Front", "Prednja strana"),
        FRONT_LEFT_45("45° from front-left", "45° sa prednje leve strane"),
        LEFT_PROFILE("Full left side", "Leva strana"),
        REAR_LEFT_45("45° from rear-left", "45° sa zadnje leve strane"),
        REAR_FACING("Rear", "Zadnja strana"),
        REAR_RIGHT_45("45° from rear-right", "45° sa zadnje desne strane"),
        RIGHT_PROFILE("Full right side", "Desna strana"),
        FRONT_RIGHT_45("45° from front-right", "45° sa prednje desne strane"),
        // Interior
        DASHBOARD("Dashboard with odometer visible", "Kontrolna tabla sa kilometražom"),
        FRONT_SEATS("Both front seats", "Prednja sedišta"),
        REAR_SEATS("Full rear seat area", "Zadnja sedišta"),
        TRUNK_BOOT("Cargo area", "Prtljažnik"),
        // Readings
        ODOMETER_CLOSEUP("Close-up of odometer", "Kilometraža izbliza"),
        FUEL_GAUGE_CLOSEUP("Close-up of fuel gauge", "Pokazivač goriva izbliza");
        
        private final String displayNameEn;
        private final String displayNameSr;
        
        PhotoAngle(String displayNameEn, String displayNameSr) {
            this.displayNameEn = displayNameEn;
            this.displayNameSr = displayNameSr;
        }
        
        public String getDisplayNameEn() { return displayNameEn; }
        public String getDisplayNameSr() { return displayNameSr; }
    }
}
