package org.example.rentoza.car.dto;

import org.example.rentoza.car.Feature;
import org.example.rentoza.car.TransmissionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for free-text query (q) field behaviour in search DTOs.
 *
 * Covers:
 * - CarSearchCriteria: q field survives normalize(), not cleared when present
 * - AvailabilitySearchRequestDTO: hasFilters() returns true when q is set
 * - AvailabilitySearchRequestDTO.hasFilters() contract: blank / null q is NOT a filter
 * - Backward compatibility: q fallback to legacy search param is handled in controller
 */
@DisplayName("Car search DTOs — free-text q field")
class CarSearchQFieldTest {

    // ── CarSearchCriteria ────────────────────────────────────────────────────

    @Nested
    @DisplayName("CarSearchCriteria")
    class CarSearchCriteriaTests {

        @Test
        @DisplayName("q survives normalize() without being cleared")
        void normalize_preservesQField() {
            CarSearchCriteria criteria = CarSearchCriteria.builder()
                    .q("audi")
                    .page(0)
                    .size(20)
                    .build();

            criteria.normalize();

            assertThat(criteria.getQ()).isEqualTo("audi");
        }

        @Test
        @DisplayName("q is null by default (optional field)")
        void qIsNullByDefault() {
            CarSearchCriteria criteria = CarSearchCriteria.builder()
                    .page(0)
                    .size(20)
                    .build();

            assertThat(criteria.getQ()).isNull();
        }

        @Test
        @DisplayName("normalize() clamps page/size but leaves q intact")
        void normalize_clampsPageSizeLeaveQAlone() {
            CarSearchCriteria criteria = CarSearchCriteria.builder()
                    .q("tesla")
                    .page(-1)
                    .size(0)
                    .build();

            criteria.normalize();

            assertThat(criteria.getPage()).isEqualTo(0);
            assertThat(criteria.getSize()).isEqualTo(20);
            assertThat(criteria.getQ()).isEqualTo("tesla");
        }
    }

    // ── AvailabilitySearchRequestDTO ─────────────────────────────────────────

    @Nested
    @DisplayName("AvailabilitySearchRequestDTO")
    class AvailabilityDtoTests {

        private AvailabilitySearchRequestDTO minimalValid() {
            return AvailabilitySearchRequestDTO.builder()
                    .location("Beograd")
                    .startTime(LocalDateTime.now().plusDays(1))
                    .endTime(LocalDateTime.now().plusDays(3))
                    .page(0)
                    .size(20)
                    .build();
        }

        private AvailabilitySearchRequestDTO withQ(String q) {
            return AvailabilitySearchRequestDTO.builder()
                    .location("Beograd")
                    .startTime(LocalDateTime.now().plusDays(1))
                    .endTime(LocalDateTime.now().plusDays(3))
                    .page(0)
                    .size(20)
                    .q(q)
                    .build();
        }

        @Test
        @DisplayName("hasFilters() returns true when q is non-blank")
        void hasFilters_trueWhenQIsSet() {
            assertThat(withQ("BMW").hasFilters()).isTrue();
        }

        @ParameterizedTest(name = "q=\"{0}\" should NOT count as a filter")
        @ValueSource(strings = { "", "  ", "\t" })
        @DisplayName("hasFilters() returns false when q is blank/whitespace")
        void hasFilters_falseWhenQIsBlank(String blank) {
            assertThat(withQ(blank).hasFilters()).isFalse();
        }

        @Test
        @DisplayName("hasFilters() returns false when q is null")
        void hasFilters_falseWhenQIsNull() {
            assertThat(minimalValid().hasFilters()).isFalse();
        }

        @Test
        @DisplayName("hasFilters() is true when q AND other filters are set")
        void hasFilters_trueWhenQAndOtherFilters() {
            AvailabilitySearchRequestDTO dto = AvailabilitySearchRequestDTO.builder()
                    .location("Beograd")
                    .startTime(LocalDateTime.now().plusDays(1))
                    .endTime(LocalDateTime.now().plusDays(3))
                    .page(0)
                    .size(20)
                    .q("SUV")
                    .minPrice(50.0)
                    .transmission(TransmissionType.AUTOMATIC)
                    .build();
            assertThat(dto.hasFilters()).isTrue();
        }

        @Test
        @DisplayName("hasFilters() is true when only traditional filters (no q) are set")
        void hasFilters_trueWithoutQ() {
            AvailabilitySearchRequestDTO dto = AvailabilitySearchRequestDTO.builder()
                    .location("Beograd")
                    .startTime(LocalDateTime.now().plusDays(1))
                    .endTime(LocalDateTime.now().plusDays(3))
                    .page(0)
                    .size(20)
                    .make("Volkswagen")
                    .build();
            assertThat(dto.hasFilters()).isTrue();
        }

        @Test
        @DisplayName("q field survives round-trip build/read")
        void qFieldRoundTrip() {
            assertThat(withQ("Srpska kola").getQ()).isEqualTo("Srpska kola");
        }
    }
}
