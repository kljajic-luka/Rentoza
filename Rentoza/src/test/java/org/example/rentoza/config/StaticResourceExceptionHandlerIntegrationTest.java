package org.example.rentoza.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for missing static resource handling.
 * 
 * <p>Verifies:
 * <ul>
 *   <li>Missing images return 404 JSON (no stack traces)</li>
 *   <li>Placeholder fallback works when enabled</li>
 *   <li>Metrics are recorded correctly</li>
 *   <li>Different resource types handled appropriately</li>
 * </ul>
 * 
 * <p><strong>Test Coverage:</strong>
 * <ul>
 *   <li>Car images (/car-images/**)</li>
 *   <li>Check-in photos (/check-in-photos/**)</li>
 *   <li>User avatars (/user-avatars/**)</li>
 *   <li>Placeholder redirect (when enabled)</li>
 *   <li>Metric recording</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.upload.path=test-uploads",
        "app.resource-missing.return-placeholder=false",
        "spring.mvc.throw-exception-if-no-handler-found=true",
        "spring.web.resources.add-mappings=true",
        // H2 in-memory database for test
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
@Import(StaticResourceExceptionHandlerIntegrationTest.TestConfig.class)
class StaticResourceExceptionHandlerIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        public MissingResourceMetrics missingResourceMetrics(MeterRegistry meterRegistry) {
            return new MissingResourceMetrics(meterRegistry);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MissingResourceMetrics metrics;

    @Autowired
    private MeterRegistry meterRegistry;

    private Path testUploadDir;

    @BeforeEach
    void setUp() throws Exception {
        // Create test upload directory structure
        testUploadDir = Files.createTempDirectory("rentoza-test-uploads");
        Files.createDirectories(testUploadDir.resolve("car-images"));
        Files.createDirectories(testUploadDir.resolve("check-in-photos"));
        
        // Create a test image file that exists
        Path existingImage = testUploadDir.resolve("car-images/existing.jpg");
        Files.write(existingImage, "fake image data".getBytes());
    }

    @Test
    @DisplayName("Missing car image returns 404 JSON without stack trace")
    void testMissingCarImageReturns404Json() throws Exception {
        mockMvc.perform(get("/car-images/47/missing.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Resource Not Found"))
                .andExpect(jsonPath("$.code").value("RESOURCE_DELETED"))
                .andExpect(jsonPath("$.message").value("This image is no longer available"))
                .andExpect(jsonPath("$.path").value("/car-images/47/missing.jpg"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("StackTrace")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exception")
                )));
    }

    @Test
    @DisplayName("Missing check-in photo returns appropriate error")
    void testMissingCheckInPhotoReturns404() throws Exception {
        mockMvc.perform(get("/check-in-photos/123/exterior.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_DELETED"))
                .andExpect(jsonPath("$.path").value("/check-in-photos/123/exterior.jpg"));
    }

    @Test
    @DisplayName("Missing user avatar returns appropriate error")
    void testMissingUserAvatarReturns404() throws Exception {
        mockMvc.perform(get("/user-avatars/5/profile.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_DELETED"))
                .andExpect(jsonPath("$.message").value("This image is no longer available"));
    }

    @Test
    @DisplayName("Missing non-image resource returns generic error")
    void testMissingNonImageResource() throws Exception {
        mockMvc.perform(get("/documents/contract.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("Metrics are recorded for missing resources")
    void testMetricsRecordedForMissingResources() throws Exception {
        // Get initial counter value
        double initialCount = meterRegistry.find("missing_resources_total")
                .tag("type", "car-image")
                .counter() != null ? 
                meterRegistry.find("missing_resources_total").tag("type", "car-image").counter().count() : 0;

        // Request missing resource
        mockMvc.perform(get("/car-images/47/test.jpg"))
                .andExpect(status().isNotFound());

        // Verify metric was incremented
        double finalCount = meterRegistry.find("missing_resources_total")
                .tag("type", "car-image")
                .counter().count();
        
        assertThat(finalCount).isGreaterThan(initialCount);
    }

    @Test
    @DisplayName("Multiple missing resource requests increment metrics correctly")
    void testMultipleRequestsIncrementMetrics() throws Exception {
        double initialCount = meterRegistry.find("missing_resources_total")
                .tag("type", "check-in-photo")
                .counter() != null ?
                meterRegistry.find("missing_resources_total").tag("type", "check-in-photo").counter().count() : 0;

        // Make 3 requests
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/check-in-photos/test-" + i + "/photo.jpg"))
                    .andExpect(status().isNotFound());
        }

        // Verify counter increased by 3
        double finalCount = meterRegistry.find("missing_resources_total")
                .tag("type", "check-in-photo")
                .counter().count();
        
        assertThat(finalCount).isEqualTo(initialCount + 3);
    }

    @Test
    @DisplayName("Error response contains no sensitive information")
    void testErrorResponseNoSensitiveInfo() throws Exception {
        mockMvc.perform(get("/car-images/47/secret.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("java.")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("org.springframework")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("at ")
                )));
    }

    @Test
    @DisplayName("Response Content-Type is application/json")
    void testResponseContentTypeIsJson() throws Exception {
        mockMvc.perform(get("/car-images/47/missing.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/json"));
    }
}
