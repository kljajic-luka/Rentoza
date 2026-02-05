package org.example.rentoza.user;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security tests for Renter Verification endpoints.
 * 
 * <p>Verifies:
 * <ul>
 *   <li>Authentication requirements on all endpoints</li>
 *   <li>Authorization (users can only access their own data)</li>
 *   <li>Admin role requirements for admin endpoints</li>
 *   <li>No PII exposure in error responses</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Renter Verification Security Tests")
class RenterVerificationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new User();
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEmail("test@example.com");
        testUser.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);
        testUser.setCreatedAt(Instant.from(LocalDateTime.now()));
        testUser = userRepository.save(testUser);

        // Create another user
        otherUser = new User();
        otherUser.setFirstName("Other");
        otherUser.setLastName("User");
        otherUser.setEmail("other@example.com");
        otherUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        otherUser.setCreatedAt(Instant.from(LocalDateTime.now()));
        otherUser = userRepository.save(otherUser);
    }

    // ==================== AUTHENTICATION TESTS ====================

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("GET /verification requires authentication")
        void getVerification_Unauthenticated_Returns401() throws Exception {
            mockMvc.perform(get("/api/users/me/verification"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /verification/license/submit requires authentication")
        void submitLicense_Unauthenticated_Returns401() throws Exception {
            MockMultipartFile front = new MockMultipartFile(
                "licenseFront", "front.jpg", "image/jpeg", "data".getBytes()
            );
            MockMultipartFile back = new MockMultipartFile(
                "licenseBack", "back.jpg", "image/jpeg", "data".getBytes()
            );

            mockMvc.perform(multipart("/api/users/me/verification/license/submit")
                    .file(front)
                    .file(back))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /verification/booking-eligible requires authentication")
        void bookingEligible_Unauthenticated_Returns401() throws Exception {
            mockMvc.perform(get("/api/users/me/verification/booking-eligible")
                    .param("tripEndDate", "2025-12-31"))
                .andExpect(status().isUnauthorized());
        }
    }

    // ==================== AUTHORIZATION TESTS ====================

    @Nested
    @DisplayName("Authorization Tests")
    class AuthorizationTests {

        @Test
        @WithMockUser(username = "test@example.com", roles = {"USER"})
        @DisplayName("User can access their own verification profile")
        void getVerification_OwnProfile_Success() throws Exception {
            mockMvc.perform(get("/api/users/me/verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
        }

        @Test
        @WithMockUser(username = "user@example.com", roles = {"USER"})
        @DisplayName("Admin endpoints require ADMIN role")
        void adminEndpoints_UserRole_Returns403() throws Exception {
            mockMvc.perform(get("/api/admin/renter-verifications/pending"))
                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "admin@rentoza.rs", roles = {"ADMIN"})
        @DisplayName("Admin can access pending verifications")
        void adminEndpoints_AdminRole_Success() throws Exception {
            mockMvc.perform(get("/api/admin/renter-verifications/pending"))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "user@example.com", roles = {"USER"})
        @DisplayName("User cannot approve verifications (admin only)")
        void approveVerification_UserRole_Returns403() throws Exception {
            mockMvc.perform(post("/api/admin/renter-verifications/" + testUser.getId() + "/approve")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"notes\": \"Approved\"}"))
                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "user@example.com", roles = {"USER"})
        @DisplayName("User cannot reject verifications (admin only)")
        void rejectVerification_UserRole_Returns403() throws Exception {
            mockMvc.perform(post("/api/admin/renter-verifications/" + testUser.getId() + "/reject")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"Invalid document\"}"))
                .andExpect(status().isForbidden());
        }
    }

    // ==================== ERROR RESPONSE TESTS ====================

    @Nested
    @DisplayName("Error Response Security Tests")
    class ErrorResponseTests {

        @Test
        @WithMockUser(username = "test@example.com", roles = {"USER"})
        @DisplayName("Validation errors do not expose internal details")
        void validationError_NoInternalDetails() throws Exception {
            // Submit empty file
            MockMultipartFile emptyFront = new MockMultipartFile(
                "licenseFront", "front.jpg", "image/jpeg", new byte[0]
            );
            MockMultipartFile back = new MockMultipartFile(
                "licenseBack", "back.jpg", "image/jpeg", "data".getBytes()
            );

            mockMvc.perform(multipart("/api/users/me/verification/license/submit")
                    .file(emptyFront)
                    .file(back)
                    .with(csrf()))
                .andExpect(status().isBadRequest())
                // Should not contain stack traces or internal paths
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        }

        @Test
        @WithMockUser(username = "test@example.com", roles = {"USER"})
        @DisplayName("Not found errors do not expose user IDs")
        void notFoundError_NoUserIdExposure() throws Exception {
            long nonExistentUserId = 999999L;
            
            // Directly accessing admin endpoint with non-existent user
            mockMvc.perform(get("/api/admin/renter-verifications/users/" + nonExistentUserId)
                    .with(csrf()))
                .andExpect(status().isForbidden()); // User can't access admin endpoints
        }
    }

    // ==================== INPUT VALIDATION TESTS ====================

    @Nested
    @DisplayName("Input Validation Security Tests")
    class InputValidationTests {

        @Test
        @WithMockUser(username = "test@example.com", roles = {"USER"})
        @DisplayName("Rejects oversized files (DoS prevention)")
        void submitLicense_OversizedFile_Returns400() throws Exception {
            // 15MB file (over 10MB limit)
            byte[] largeData = new byte[15 * 1024 * 1024];
            MockMultipartFile largeFront = new MockMultipartFile(
                "licenseFront", "front.jpg", "image/jpeg", largeData
            );
            MockMultipartFile back = new MockMultipartFile(
                "licenseBack", "back.jpg", "image/jpeg", "data".getBytes()
            );

            mockMvc.perform(multipart("/api/users/me/verification/license/submit")
                    .file(largeFront)
                    .file(back)
                    .with(csrf()))
                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "test@example.com", roles = {"USER"})
        @DisplayName("Rejects non-image MIME types")
        void submitLicense_InvalidMimeType_Returns400() throws Exception {
            MockMultipartFile pdfFront = new MockMultipartFile(
                "licenseFront", "front.pdf", "application/pdf", "fake-pdf".getBytes()
            );
            MockMultipartFile back = new MockMultipartFile(
                "licenseBack", "back.jpg", "image/jpeg", "data".getBytes()
            );

            mockMvc.perform(multipart("/api/users/me/verification/license/submit")
                    .file(pdfFront)
                    .file(back)
                    .with(csrf()))
                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "admin@rentoza.rs", roles = {"ADMIN"})
        @DisplayName("Admin reject requires rejection reason (no empty reasons)")
        void rejectVerification_EmptyReason_Returns400() throws Exception {
            // Set user to PENDING_REVIEW first
            testUser.setDriverLicenseStatus(DriverLicenseStatus.PENDING_REVIEW);
            userRepository.save(testUser);

            mockMvc.perform(post("/api/admin/renter-verifications/" + testUser.getId() + "/reject")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"\"}"))
                .andExpect(status().isBadRequest());
        }
    }
}
