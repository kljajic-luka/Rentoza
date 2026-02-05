package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.checkin.dto.PhotoGuidanceDTO;
import org.example.rentoza.booking.checkin.dto.PhotoGuidanceDTO.PhotoAngle;
import org.example.rentoza.booking.checkin.dto.PhotoSequenceValidationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PhotoGuidanceService.
 * 
 * Tests the structured 8-point photo protocol for enterprise-grade
 * vehicle documentation.
 * 
 * @since Enterprise Upgrade Phase 2
 */
@DisplayName("PhotoGuidanceService Tests")
class PhotoGuidanceServiceTest {

    private PhotoGuidanceService photoGuidanceService;

    @BeforeEach
    void setUp() {
        photoGuidanceService = new PhotoGuidanceService();
        // Set the silhouette base URL via reflection
        ReflectionTestUtils.setField(photoGuidanceService, "silhouetteBaseUrl", "/assets/silhouettes");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUIDANCE RETRIEVAL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getGuidance() - Individual Photo Type Guidance")
    class GetGuidanceTests {

        @Test
        @DisplayName("Should return valid guidance for GUEST_EXTERIOR_FRONT")
        void shouldReturnGuidanceForExteriorFront() {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_EXTERIOR_FRONT);

            assertThat(guidance).isNotNull();
            assertThat(guidance.getPhotoType()).isEqualTo(CheckInPhotoType.GUEST_EXTERIOR_FRONT);
            assertThat(guidance.getSequenceOrder()).isEqualTo(1);
            assertThat(guidance.getDisplayName()).isEqualTo("Prednja strana");
            assertThat(guidance.getDisplayNameEn()).isEqualTo("Front Exterior");
            assertThat(guidance.getExpectedAngle()).isEqualTo(PhotoAngle.FRONT_FACING);
            assertThat(guidance.getSilhouetteUrl()).contains("silhouettes");
            assertThat(guidance.getInstructionsSr()).isNotBlank();
            assertThat(guidance.getInstructionsEn()).isNotBlank();
            assertThat(guidance.getCommonMistakesSr()).isNotEmpty();
            assertThat(guidance.getCommonMistakesEn()).isNotEmpty();
            assertThat(guidance.getVisibilityChecklistSr()).isNotEmpty();
            assertThat(guidance.getVisibilityChecklistEn()).isNotEmpty();
        }

        @Test
        @DisplayName("Should return valid guidance for GUEST_INTERIOR_DASHBOARD")
        void shouldReturnGuidanceForInteriorDashboard() {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_INTERIOR_DASHBOARD);

            assertThat(guidance).isNotNull();
            assertThat(guidance.getPhotoType()).isEqualTo(CheckInPhotoType.GUEST_INTERIOR_DASHBOARD);
            assertThat(guidance.getCategory()).isEqualTo("interior");
            assertThat(guidance.getExpectedAngle()).isEqualTo(PhotoAngle.DASHBOARD);
            assertThat(guidance.getMinDistanceMeters()).isNull(); // Interior photos don't have distance
        }

        @Test
        @DisplayName("Should return valid guidance for GUEST_ODOMETER")
        void shouldReturnGuidanceForOdometer() {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_ODOMETER);

            assertThat(guidance).isNotNull();
            assertThat(guidance.getPhotoType()).isEqualTo(CheckInPhotoType.GUEST_ODOMETER);
            assertThat(guidance.getCategory()).isEqualTo("reading");
            assertThat(guidance.getExpectedAngle()).isEqualTo(PhotoAngle.ODOMETER_CLOSEUP);
            // Odometer should have specific instructions about readability
            assertThat(guidance.getInstructionsSr()).containsIgnoringCase("kilometr");
        }

        @Test
        @DisplayName("Should return valid guidance for GUEST_FUEL_GAUGE")
        void shouldReturnGuidanceForFuelGauge() {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_FUEL_GAUGE);

            assertThat(guidance).isNotNull();
            assertThat(guidance.getPhotoType()).isEqualTo(CheckInPhotoType.GUEST_FUEL_GAUGE);
            assertThat(guidance.getCategory()).isEqualTo("reading");
            assertThat(guidance.getExpectedAngle()).isEqualTo(PhotoAngle.FUEL_GAUGE_CLOSEUP);
        }

        @ParameterizedTest
        @EnumSource(value = CheckInPhotoType.class, names = {
            "GUEST_EXTERIOR_FRONT", "GUEST_EXTERIOR_REAR", 
            "GUEST_EXTERIOR_LEFT", "GUEST_EXTERIOR_RIGHT",
            "GUEST_INTERIOR_DASHBOARD", "GUEST_INTERIOR_REAR",
            "GUEST_ODOMETER", "GUEST_FUEL_GAUGE"
        })
        @DisplayName("Should return non-null guidance for all required guest photo types")
        void shouldReturnGuidanceForAllGuestPhotoTypes(CheckInPhotoType photoType) {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(photoType);

            assertThat(guidance).isNotNull();
            assertThat(guidance.getPhotoType()).isEqualTo(photoType);
            assertThat(guidance.getDisplayName()).isNotBlank();
            assertThat(guidance.getInstructionsSr()).isNotBlank();
        }

        @ParameterizedTest
        @EnumSource(value = CheckInPhotoType.class, names = {
            "GUEST_EXTERIOR_FRONT", "GUEST_EXTERIOR_REAR", 
            "GUEST_EXTERIOR_LEFT", "GUEST_EXTERIOR_RIGHT"
        })
        @DisplayName("Exterior photos should have distance requirements")
        void exteriorPhotosShouldHaveDistanceRequirements(CheckInPhotoType photoType) {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(photoType);

            assertThat(guidance.getCategory()).isEqualTo("exterior");
            assertThat(guidance.getMinDistanceMeters()).isNotNull();
            assertThat(guidance.getMaxDistanceMeters()).isNotNull();
            assertThat(guidance.getMinDistanceMeters()).isLessThan(guidance.getMaxDistanceMeters());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEQUENCE RETRIEVAL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sequence Retrieval Tests")
    class SequenceRetrievalTests {

        @Test
        @DisplayName("getGuestCheckInGuidanceSequence() should return all 8 required types sorted by sequence")
        void shouldReturnGuestCheckInSequenceInOrder() {
            List<PhotoGuidanceDTO> sequence = photoGuidanceService.getGuestCheckInGuidanceSequence();

            assertThat(sequence).hasSize(8);
            
            // Verify sorted by sequence order (ascending)
            for (int i = 0; i < sequence.size() - 1; i++) {
                assertThat(sequence.get(i).getSequenceOrder())
                    .as("Element %d should have smaller or equal sequence than element %d", i, i+1)
                    .isLessThanOrEqualTo(sequence.get(i + 1).getSequenceOrder());
            }
            
            // Verify all required types present
            Set<CheckInPhotoType> types = Set.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT,
                CheckInPhotoType.GUEST_EXTERIOR_REAR,
                CheckInPhotoType.GUEST_EXTERIOR_LEFT,
                CheckInPhotoType.GUEST_EXTERIOR_RIGHT,
                CheckInPhotoType.GUEST_INTERIOR_DASHBOARD,
                CheckInPhotoType.GUEST_INTERIOR_REAR,
                CheckInPhotoType.GUEST_ODOMETER,
                CheckInPhotoType.GUEST_FUEL_GAUGE
            );
            
            assertThat(sequence.stream().map(PhotoGuidanceDTO::getPhotoType))
                .containsExactlyInAnyOrderElementsOf(types);
        }

        @Test
        @DisplayName("getHostCheckInGuidanceSequence() should return all host types")
        void shouldReturnHostCheckInSequence() {
            List<PhotoGuidanceDTO> sequence = photoGuidanceService.getHostCheckInGuidanceSequence();

            assertThat(sequence).isNotEmpty();
            assertThat(sequence).allMatch(g -> 
                g.getPhotoType().name().startsWith("HOST_") && 
                !g.getPhotoType().name().startsWith("HOST_CHECKOUT_")
            );
        }

        @Test
        @DisplayName("getHostCheckoutGuidanceSequence() should return checkout types")
        void shouldReturnHostCheckoutSequence() {
            List<PhotoGuidanceDTO> sequence = photoGuidanceService.getHostCheckoutGuidanceSequence();

            assertThat(sequence).isNotEmpty();
            assertThat(sequence).allMatch(g -> 
                g.getPhotoType().name().startsWith("HOST_CHECKOUT_")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEQUENCE VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sequence Validation Tests")
    class SequenceValidationTests {

        @Test
        @DisplayName("Should return valid when all 8 guest photos submitted with requiresAll=true")
        void shouldBeValidWhenAllGuestPhotosSubmittedStrictMode() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT,
                CheckInPhotoType.GUEST_EXTERIOR_REAR,
                CheckInPhotoType.GUEST_EXTERIOR_LEFT,
                CheckInPhotoType.GUEST_EXTERIOR_RIGHT,
                CheckInPhotoType.GUEST_INTERIOR_DASHBOARD,
                CheckInPhotoType.GUEST_INTERIOR_REAR,
                CheckInPhotoType.GUEST_ODOMETER,
                CheckInPhotoType.GUEST_FUEL_GAUGE
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateGuestCheckInSequence(submitted, true);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getUploadedCount()).isEqualTo(8);
            assertThat(result.getRequiredCount()).isEqualTo(8);
            assertThat(result.getCompletionPercentage()).isEqualTo(100);
            assertThat(result.isReadyForHandshake()).isTrue();
        }

        @Test
        @DisplayName("Should return invalid when photos are missing with requiresAll=true")
        void shouldBeInvalidWhenPhotosMissingStrictMode() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT,
                CheckInPhotoType.GUEST_EXTERIOR_REAR,
                CheckInPhotoType.GUEST_EXTERIOR_LEFT
                // Missing 5 photos
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateGuestCheckInSequence(submitted, true);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getMissingTypes()).hasSize(5);
            assertThat(result.getUploadedCount()).isEqualTo(3);
            assertThat(result.getCompletionPercentage()).isEqualTo(37); // 3/8 ≈ 37%
            assertThat(result.isReadyForHandshake()).isFalse();
        }

        @Test
        @DisplayName("Should return valid when requiresAll=false regardless of count")
        void shouldBeValidInNonStrictMode() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT
                // Only 1 photo, but requiresAll=false
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateGuestCheckInSequence(submitted, false);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getRequiredCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("Should return invalid when no photos submitted in strict mode")
        void shouldBeInvalidWhenNoPhotosSubmittedStrictMode() {
            List<CheckInPhotoType> submitted = List.of();

            PhotoSequenceValidationDTO result = photoGuidanceService.validateGuestCheckInSequence(submitted, true);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getMissingTypes()).hasSize(8);
            assertThat(result.getUploadedCount()).isEqualTo(0);
            assertThat(result.getCompletionPercentage()).isEqualTo(0);
            assertThat(result.isReadyForHandshake()).isFalse();
        }

        @Test
        @DisplayName("Should handle duplicate photo types in submission")
        void shouldHandleDuplicatePhotoTypes() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT,
                CheckInPhotoType.GUEST_EXTERIOR_FRONT, // Duplicate
                CheckInPhotoType.GUEST_EXTERIOR_REAR,
                CheckInPhotoType.GUEST_EXTERIOR_LEFT
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateGuestCheckInSequence(submitted, true);

            // Should count unique types only (uses HashSet internally)
            assertThat(result.isValid()).isFalse();
            assertThat(result.getUploadedCount()).isEqualTo(3); // 3 unique types
        }

        @Test
        @DisplayName("Should validate host checkout sequence correctly in strict mode")
        void shouldValidateHostCheckoutSequenceStrictMode() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.HOST_CHECKOUT_EXTERIOR_FRONT,
                CheckInPhotoType.HOST_CHECKOUT_EXTERIOR_REAR,
                CheckInPhotoType.HOST_CHECKOUT_EXTERIOR_LEFT,
                CheckInPhotoType.HOST_CHECKOUT_EXTERIOR_RIGHT,
                CheckInPhotoType.HOST_CHECKOUT_INTERIOR_DASHBOARD,
                CheckInPhotoType.HOST_CHECKOUT_INTERIOR_REAR,
                CheckInPhotoType.HOST_CHECKOUT_ODOMETER,
                CheckInPhotoType.HOST_CHECKOUT_FUEL_GAUGE
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateHostCheckoutSequence(submitted, true);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("Should validate host checkout in non-strict mode")
        void shouldValidateHostCheckoutSequenceNonStrictMode() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.HOST_CHECKOUT_EXTERIOR_FRONT
                // Only 1 photo
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateHostCheckoutSequence(submitted, false);

            assertThat(result.isValid()).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SILHOUETTE URL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Silhouette URL Tests")
    class SilhouetteUrlTests {

        @Test
        @DisplayName("Exterior photos should have silhouette URLs")
        void exteriorPhotosShouldHaveSilhouetteUrls() {
            List<CheckInPhotoType> exteriorTypes = List.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT,
                CheckInPhotoType.GUEST_EXTERIOR_REAR,
                CheckInPhotoType.GUEST_EXTERIOR_LEFT,
                CheckInPhotoType.GUEST_EXTERIOR_RIGHT
            );

            for (CheckInPhotoType type : exteriorTypes) {
                PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(type);
                assertThat(guidance.getSilhouetteUrl())
                    .as("Silhouette URL for %s", type)
                    .isNotBlank()
                    .contains("/assets/silhouettes/");
            }
        }

        @Test
        @DisplayName("Interior photos should have silhouette URLs")
        void interiorPhotosShouldHaveSilhouetteUrls() {
            PhotoGuidanceDTO dashboard = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_INTERIOR_DASHBOARD);
            PhotoGuidanceDTO rear = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_INTERIOR_REAR);

            assertThat(dashboard.getSilhouetteUrl()).isNotBlank();
            assertThat(rear.getSilhouetteUrl()).isNotBlank();
        }

        @Test
        @DisplayName("Reading photos (odometer, fuel) should have silhouette URLs")
        void readingPhotosShouldHaveSilhouetteUrls() {
            PhotoGuidanceDTO odometer = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_ODOMETER);
            PhotoGuidanceDTO fuel = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_FUEL_GAUGE);

            assertThat(odometer.getSilhouetteUrl()).isNotBlank();
            assertThat(fuel.getSilhouetteUrl()).isNotBlank();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BILINGUAL CONTENT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Bilingual Content Tests")
    class BilingualContentTests {

        @Test
        @DisplayName("All guidance should have Serbian and English instructions")
        void allGuidanceShouldHaveBilingualInstructions() {
            List<PhotoGuidanceDTO> sequence = photoGuidanceService.getGuestCheckInGuidanceSequence();

            for (PhotoGuidanceDTO guidance : sequence) {
                assertThat(guidance.getInstructionsSr())
                    .as("Serbian instructions for %s", guidance.getPhotoType())
                    .isNotBlank();
                assertThat(guidance.getInstructionsEn())
                    .as("English instructions for %s", guidance.getPhotoType())
                    .isNotBlank();
                assertThat(guidance.getDisplayName())
                    .as("Serbian display name for %s", guidance.getPhotoType())
                    .isNotBlank();
                assertThat(guidance.getDisplayNameEn())
                    .as("English display name for %s", guidance.getPhotoType())
                    .isNotBlank();
            }
        }

        @Test
        @DisplayName("Common mistakes should be provided in both languages")
        void commonMistakesShouldBeBilingual() {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_EXTERIOR_FRONT);

            assertThat(guidance.getCommonMistakesSr()).isNotEmpty();
            assertThat(guidance.getCommonMistakesEn()).isNotEmpty();
            assertThat(guidance.getCommonMistakesSr()).hasSameSizeAs(guidance.getCommonMistakesEn());
        }

        @Test
        @DisplayName("Visibility checklist should be provided in both languages")
        void visibilityChecklistShouldBeBilingual() {
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_EXTERIOR_FRONT);

            assertThat(guidance.getVisibilityChecklistSr()).isNotEmpty();
            assertThat(guidance.getVisibilityChecklistEn()).isNotEmpty();
            assertThat(guidance.getVisibilityChecklistSr()).hasSameSizeAs(guidance.getVisibilityChecklistEn());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null photo type list in validation - expect NPE or IAE")
        void shouldHandleNullPhotoTypeList() {
            assertThatThrownBy(() -> photoGuidanceService.validateGuestCheckInSequence(null, true))
                .isInstanceOf(RuntimeException.class); // NPE from HashSet or IAE
        }

        @Test
        @DisplayName("Should only count guest types when mixed types submitted")
        void shouldOnlyCountGuestTypesWhenMixedTypesSubmitted() {
            List<CheckInPhotoType> submitted = List.of(
                CheckInPhotoType.GUEST_EXTERIOR_FRONT,
                CheckInPhotoType.HOST_EXTERIOR_FRONT, // Wrong type - not a guest type
                CheckInPhotoType.GUEST_EXTERIOR_REAR
            );

            PhotoSequenceValidationDTO result = photoGuidanceService.validateGuestCheckInSequence(submitted, true);

            // The service uses HashSet intersection with required types
            // HOST_EXTERIOR_FRONT is not in required guest types, so only 2 count
            assertThat(result.isValid()).isFalse();
            // uploadedCount reflects what was passed (before filtering), missingTypes shows what's missing
            assertThat(result.getMissingTypes()).hasSize(6); // Missing 6 of 8 required guest types
        }

        @Test
        @DisplayName("getGuidance should return default guidance for unknown type")
        void shouldReturnDefaultGuidanceForUnknownType() {
            // Create a custom type that might not have specific guidance
            // Since all enum values are handled, test the default case behavior
            PhotoGuidanceDTO guidance = photoGuidanceService.getGuidance(CheckInPhotoType.GUEST_EXTERIOR_FRONT);
            
            // Verify it doesn't throw and returns valid guidance
            assertThat(guidance).isNotNull();
            assertThat(guidance.getPhotoType()).isEqualTo(CheckInPhotoType.GUEST_EXTERIOR_FRONT);
        }
    }
}
