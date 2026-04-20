package org.example.rentoza.car;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.storage.CarImageStorageService;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Turo-standard car listing hardening rules.
 * Covers: price bounds, photo count (5–10), license plate uniqueness,
 * description sanitization, year validation, and mileage limits.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Car Listing Hardening Rules")
class CarListingHardeningTest {

    @Mock private CarRepository carRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private CurrentUser currentUser;
    @Mock private CarImageStorageService carImageStorageService;
    @Mock private MarketplaceComplianceService marketplaceComplianceService;

    private CarService carService;
    private User testOwner;

    @BeforeEach
    void setUp() {
        carService = new CarService(
                carRepository, bookingRepository, reviewRepository,
            currentUser, carImageStorageService, marketplaceComplianceService
        );
        testOwner = new User();
        testOwner.setId(1L);
        testOwner.setEmail("owner@test.com");
    }

    /** Build a valid DTO that passes all validation. */
    private CarRequestDTO validDto() {
        CarRequestDTO dto = new CarRequestDTO();
        dto.setBrand("BMW");
        dto.setModel("X5");
        dto.setYear(java.time.Year.now().getValue());
        dto.setPricePerDay(new BigDecimal("100"));
        dto.setLocation("Beograd");
        dto.setLocationLatitude(BigDecimal.valueOf(44.8));
        dto.setLocationLongitude(BigDecimal.valueOf(20.4));
        dto.setLicensePlate("BG-123-AB");
        dto.setImageUrls(List.of("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"));
        dto.setDescription("Clean car in great condition");
        return dto;
    }

    // =========================================================================
    // P1: PRICE BOUNDS
    // =========================================================================
    @Nested
    @DisplayName("Price validation")
    class PriceValidation {

        @Test
        @DisplayName("Price below minimum (10 RSD) is rejected")
        void priceBelowMinimum() {
            CarRequestDTO dto = validDto();
            dto.setPricePerDay(new BigDecimal("9"));
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("at least 10"));
        }

        @Test
        @DisplayName("Price above maximum (50,000 RSD) is rejected")
        void priceAboveMaximum() {
            CarRequestDTO dto = validDto();
            dto.setPricePerDay(new BigDecimal("50001"));
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("50,000"));
        }

        @Test
        @DisplayName("Price at boundaries (10 and 50000) is accepted")
        void priceAtBoundaries() {
            // Lower bound
            CarRequestDTO dtoLow = validDto();
            dtoLow.setPricePerDay(new BigDecimal("10"));
            // Should not throw for price validation (may throw later for other reasons)
            assertDoesNotThrow(() -> {
                try { carService.addCar(dtoLow, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("Price") || e.getMessage().contains("price"))
                        throw e; // re-throw price errors only
                }
            });

            // Upper bound
            CarRequestDTO dtoHigh = validDto();
            dtoHigh.setPricePerDay(new BigDecimal("50000"));
            assertDoesNotThrow(() -> {
                try { carService.addCar(dtoHigh, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("Price") || e.getMessage().contains("price"))
                        throw e;
                }
            });
        }
    }

    // =========================================================================
    // P1: PHOTO COUNT (5–10 on JSON path)
    // =========================================================================
    @Nested
    @DisplayName("Photo count validation (JSON path)")
    class PhotoCountValidation {

        @Test
        @DisplayName("0 photos on JSON path is rejected - minimum 5 required")
        void zeroPhotosRejected() {
            CarRequestDTO dto = validDto();
            dto.setImageUrls(Collections.emptyList());
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("Minimum 5"));
        }

        @Test
        @DisplayName("4 photos on JSON path is rejected - minimum 5 required")
        void fourPhotosRejected() {
            CarRequestDTO dto = validDto();
            dto.setImageUrls(List.of("1.jpg", "2.jpg", "3.jpg", "4.jpg"));
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("Minimum 5"));
        }

        @Test
        @DisplayName("11 photos on JSON path is rejected - maximum 10")
        void elevenPhotosRejected() {
            CarRequestDTO dto = validDto();
            dto.setImageUrls(IntStream.rangeClosed(1, 11)
                    .mapToObj(i -> i + ".jpg").collect(Collectors.toList()));
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("Maximum 10"));
        }

        @Test
        @DisplayName("5 photos on JSON path is accepted (lower bound)")
        void fivePhotosAccepted() {
            CarRequestDTO dto = validDto();
            dto.setImageUrls(IntStream.rangeClosed(1, 5)
                    .mapToObj(i -> i + ".jpg").collect(Collectors.toList()));
            assertDoesNotThrow(() -> {
                try { carService.addCar(dto, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("photo") || e.getMessage().contains("Photo"))
                        throw e;
                }
            });
        }

        @Test
        @DisplayName("10 photos on JSON path is accepted (upper bound)")
        void tenPhotosAccepted() {
            CarRequestDTO dto = validDto();
            dto.setImageUrls(IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> i + ".jpg").collect(Collectors.toList()));
            assertDoesNotThrow(() -> {
                try { carService.addCar(dto, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("photo") || e.getMessage().contains("Photo"))
                        throw e;
                }
            });
        }

        @Test
        @DisplayName("null imageUrls passes photo check (multipart path)")
        void nullImageUrlsPassesPhotoCheck() {
            CarRequestDTO dto = validDto();
            dto.setImageUrls(null); // multipart path doesn't set imageUrls
            assertDoesNotThrow(() -> {
                try { carService.addCar(dto, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("photo") || e.getMessage().contains("Photo"))
                        throw e;
                }
            });
        }
    }

    // =========================================================================
    // P1: DUPLICATE LICENSE PLATE
    // =========================================================================
    @Nested
    @DisplayName("License plate uniqueness")
    class LicensePlateUniqueness {

        @Test
        @DisplayName("Duplicate license plate is rejected")
        void duplicatePlateRejected() {
            when(carRepository.existsByLicensePlateIgnoreCase("BG-123-AB")).thenReturn(true);
            CarRequestDTO dto = validDto();
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("already registered"));
        }

        @Test
        @DisplayName("Unique license plate is accepted")
        void uniquePlateAccepted() {
            when(carRepository.existsByLicensePlateIgnoreCase(anyString())).thenReturn(false);
            CarRequestDTO dto = validDto();
            assertDoesNotThrow(() -> {
                try { carService.addCar(dto, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("already registered"))
                        throw e;
                }
            });
        }
    }

    // =========================================================================
    // P1: DESCRIPTION SANITIZATION (iterative entity-decode loop)
    // =========================================================================
    @Nested
    @DisplayName("Description sanitization")
    class DescriptionSanitization {

        /**
         * Access the private sanitizeText method via reflection for isolated testing.
         */
        private String callSanitize(String input) throws Exception {
            Method m = CarService.class.getDeclaredMethod("sanitizeText", String.class);
            m.setAccessible(true);
            return (String) m.invoke(carService, input);
        }

        @Test
        @DisplayName("Plain text passes through unchanged")
        void plainTextUnchanged() throws Exception {
            assertEquals("Hello world", callSanitize("Hello world"));
        }

        @Test
        @DisplayName("HTML tags are stripped")
        void htmlTagsStripped() throws Exception {
            assertEquals("Hello", callSanitize("<b>Hello</b>"));
        }

        @Test
        @DisplayName("Script tags are stripped (text content preserved, tags removed)")
        void scriptTagsStripped() throws Exception {
            String result = callSanitize("<script>alert('xss')</script>");
            assertFalse(result.contains("<script"), "Script opening tag must be removed");
            assertFalse(result.contains("</script"), "Script closing tag must be removed");
        }

        @Test
        @DisplayName("Single-encoded HTML entities do not survive as tags")
        void singleEncodedEntitiesNeutralized() throws Exception {
            // &lt;script&gt;alert('xss')&lt;/script&gt;
            // After decode: <script>alert('xss')</script>
            // After second tag strip: empty or just the text
            String result = callSanitize("&lt;script&gt;alert('xss')&lt;/script&gt;");
            assertFalse(result.contains("<script"), "Decoded entities must not survive as real tags");
            assertFalse(result.contains("</script"), "Decoded closing tags must not survive");
        }

        @Test
        @DisplayName("Double-encoded HTML entities do not survive as tags")
        void doubleEncodedEntitiesNeutralized() throws Exception {
            // &amp;lt;script&amp;gt; → &lt;script&gt; → <script> → stripped
            String result = callSanitize("&amp;lt;script&amp;gt;alert('xss')&amp;lt;/script&amp;gt;");
            assertFalse(result.contains("<script"), "Double-encoded tags must not survive");
        }

        @Test
        @DisplayName("javascript: protocol is removed")
        void javascriptProtocolRemoved() throws Exception {
            String result = callSanitize("Click javascript:alert(1)");
            assertFalse(result.toLowerCase().contains("javascript:"));
        }

        @Test
        @DisplayName("Event handlers are removed")
        void eventHandlersRemoved() throws Exception {
            String result = callSanitize("test onerror=alert(1)");
            assertFalse(result.contains("onerror="));
        }

        @Test
        @DisplayName("null input returns null")
        void nullReturnsNull() throws Exception {
            assertNull(callSanitize(null));
        }
    }

    // =========================================================================
    // P2: YEAR VALIDATION (create + update)
    // =========================================================================
    @Nested
    @DisplayName("Year validation")
    class YearValidation {

        @Test
        @DisplayName("Car older than 15 years is rejected on create")
        void tooOldOnCreate() {
            CarRequestDTO dto = validDto();
            dto.setYear(java.time.Year.now().getValue() - 16);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("too old"));
        }

        @Test
        @DisplayName("Future year (>current+1) is rejected on create")
        void futureYearOnCreate() {
            CarRequestDTO dto = validDto();
            dto.setYear(java.time.Year.now().getValue() + 2);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("future"));
        }

        @Test
        @DisplayName("Next year is accepted (model year = current + 1)")
        void nextYearAccepted() {
            CarRequestDTO dto = validDto();
            dto.setYear(java.time.Year.now().getValue() + 1);
            assertDoesNotThrow(() -> {
                try { carService.addCar(dto, testOwner); } catch (RuntimeException e) {
                    if (e.getMessage().contains("year") || e.getMessage().contains("Year") ||
                        e.getMessage().contains("old") || e.getMessage().contains("future"))
                        throw e;
                }
            });
        }
    }

    // =========================================================================
    // P2: MILEAGE VALIDATION
    // =========================================================================
    @Nested
    @DisplayName("Mileage validation")
    class MileageValidation {

        @Test
        @DisplayName("Mileage above 300,000 km is rejected")
        void mileageTooHigh() {
            CarRequestDTO dto = validDto();
            dto.setCurrentMileageKm(300_001);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("300,000") || ex.getMessage().contains("Mileage too high"));
        }

        @Test
        @DisplayName("Negative mileage is rejected")
        void negativeMileage() {
            CarRequestDTO dto = validDto();
            dto.setCurrentMileageKm(-1);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("negative"));
        }

        @Test
        @DisplayName("Daily mileage limit below 50 is rejected")
        void dailyLimitTooLow() {
            CarRequestDTO dto = validDto();
            dto.setDailyMileageLimitKm(49);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("50"));
        }

        @Test
        @DisplayName("Daily mileage limit above 1000 is rejected")
        void dailyLimitTooHigh() {
            CarRequestDTO dto = validDto();
            dto.setDailyMileageLimitKm(1001);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> carService.addCar(dto, testOwner));
            assertTrue(ex.getMessage().contains("1,000") || ex.getMessage().contains("1000"));
        }
    }
}
