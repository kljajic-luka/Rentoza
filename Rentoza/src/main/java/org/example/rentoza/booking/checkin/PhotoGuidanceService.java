package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.PhotoGuidanceDTO;
import org.example.rentoza.booking.checkin.dto.PhotoGuidanceDTO.PhotoAngle;
import org.example.rentoza.booking.checkin.dto.PhotoSequenceValidationDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service providing guided photo capture instructions and sequence validation.
 * 
 * <h2>Purpose</h2>
 * Implements the structured 8-point photo protocol for enterprise-grade
 * vehicle documentation. Provides detailed guidance for each photo type
 * including positioning, angles, and common mistakes to avoid.
 * 
 * <h2>Photo Protocol</h2>
 * <pre>
 * EXTERIOR (8 photos, counterclockwise):
 *   1. Front       - Full vehicle with license plate
 *   2. Front-Left  - 45° angle showing driver corner
 *   3. Left Side   - Full profile
 *   4. Rear-Left   - 45° angle showing rear corner
 *   5. Rear        - Full rear with license plate
 *   6. Rear-Right  - 45° angle
 *   7. Right Side  - Full profile
 *   8. Front-Right - 45° angle
 * 
 * INTERIOR (4 photos):
 *   9. Dashboard   - Steering wheel, center console
 *   10. Front Seats - Both front seats
 *   11. Rear Seats  - Full rear area
 *   12. Odometer   - Close-up reading
 * </pre>
 * 
 * @since Enterprise Upgrade Phase 2
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoGuidanceService {
    
    @Value("${app.checkin.photo.silhouette-base-url:/assets/silhouettes}")
    private String silhouetteBaseUrl;
    
    /**
     * Default distance range for exterior photos.
     */
    private static final int MIN_EXTERIOR_DISTANCE_METERS = 3;
    private static final int MAX_EXTERIOR_DISTANCE_METERS = 8;
    
    /**
     * Get detailed guidance for a specific photo type.
     * 
     * @param photoType The photo type to get guidance for
     * @return PhotoGuidanceDTO with instructions, silhouette URL, etc.
     */
    public PhotoGuidanceDTO getGuidance(CheckInPhotoType photoType) {
        return switch (photoType) {
            // Guest Check-In Photos
            case GUEST_EXTERIOR_FRONT -> buildExteriorGuidance(
                photoType, 1, "Prednja strana", "Front Exterior",
                PhotoAngle.FRONT_FACING,
                "Fotografišite prednju stranu vozila sa 5-8 metara udaljenosti. " +
                "Registarska tablica mora biti jasno vidljiva.",
                "Photograph the front of the vehicle from 5-8 meters away. " +
                "License plate must be clearly visible.",
                List.of("Tablica nije vidljiva", "Preblizu vozilu", "Loše osvetljenje"),
                List.of("License plate not visible", "Too close to vehicle", "Poor lighting"),
                List.of("Tablica jasno vidljiva", "Prednja svetla vidljiva", "Celo vozilo u kadru"),
                List.of("License plate clearly visible", "Headlights visible", "Entire vehicle in frame")
            );
            
            case GUEST_EXTERIOR_LEFT -> buildExteriorGuidance(
                photoType, 3, "Leva strana", "Left Side",
                PhotoAngle.LEFT_PROFILE,
                "Fotografišite levu stranu vozila u punom profilu. " +
                "Stanite paralelno sa vozilom na 5-8 metara.",
                "Photograph the full left side profile of the vehicle. " +
                "Stand parallel to the vehicle at 5-8 meters.",
                List.of("Ugao nije paralelan", "Deo vozila je odsečen", "Senke prekrivaju karoseriju"),
                List.of("Angle is not parallel", "Part of vehicle is cut off", "Shadows cover bodywork"),
                List.of("Sva 4 točka vidljiva", "Retrovizor vidljiv", "Bočni deo u celosti"),
                List.of("All 4 wheels visible", "Side mirror visible", "Complete side profile")
            );
            
            case GUEST_EXTERIOR_RIGHT -> buildExteriorGuidance(
                photoType, 7, "Desna strana", "Right Side",
                PhotoAngle.RIGHT_PROFILE,
                "Fotografišite desnu stranu vozila u punom profilu. " +
                "Stanite paralelno sa vozilom na 5-8 metara.",
                "Photograph the full right side profile of the vehicle. " +
                "Stand parallel to the vehicle at 5-8 meters.",
                List.of("Ugao nije paralelan", "Deo vozila je odsečen", "Senke prekrivaju karoseriju"),
                List.of("Angle is not parallel", "Part of vehicle is cut off", "Shadows cover bodywork"),
                List.of("Sva 4 točka vidljiva", "Retrovizor vidljiv", "Bočni deo u celosti"),
                List.of("All 4 wheels visible", "Side mirror visible", "Complete side profile")
            );
            
            case GUEST_EXTERIOR_REAR -> buildExteriorGuidance(
                photoType, 5, "Zadnja strana", "Rear Exterior",
                PhotoAngle.REAR_FACING,
                "Fotografišite zadnju stranu vozila sa 5-8 metara udaljenosti. " +
                "Registarska tablica mora biti jasno vidljiva.",
                "Photograph the rear of the vehicle from 5-8 meters away. " +
                "License plate must be clearly visible.",
                List.of("Tablica nije vidljiva", "Prtljažnik je otvoren", "Loše osvetljenje"),
                List.of("License plate not visible", "Trunk is open", "Poor lighting"),
                List.of("Tablica jasno vidljiva", "Zadnja svetla vidljiva", "Celo vozilo u kadru"),
                List.of("License plate clearly visible", "Tail lights visible", "Entire vehicle in frame")
            );
            
            case GUEST_INTERIOR_DASHBOARD -> buildInteriorGuidance(
                photoType, 1, "Kontrolna tabla", "Dashboard",
                PhotoAngle.DASHBOARD,
                "Fotografišite kontrolnu tablu iz pozicije vozača. " +
                "Volan, instrument tabla i centralna konzola moraju biti vidljivi.",
                "Photograph the dashboard from the driver's position. " +
                "Steering wheel, instrument panel, and center console must be visible.",
                List.of("Kilometraža nije vidljiva", "Previše tamno", "Odraz na staklu"),
                List.of("Odometer not visible", "Too dark", "Reflection on glass"),
                List.of("Volan vidljiv", "Instrument tabla vidljiva", "Centralna konzola vidljiva"),
                List.of("Steering wheel visible", "Instrument panel visible", "Center console visible")
            );
            
            case GUEST_INTERIOR_REAR -> buildInteriorGuidance(
                photoType, 3, "Zadnja sedišta", "Rear Seats",
                PhotoAngle.REAR_SEATS,
                "Fotografišite zadnja sedišta iz prednjeg dela vozila. " +
                "Pokažite stanje sedišta i podnog prostora.",
                "Photograph the rear seats from the front of the vehicle. " +
                "Show the condition of seats and floor space.",
                List.of("Deo sedišta nije vidljiv", "Previše tamno", "Predmeti blokiraju pogled"),
                List.of("Part of seats not visible", "Too dark", "Objects blocking view"),
                List.of("Oba zadnja sedišta vidljiva", "Pod vidljiv", "Dobro osvetljeno"),
                List.of("Both rear seats visible", "Floor visible", "Well lit")
            );
            
            case GUEST_ODOMETER -> buildReadingGuidance(
                photoType, "Kilometraža", "Odometer",
                PhotoAngle.ODOMETER_CLOSEUP,
                "Fotografišite kilometražu izbliza. " +
                "Brojke moraju biti jasno čitljive. Motor mora biti upaljen.",
                "Photograph the odometer close-up. " +
                "Numbers must be clearly readable. Engine must be on.",
                List.of("Brojke nisu čitljive", "Odraz na staklu", "Motor ugašen"),
                List.of("Numbers not readable", "Reflection on glass", "Engine off"),
                List.of("Sve cifre jasno vidljive", "Bez odraza", "Fokusirano"),
                List.of("All digits clearly visible", "No reflections", "In focus")
            );
            
            case GUEST_FUEL_GAUGE -> buildReadingGuidance(
                photoType, "Pokazivač goriva", "Fuel Gauge",
                PhotoAngle.FUEL_GAUGE_CLOSEUP,
                "Fotografišite pokazivač goriva izbliza. " +
                "Nivo goriva mora biti jasno vidljiv. Motor mora biti upaljen.",
                "Photograph the fuel gauge close-up. " +
                "Fuel level must be clearly visible. Engine must be on.",
                List.of("Nivo nije vidljiv", "Odraz na staklu", "Zamućeno"),
                List.of("Level not visible", "Reflection on glass", "Blurry"),
                List.of("Kazaljka jasno vidljiva", "Skala čitljiva", "Dobro osvetljeno"),
                List.of("Needle clearly visible", "Scale readable", "Well lit")
            );
            
            // Host Check-In Photos (same guidance as guest)
            case HOST_EXTERIOR_FRONT -> buildExteriorGuidance(
                photoType, 1, "Prednja strana", "Front Exterior",
                PhotoAngle.FRONT_FACING,
                "Fotografišite prednju stranu vozila sa 5-8 metara udaljenosti. " +
                "Registarska tablica mora biti jasno vidljiva.",
                "Photograph the front of the vehicle from 5-8 meters away. " +
                "License plate must be clearly visible.",
                List.of("Tablica nije vidljiva", "Preblizu vozilu", "Loše osvetljenje"),
                List.of("License plate not visible", "Too close to vehicle", "Poor lighting"),
                List.of("Tablica jasno vidljiva", "Prednja svetla vidljiva", "Celo vozilo u kadru"),
                List.of("License plate clearly visible", "Headlights visible", "Entire vehicle in frame")
            );
            
            case HOST_EXTERIOR_LEFT -> buildExteriorGuidance(
                photoType, 3, "Leva strana", "Left Side",
                PhotoAngle.LEFT_PROFILE,
                "Fotografišite levu stranu vozila u punom profilu.",
                "Photograph the full left side profile of the vehicle.",
                List.of("Ugao nije paralelan", "Deo vozila je odsečen"),
                List.of("Angle is not parallel", "Part of vehicle is cut off"),
                List.of("Sva 4 točka vidljiva", "Bočni deo u celosti"),
                List.of("All 4 wheels visible", "Complete side profile")
            );
            
            case HOST_EXTERIOR_RIGHT -> buildExteriorGuidance(
                photoType, 7, "Desna strana", "Right Side",
                PhotoAngle.RIGHT_PROFILE,
                "Fotografišite desnu stranu vozila u punom profilu.",
                "Photograph the full right side profile of the vehicle.",
                List.of("Ugao nije paralelan", "Deo vozila je odsečen"),
                List.of("Angle is not parallel", "Part of vehicle is cut off"),
                List.of("Sva 4 točka vidljiva", "Bočni deo u celosti"),
                List.of("All 4 wheels visible", "Complete side profile")
            );
            
            case HOST_EXTERIOR_REAR -> buildExteriorGuidance(
                photoType, 5, "Zadnja strana", "Rear Exterior",
                PhotoAngle.REAR_FACING,
                "Fotografišite zadnju stranu vozila sa 5-8 metara udaljenosti.",
                "Photograph the rear of the vehicle from 5-8 meters away.",
                List.of("Tablica nije vidljiva", "Prtljažnik je otvoren"),
                List.of("License plate not visible", "Trunk is open"),
                List.of("Tablica jasno vidljiva", "Zadnja svetla vidljiva"),
                List.of("License plate clearly visible", "Tail lights visible")
            );
            
            case HOST_INTERIOR_DASHBOARD -> buildInteriorGuidance(
                photoType, 1, "Kontrolna tabla", "Dashboard",
                PhotoAngle.DASHBOARD,
                "Fotografišite kontrolnu tablu iz pozicije vozača.",
                "Photograph the dashboard from the driver's position.",
                List.of("Kilometraža nije vidljiva", "Previše tamno"),
                List.of("Odometer not visible", "Too dark"),
                List.of("Volan vidljiv", "Instrument tabla vidljiva"),
                List.of("Steering wheel visible", "Instrument panel visible")
            );
            
            case HOST_INTERIOR_REAR -> buildInteriorGuidance(
                photoType, 3, "Zadnja sedišta", "Rear Seats",
                PhotoAngle.REAR_SEATS,
                "Fotografišite zadnja sedišta iz prednjeg dela vozila.",
                "Photograph the rear seats from the front of the vehicle.",
                List.of("Deo sedišta nije vidljiv", "Previše tamno"),
                List.of("Part of seats not visible", "Too dark"),
                List.of("Oba zadnja sedišta vidljiva", "Pod vidljiv"),
                List.of("Both rear seats visible", "Floor visible")
            );
            
            case HOST_ODOMETER -> buildReadingGuidance(
                photoType, "Kilometraža", "Odometer",
                PhotoAngle.ODOMETER_CLOSEUP,
                "Fotografišite kilometražu izbliza. Brojke moraju biti jasno čitljive.",
                "Photograph the odometer close-up. Numbers must be clearly readable.",
                List.of("Brojke nisu čitljive", "Odraz na staklu"),
                List.of("Numbers not readable", "Reflection on glass"),
                List.of("Sve cifre jasno vidljive", "Fokusirano"),
                List.of("All digits clearly visible", "In focus")
            );
            
            case HOST_FUEL_GAUGE -> buildReadingGuidance(
                photoType, "Pokazivač goriva", "Fuel Gauge",
                PhotoAngle.FUEL_GAUGE_CLOSEUP,
                "Fotografišite pokazivač goriva izbliza.",
                "Photograph the fuel gauge close-up.",
                List.of("Nivo nije vidljiv", "Odraz na staklu"),
                List.of("Level not visible", "Reflection on glass"),
                List.of("Kazaljka jasno vidljiva", "Dobro osvetljeno"),
                List.of("Needle clearly visible", "Well lit")
            );
            
            // Host Checkout Photos
            case HOST_CHECKOUT_EXTERIOR_FRONT, HOST_CHECKOUT_EXTERIOR_REAR,
                 HOST_CHECKOUT_EXTERIOR_LEFT, HOST_CHECKOUT_EXTERIOR_RIGHT,
                 HOST_CHECKOUT_INTERIOR_DASHBOARD, HOST_CHECKOUT_INTERIOR_REAR,
                 HOST_CHECKOUT_ODOMETER, HOST_CHECKOUT_FUEL_GAUGE -> {
                // Reuse check-in guidance with checkout context
                CheckInPhotoType correspondingType = mapCheckoutToCheckinType(photoType);
                PhotoGuidanceDTO base = getGuidance(correspondingType);
                yield PhotoGuidanceDTO.builder()
                    .photoType(photoType)
                    .displayName(base.getDisplayName())
                    .displayNameEn(base.getDisplayNameEn())
                    .sequenceOrder(base.getSequenceOrder())
                    .totalInCategory(base.getTotalInCategory())
                    .category(base.getCategory())
                    .instructionsSr("Povratna fotografija: " + base.getInstructionsSr())
                    .instructionsEn("Return photo: " + base.getInstructionsEn())
                    .silhouetteUrl(base.getSilhouetteUrl())
                    .expectedAngle(base.getExpectedAngle())
                    .estimatedDuration(base.getEstimatedDuration())
                    .commonMistakesSr(base.getCommonMistakesSr())
                    .commonMistakesEn(base.getCommonMistakesEn())
                    .tipsSr(List.of("Uporedite sa fotografijom od prijema", "Dokumentujte sve promene"))
                    .tipsEn(List.of("Compare with check-in photo", "Document any changes"))
                    .required(true)
                    .minDistanceMeters(base.getMinDistanceMeters())
                    .maxDistanceMeters(base.getMaxDistanceMeters())
                    .visibilityChecklistSr(base.getVisibilityChecklistSr())
                    .visibilityChecklistEn(base.getVisibilityChecklistEn())
                    .build();
            }
            
            default -> PhotoGuidanceDTO.builder()
                .photoType(photoType)
                .displayName(photoType.name())
                .displayNameEn(photoType.name())
                .sequenceOrder(0)
                .totalInCategory(1)
                .category("other")
                .instructionsSr("Fotografišite prema potrebi.")
                .instructionsEn("Photograph as needed.")
                .required(false)
                .estimatedDuration(Duration.ofSeconds(30))
                .build();
        };
    }
    
    /**
     * Get guidance for all required guest check-in photos in order.
     */
    public List<PhotoGuidanceDTO> getGuestCheckInGuidanceSequence() {
        return Arrays.stream(CheckInPhotoType.getRequiredGuestCheckInTypes())
            .map(this::getGuidance)
            .sorted(Comparator.comparingInt(PhotoGuidanceDTO::getSequenceOrder))
            .collect(Collectors.toList());
    }
    
    /**
     * Get guidance for all required host check-in photos in order.
     */
    public List<PhotoGuidanceDTO> getHostCheckInGuidanceSequence() {
        return Arrays.stream(CheckInPhotoType.values())
            .filter(CheckInPhotoType::isRequiredForHost)
            .map(this::getGuidance)
            .sorted(Comparator.comparingInt(PhotoGuidanceDTO::getSequenceOrder))
            .collect(Collectors.toList());
    }
    
    /**
     * Get guidance for all required host checkout photos in order.
     */
    public List<PhotoGuidanceDTO> getHostCheckoutGuidanceSequence() {
        return Arrays.stream(CheckInPhotoType.getRequiredHostCheckoutTypes())
            .map(this::getGuidance)
            .sorted(Comparator.comparingInt(PhotoGuidanceDTO::getSequenceOrder))
            .collect(Collectors.toList());
    }
    
    /**
     * Validate that all required photo types have been submitted.
     * 
     * @param submittedTypes List of photo types that have been uploaded
     * @param requiresAll Whether all types must be present (true) or just count (false)
     * @return Validation result with missing types if any
     */
    public PhotoSequenceValidationDTO validateGuestCheckInSequence(
            List<CheckInPhotoType> submittedTypes,
            boolean requiresAll) {
        
        Set<CheckInPhotoType> submitted = new HashSet<>(submittedTypes);
        Set<CheckInPhotoType> required = Set.of(CheckInPhotoType.getRequiredGuestCheckInTypes());
        
        if (requiresAll) {
            List<CheckInPhotoType> missing = required.stream()
                .filter(t -> !submitted.contains(t))
                .collect(Collectors.toList());
            
            if (!missing.isEmpty()) {
                return PhotoSequenceValidationDTO.invalid(
                    missing,
                    submitted.size(),
                    required.size()
                );
            }
        }
        
        return PhotoSequenceValidationDTO.valid(required.size());
    }
    
    /**
     * Validate host checkout photo sequence.
     */
    public PhotoSequenceValidationDTO validateHostCheckoutSequence(
            List<CheckInPhotoType> submittedTypes,
            boolean requiresAll) {
        
        Set<CheckInPhotoType> submitted = new HashSet<>(submittedTypes);
        Set<CheckInPhotoType> required = Set.of(CheckInPhotoType.getRequiredHostCheckoutTypes());
        
        if (requiresAll) {
            List<CheckInPhotoType> missing = required.stream()
                .filter(t -> !submitted.contains(t))
                .collect(Collectors.toList());
            
            if (!missing.isEmpty()) {
                return PhotoSequenceValidationDTO.invalid(
                    missing,
                    submitted.size(),
                    required.size()
                );
            }
        }
        
        return PhotoSequenceValidationDTO.valid(required.size());
    }
    
    // ========== Private Helper Methods ==========
    
    private PhotoGuidanceDTO buildExteriorGuidance(
            CheckInPhotoType photoType,
            int sequenceOrder,
            String displayNameSr,
            String displayNameEn,
            PhotoAngle expectedAngle,
            String instructionsSr,
            String instructionsEn,
            List<String> commonMistakesSr,
            List<String> commonMistakesEn,
            List<String> visibilityChecklistSr,
            List<String> visibilityChecklistEn) {
        
        return PhotoGuidanceDTO.builder()
            .photoType(photoType)
            .displayName(displayNameSr)
            .displayNameEn(displayNameEn)
            .sequenceOrder(sequenceOrder)
            .totalInCategory(8)
            .category("exterior")
            .instructionsSr(instructionsSr)
            .instructionsEn(instructionsEn)
            .silhouetteUrl(silhouetteBaseUrl + "/car-" + photoType.name().toLowerCase() + ".svg")
            .expectedAngle(expectedAngle)
            .estimatedDuration(Duration.ofSeconds(30))
            .commonMistakesSr(commonMistakesSr)
            .commonMistakesEn(commonMistakesEn)
            .tipsSr(List.of("Stanite na ravnoj površini", "Izbegavajte jake senke", "Uključite fleš ako je potrebno"))
            .tipsEn(List.of("Stand on flat surface", "Avoid harsh shadows", "Use flash if needed"))
            .required(true)
            .minDistanceMeters(MIN_EXTERIOR_DISTANCE_METERS)
            .maxDistanceMeters(MAX_EXTERIOR_DISTANCE_METERS)
            .visibilityChecklistSr(visibilityChecklistSr)
            .visibilityChecklistEn(visibilityChecklistEn)
            .build();
    }
    
    private PhotoGuidanceDTO buildInteriorGuidance(
            CheckInPhotoType photoType,
            int sequenceOrder,
            String displayNameSr,
            String displayNameEn,
            PhotoAngle expectedAngle,
            String instructionsSr,
            String instructionsEn,
            List<String> commonMistakesSr,
            List<String> commonMistakesEn,
            List<String> visibilityChecklistSr,
            List<String> visibilityChecklistEn) {
        
        return PhotoGuidanceDTO.builder()
            .photoType(photoType)
            .displayName(displayNameSr)
            .displayNameEn(displayNameEn)
            .sequenceOrder(sequenceOrder + 8) // Interior photos are 9-12
            .totalInCategory(4)
            .category("interior")
            .instructionsSr(instructionsSr)
            .instructionsEn(instructionsEn)
            .silhouetteUrl(silhouetteBaseUrl + "/car-" + photoType.name().toLowerCase() + ".svg")
            .expectedAngle(expectedAngle)
            .estimatedDuration(Duration.ofSeconds(20))
            .commonMistakesSr(commonMistakesSr)
            .commonMistakesEn(commonMistakesEn)
            .tipsSr(List.of("Otvorite vrata za bolje osvetljenje", "Uklonite lične predmete"))
            .tipsEn(List.of("Open doors for better lighting", "Remove personal items"))
            .required(true)
            .visibilityChecklistSr(visibilityChecklistSr)
            .visibilityChecklistEn(visibilityChecklistEn)
            .build();
    }
    
    private PhotoGuidanceDTO buildReadingGuidance(
            CheckInPhotoType photoType,
            String displayNameSr,
            String displayNameEn,
            PhotoAngle expectedAngle,
            String instructionsSr,
            String instructionsEn,
            List<String> commonMistakesSr,
            List<String> commonMistakesEn,
            List<String> visibilityChecklistSr,
            List<String> visibilityChecklistEn) {
        
        int sequenceOrder = photoType.name().contains("ODOMETER") ? 11 : 12;
        
        return PhotoGuidanceDTO.builder()
            .photoType(photoType)
            .displayName(displayNameSr)
            .displayNameEn(displayNameEn)
            .sequenceOrder(sequenceOrder)
            .totalInCategory(2)
            .category("reading")
            .instructionsSr(instructionsSr)
            .instructionsEn(instructionsEn)
            .silhouetteUrl(silhouetteBaseUrl + "/car-" + photoType.name().toLowerCase() + ".svg")
            .expectedAngle(expectedAngle)
            .estimatedDuration(Duration.ofSeconds(15))
            .commonMistakesSr(commonMistakesSr)
            .commonMistakesEn(commonMistakesEn)
            .tipsSr(List.of("Upalite motor", "Fokusirajte na brojke", "Izbegavajte odsjaj"))
            .tipsEn(List.of("Turn on engine", "Focus on numbers", "Avoid glare"))
            .required(true)
            .visibilityChecklistSr(visibilityChecklistSr)
            .visibilityChecklistEn(visibilityChecklistEn)
            .build();
    }
    
    private CheckInPhotoType mapCheckoutToCheckinType(CheckInPhotoType checkoutType) {
        return switch (checkoutType) {
            case HOST_CHECKOUT_EXTERIOR_FRONT -> CheckInPhotoType.HOST_EXTERIOR_FRONT;
            case HOST_CHECKOUT_EXTERIOR_REAR -> CheckInPhotoType.HOST_EXTERIOR_REAR;
            case HOST_CHECKOUT_EXTERIOR_LEFT -> CheckInPhotoType.HOST_EXTERIOR_LEFT;
            case HOST_CHECKOUT_EXTERIOR_RIGHT -> CheckInPhotoType.HOST_EXTERIOR_RIGHT;
            case HOST_CHECKOUT_INTERIOR_DASHBOARD -> CheckInPhotoType.HOST_INTERIOR_DASHBOARD;
            case HOST_CHECKOUT_INTERIOR_REAR -> CheckInPhotoType.HOST_INTERIOR_REAR;
            case HOST_CHECKOUT_ODOMETER -> CheckInPhotoType.HOST_ODOMETER;
            case HOST_CHECKOUT_FUEL_GAUGE -> CheckInPhotoType.HOST_FUEL_GAUGE;
            default -> checkoutType;
        };
    }
}
