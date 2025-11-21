package org.example.rentoza.car;

import org.example.rentoza.car.dto.AvailabilitySearchRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CarController availability-search endpoint
 * Tests request/response handling, validation, and error scenarios
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CarController /availability-search Endpoint Tests")
class CarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AvailabilityService availabilityService;

    @Test
    @DisplayName("Test 1: Valid request should return 200 OK with paginated results")
    void testValidRequest() throws Exception {
        // Arrange
        Car car = new Car();
        car.setId(1L);
        car.setBrand("BMW");
        car.setModel("X5");
        car.setYear(2023);
        car.setPricePerDay(150.0);
        car.setLocation("Beograd");
        car.setAvailable(true);

        Page<Car> mockPage = new PageImpl<>(List.of(car));
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenReturn(mockPage);

        // Act & Assert
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].brand").value("BMW"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.currentPage").value(0))
                .andExpect(jsonPath("$.pageSize").exists());
    }

    @Test
    @DisplayName("Test 2: Missing required fields should return 400 BAD REQUEST")
    void testMissingFields() throws Exception {
        // Act & Assert - Missing startTime
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        // MISSING startTime
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Test 3: Bad date format should return 400 BAD REQUEST")
    void testBadDateFormat() throws Exception {
        // Act & Assert - Invalid date format
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", "2025-13-45") // INVALID DATE
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Test 4: Bad time format should return 400 BAD REQUEST")
    void testBadTimeFormat() throws Exception {
        // Act & Assert - Invalid time format
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "25:99") // INVALID TIME
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Test 5: Start > End should return 400 BAD REQUEST")
    void testStartAfterEnd() throws Exception {
        // Arrange - Configure service to throw validation exception
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenThrow(new IllegalArgumentException("End date/time must be after start date/time"));

        // Act & Assert - Start date after end date
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(5).toString()) // AFTER END
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(1).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Test 6: Range > 90 days should return 400 BAD REQUEST")
    void testRangeTooLong() throws Exception {
        // Arrange - Configure service to throw validation exception
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenThrow(new IllegalArgumentException("Maximum search range is 90 days"));

        // Act & Assert - Range exceeds 90 days
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(91).toString()) // 91 DAYS
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Test 7: Invalid location (blank) should return 400 BAD REQUEST")
    void testInvalidLocation() throws Exception {
        // Arrange - Configure service to throw validation exception
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenThrow(new IllegalArgumentException("Location is required and cannot be blank"));

        // Act & Assert - Blank location
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "   ") // BLANK
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    @DisplayName("Test 8: Internal service error should return 500 INTERNAL SERVER ERROR")
    void testInternalServiceError() throws Exception {
        // Arrange - Simulate unexpected service error
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Act & Assert
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Test 9: Empty results should return 200 OK with empty content array")
    void testEmptyResults() throws Exception {
        // Arrange - Service returns empty page
        Page<Car> emptyPage = new PageImpl<>(Collections.emptyList());
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenReturn(emptyPage);

        // Act & Assert
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("Test 10: Pagination parameters should be respected")
    void testPaginationParameters() throws Exception {
        // Arrange
        Car car1 = new Car();
        car1.setId(1L);
        car1.setBrand("BMW");
        car1.setModel("X5");

        Car car2 = new Car();
        car2.setId(2L);
        car2.setBrand("Mercedes");
        car2.setModel("GLE");

        Page<Car> mockPage = new PageImpl<>(List.of(car1, car2));
        when(availabilityService.searchAvailableCars(any(AvailabilitySearchRequestDTO.class)))
                .thenReturn(mockPage);

        // Act & Assert - Test custom page size
        mockMvc.perform(get("/api/cars/availability-search")
                        .param("location", "Beograd")
                        .param("startDate", LocalDate.now().plusDays(1).toString())
                        .param("startTime", "09:00")
                        .param("endDate", LocalDate.now().plusDays(3).toString())
                        .param("endTime", "18:00")
                        .param("page", "0")
                        .param("size", "50") // CUSTOM SIZE
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.currentPage").value(0));
    }
}
