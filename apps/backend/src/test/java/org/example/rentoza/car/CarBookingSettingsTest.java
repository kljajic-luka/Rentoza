package org.example.rentoza.car;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CarBookingSettings embeddable entity.
 * Phase 2: Validation Alignment - Host-configurable booking rules.
 * 
 * @see Time_Window_Logic_Improvement_Plan.md Phase 2.1
 */
class CarBookingSettingsTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ========================================================================
    // DEFAULT VALUES TESTS
    // ========================================================================

    @Nested
    @DisplayName("Default Values")
    class DefaultValuesTests {

        @Test
        @DisplayName("defaults() should return instance with all default values")
        void defaultsShouldReturnInstanceWithAllDefaultValues() {
            CarBookingSettings settings = CarBookingSettings.defaults();

            assertThat(settings.getMinTripHours()).isEqualTo(24);
            assertThat(settings.getMaxTripDays()).isEqualTo(30);
            assertThat(settings.getAdvanceNoticeHours()).isEqualTo(2);
            assertThat(settings.getPrepBufferHours()).isEqualTo(3);
            assertThat(settings.getInstantBookEnabled()).isFalse();
        }

        @Test
        @DisplayName("New instance should have default values (not null)")
        void newInstanceShouldHaveDefaultValues() {
            CarBookingSettings settings = new CarBookingSettings();

            // Fields have default values assigned in the class
            assertThat(settings.getMinTripHours()).isEqualTo(24);
            assertThat(settings.getMaxTripDays()).isEqualTo(30);
            assertThat(settings.getAdvanceNoticeHours()).isEqualTo(2);
            assertThat(settings.getPrepBufferHours()).isEqualTo(3);
            assertThat(settings.getInstantBookEnabled()).isFalse();
        }
    }

    // ========================================================================
    // EFFECTIVE VALUE TESTS
    // ========================================================================

    @Nested
    @DisplayName("Effective Values (Null-safe getters)")
    class EffectiveValuesTests {

        @Test
        @DisplayName("getEffectiveMinTripHours should return default when null")
        void getEffectiveMinTripHoursShouldReturnDefaultWhenNull() {
            CarBookingSettings settings = new CarBookingSettings();

            assertThat(settings.getEffectiveMinTripHours()).isEqualTo(24);
        }

        @Test
        @DisplayName("getEffectiveMinTripHours should return set value")
        void getEffectiveMinTripHoursShouldReturnSetValue() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMinTripHours(48);

            assertThat(settings.getEffectiveMinTripHours()).isEqualTo(48);
        }

        @Test
        @DisplayName("getEffectiveMaxTripDays should return default when null")
        void getEffectiveMaxTripDaysShouldReturnDefaultWhenNull() {
            CarBookingSettings settings = new CarBookingSettings();

            assertThat(settings.getEffectiveMaxTripDays()).isEqualTo(30);
        }

        @Test
        @DisplayName("getEffectiveMaxTripDays should return set value")
        void getEffectiveMaxTripDaysShouldReturnSetValue() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMaxTripDays(14);

            assertThat(settings.getEffectiveMaxTripDays()).isEqualTo(14);
        }

        @Test
        @DisplayName("getEffectiveAdvanceNoticeHours should return default when null")
        void getEffectiveAdvanceNoticeHoursShouldReturnDefaultWhenNull() {
            CarBookingSettings settings = new CarBookingSettings();

            assertThat(settings.getEffectiveAdvanceNoticeHours()).isEqualTo(2);
        }

        @Test
        @DisplayName("getEffectiveAdvanceNoticeHours should return set value")
        void getEffectiveAdvanceNoticeHoursShouldReturnSetValue() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setAdvanceNoticeHours(6);

            assertThat(settings.getEffectiveAdvanceNoticeHours()).isEqualTo(6);
        }

        @Test
        @DisplayName("getEffectivePrepBufferHours should return default when null")
        void getEffectivePrepBufferHoursShouldReturnDefaultWhenNull() {
            CarBookingSettings settings = new CarBookingSettings();

            assertThat(settings.getEffectivePrepBufferHours()).isEqualTo(3);
        }

        @Test
        @DisplayName("getEffectivePrepBufferHours should return set value")
        void getEffectivePrepBufferHoursShouldReturnSetValue() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setPrepBufferHours(6);

            assertThat(settings.getEffectivePrepBufferHours()).isEqualTo(6);
        }

        @Test
        @DisplayName("isInstantBookEnabled should return false when null")
        void isInstantBookEnabledShouldReturnFalseWhenNull() {
            CarBookingSettings settings = new CarBookingSettings();

            assertThat(settings.isInstantBookEnabled()).isFalse();
        }

        @Test
        @DisplayName("isInstantBookEnabled should return true when set")
        void isInstantBookEnabledShouldReturnTrueWhenSet() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setInstantBookEnabled(true);

            assertThat(settings.isInstantBookEnabled()).isTrue();
        }
    }

    // ========================================================================
    // VALIDATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Validation Constraints")
    class ValidationTests {

        @Test
        @DisplayName("Valid settings should pass validation")
        void validSettingsShouldPassValidation() {
            CarBookingSettings settings = CarBookingSettings.defaults();

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Null fields should pass validation (optional)")
        void nullFieldsShouldPassValidation() {
            CarBookingSettings settings = new CarBookingSettings();

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).isEmpty();
        }

        // --- minTripHours validation ---

        @ParameterizedTest
        @ValueSource(ints = {1, 24, 48, 168})
        @DisplayName("Valid minTripHours should pass validation")
        void validMinTripHoursShouldPassValidation(int hours) {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMinTripHours(hours);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("minTripHours below 1 should fail validation")
        void minTripHoursBelowOneShouldFailValidation() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMinTripHours(0);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("1");
        }

        @Test
        @DisplayName("minTripHours above 168 should fail validation")
        void minTripHoursAbove168ShouldFailValidation() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMinTripHours(169);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("168");
        }

        // --- maxTripDays validation ---

        @ParameterizedTest
        @ValueSource(ints = {1, 7, 14, 30})
        @DisplayName("Valid maxTripDays should pass validation")
        void validMaxTripDaysShouldPassValidation(int days) {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMaxTripDays(days);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("maxTripDays below 1 should fail validation")
        void maxTripDaysBelowOneShouldFailValidation() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMaxTripDays(0);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).hasSize(1);
        }

        @Test
        @DisplayName("maxTripDays above 30 should fail validation")
        void maxTripDaysAbove30ShouldFailValidation() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setMaxTripDays(31);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("30");
        }

        // --- advanceNoticeHours validation ---

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 24, 72})
        @DisplayName("Valid advanceNoticeHours should pass validation")
        void validAdvanceNoticeHoursShouldPassValidation(int hours) {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setAdvanceNoticeHours(hours);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("advanceNoticeHours above 72 should fail validation")
        void advanceNoticeHoursAbove72ShouldFailValidation() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setAdvanceNoticeHours(73);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("72");
        }

        // --- prepBufferHours validation ---

        @ParameterizedTest
        @ValueSource(ints = {0, 3, 12, 24})
        @DisplayName("Valid prepBufferHours should pass validation")
        void validPrepBufferHoursShouldPassValidation(int hours) {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setPrepBufferHours(hours);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("prepBufferHours above 24 should fail validation")
        void prepBufferHoursAbove24ShouldFailValidation() {
            CarBookingSettings settings = new CarBookingSettings();
            settings.setPrepBufferHours(25);

            Set<ConstraintViolation<CarBookingSettings>> violations = validator.validate(settings);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("24");
        }
    }

    // ========================================================================
    // BUILDER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Builder Pattern")
    class BuilderTests {

        @Test
        @DisplayName("Builder should create valid instance with all fields")
        void builderShouldCreateValidInstanceWithAllFields() {
            CarBookingSettings settings = CarBookingSettings.builder()
                    .minTripHours(48)
                    .maxTripDays(14)
                    .advanceNoticeHours(6)
                    .prepBufferHours(4)
                    .instantBookEnabled(true)
                    .build();

            assertThat(settings.getMinTripHours()).isEqualTo(48);
            assertThat(settings.getMaxTripDays()).isEqualTo(14);
            assertThat(settings.getAdvanceNoticeHours()).isEqualTo(6);
            assertThat(settings.getPrepBufferHours()).isEqualTo(4);
            assertThat(settings.getInstantBookEnabled()).isTrue();
        }

        @Test
        @DisplayName("Builder should allow partial field specification")
        void builderShouldAllowPartialFieldSpecification() {
            CarBookingSettings settings = CarBookingSettings.builder()
                    .minTripHours(72)
                    .build();

            assertThat(settings.getMinTripHours()).isEqualTo(72);
            assertThat(settings.getMaxTripDays()).isNull();
            assertThat(settings.getAdvanceNoticeHours()).isNull();
        }
    }

    // ========================================================================
    // INTEGRATION WITH CAR ENTITY TESTS
    // ========================================================================

    @Nested
    @DisplayName("Integration with Car Entity")
    class CarIntegrationTests {

        @Test
        @DisplayName("Car should provide effective settings when embedded settings is null")
        void carShouldProvideEffectiveSettingsWhenNull() {
            Car car = new Car();
            // bookingSettings is null by default

            CarBookingSettings effectiveSettings = car.getEffectiveBookingSettings();

            assertThat(effectiveSettings).isNotNull();
            assertThat(effectiveSettings.getEffectiveMinTripHours()).isEqualTo(24);
            assertThat(effectiveSettings.getEffectiveMaxTripDays()).isEqualTo(30);
            assertThat(effectiveSettings.getEffectiveAdvanceNoticeHours()).isEqualTo(2);
            assertThat(effectiveSettings.getEffectivePrepBufferHours()).isEqualTo(3);
        }

        @Test
        @DisplayName("Car should use embedded settings when present")
        void carShouldUseEmbeddedSettingsWhenPresent() {
            Car car = new Car();
            CarBookingSettings customSettings = CarBookingSettings.builder()
                    .minTripHours(48)
                    .maxTripDays(7)
                    .advanceNoticeHours(12)
                    .prepBufferHours(6)
                    .instantBookEnabled(true)
                    .build();
            car.setBookingSettings(customSettings);

            CarBookingSettings effectiveSettings = car.getEffectiveBookingSettings();

            assertThat(effectiveSettings).isSameAs(customSettings);
            assertThat(effectiveSettings.getEffectiveMinTripHours()).isEqualTo(48);
            assertThat(effectiveSettings.getEffectiveMaxTripDays()).isEqualTo(7);
        }
    }
}
