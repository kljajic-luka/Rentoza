package org.example.rentoza.user;

import org.example.rentoza.booking.checkin.CheckInIdVerification;
import org.example.rentoza.booking.checkin.verification.IdVerificationProvider;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.document.RenterDocument;
import org.example.rentoza.user.document.RenterDocumentRepository;
import org.example.rentoza.user.document.RenterDocumentType;
import org.example.rentoza.user.dto.BookingEligibilityDTO;
import org.example.rentoza.user.dto.DriverLicenseSubmissionRequest;
import org.example.rentoza.user.dto.RenterDocumentDTO;
import org.example.rentoza.user.dto.RenterVerificationProfileDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration tests for RenterVerificationService.
 * 
 * <p>Tests end-to-end workflows with real database (H2) and mocked external services.
 * 
 * <p>Covers:
 * <ul>
 *   <li>Complete verification workflow: submit → review → approve → book</li>
 *   <li>Rejection and resubmission flow</li>
 *   <li>License expiry edge cases</li>
 *   <li>Status transitions and audit trail</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("RenterVerificationService Integration Tests")
class RenterVerificationIntegrationTest {

    @Autowired
    private RenterVerificationService verificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RenterDocumentRepository documentRepository;

    @MockBean
    private SupabaseStorageService storageService;

    @MockBean
    private IdVerificationProvider idVerificationProvider;

    private User testUser;
    private User adminUser;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.uploadRenterDocument(anyLong(), anyString(), any()))
            .thenAnswer(invocation -> "renter-documents/" + invocation.getArgument(1) + ".jpg");
        when(storageService.downloadRenterDocument(anyString()))
            .thenReturn(validJpegBytes((byte) 0x0A));
        when(idVerificationProvider.getProviderName()).thenReturn("MOCK");
        when(idVerificationProvider.extractDocumentData(any(), any(), anyString()))
            .thenReturn(IdVerificationProvider.DocumentExtraction.builder()
                .success(true)
                .documentType(CheckInIdVerification.DocumentType.DRIVERS_LICENSE)
                .firstName("Marko")
                .lastName("Petrovic")
                .documentNumber("DL-123456")
                .expiryDate(LocalDate.now().plusYears(2))
                .dateOfBirth(LocalDate.now().minusYears(25))
                .issueDate(LocalDate.now().minusYears(3))
                .confidence(java.math.BigDecimal.valueOf(0.95))
                .build());

        // Create test user
        testUser = new User();
        testUser.setFirstName("Marko");
        testUser.setLastName("Petrović");
        testUser.setEmail("marko@test.rs");
        testUser.setPassword("securePassword123");
        testUser.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);
        testUser.setCreatedAt(Instant.now().minus(60, ChronoUnit.DAYS));
        testUser.setAge(25);
        testUser = userRepository.save(testUser);

        // Create admin user
        adminUser = new User();
        adminUser.setFirstName("Admin");
        adminUser.setLastName("User");
        adminUser.setEmail("admin@rentoza.rs");
        adminUser.setPassword("securePassword123");
        adminUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        adminUser.setCreatedAt(Instant.now().minus(365, ChronoUnit.DAYS));
        adminUser.setAge(40);
        adminUser = userRepository.save(adminUser);
    }

    // ==================== WORKFLOW TESTS ====================

    @Nested
    @DisplayName("Complete Verification Workflow")
    class WorkflowTests {

        @Test
        @DisplayName("Full workflow: NOT_STARTED → PENDING_REVIEW → APPROVED → eligible for booking")
        void fullWorkflow_ManualApproval_Success() throws Exception {
            // Step 1: Initial state
            RenterVerificationProfileDTO initialProfile = verificationService.getVerificationProfile(testUser.getId());
            assertThat(initialProfile.getStatus()).isEqualTo(DriverLicenseStatus.NOT_STARTED);
            
            // Step 2: Submit front of license
            MockMultipartFile frontFile = new MockMultipartFile(
                "licenseFront", "front.jpg", "image/jpeg", validJpegBytes((byte) 0x01)
            );
            DriverLicenseSubmissionRequest frontRequest = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .expiryDate(LocalDate.now().plusYears(2))
                .build();
            
            RenterDocumentDTO frontDoc = verificationService.submitDocument(
                testUser.getId(), frontFile, frontRequest
            );
            assertThat(frontDoc).isNotNull();
            
            // Step 3: Submit back of license
            MockMultipartFile backFile = new MockMultipartFile(
                "licenseBack", "back.jpg", "image/jpeg", validJpegBytes((byte) 0x02)
            );
            DriverLicenseSubmissionRequest backRequest = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_BACK)
                .expiryDate(LocalDate.now().plusYears(2))
                .build();
            
            verificationService.submitDocument(testUser.getId(), backFile, backRequest);

            // Step 4: Submit selfie required for face matching
            MockMultipartFile selfieFile = new MockMultipartFile(
                "selfie", "selfie.jpg", "image/jpeg", validJpegBytes((byte) 0x05)
            );
            DriverLicenseSubmissionRequest selfieRequest = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.SELFIE)
                .build();

            verificationService.submitDocument(testUser.getId(), selfieFile, selfieRequest);

            // Step 5: Verify status is PENDING_REVIEW
            RenterVerificationProfileDTO pendingProfile = verificationService.getVerificationProfile(testUser.getId());
            assertThat(pendingProfile.getStatus()).isEqualTo(DriverLicenseStatus.PENDING_REVIEW);
            
            // Step 6: Check NOT eligible for booking yet
            BookingEligibilityDTO notYetEligible = verificationService.checkBookingEligibility(
                testUser.getId(), LocalDate.now().plusDays(7)
            );
            assertThat(notYetEligible.isEligible()).isFalse();
            
            // Step 7: Admin approves
            verificationService.approveVerification(testUser.getId(), adminUser.getId(), "Documents look valid");
            
            // Step 8: Verify status is APPROVED
            RenterVerificationProfileDTO approvedProfile = verificationService.getVerificationProfile(testUser.getId());
            assertThat(approvedProfile.getStatus()).isEqualTo(DriverLicenseStatus.APPROVED);
            
            // Step 9: Check NOW eligible for booking
            BookingEligibilityDTO nowEligible = verificationService.checkBookingEligibility(
                testUser.getId(), LocalDate.now().plusDays(7)
            );
            assertThat(nowEligible.isEligible()).isTrue();
        }

        @Test
        @DisplayName("Rejection workflow: submit → reject → resubmit → approve")
        void rejectionWorkflow_ResubmitSuccess() throws Exception {
            // Step 1: Submit documents
            MockMultipartFile file = new MockMultipartFile(
                "license", "license.jpg", "image/jpeg", validJpegBytes((byte) 0x03)
            );
            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .expiryDate(LocalDate.now().plusYears(1))
                .build();
            
            verificationService.submitDocument(testUser.getId(), file, request);

            MockMultipartFile backFile = new MockMultipartFile(
                "licenseBack", "license-back.jpg", "image/jpeg", validJpegBytes((byte) 0x06)
            );
            DriverLicenseSubmissionRequest backRequest = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_BACK)
                .expiryDate(LocalDate.now().plusYears(1))
                .build();
            verificationService.submitDocument(testUser.getId(), backFile, backRequest);

            MockMultipartFile selfieFile = new MockMultipartFile(
                "selfie", "selfie.jpg", "image/jpeg", validJpegBytes((byte) 0x07)
            );
            DriverLicenseSubmissionRequest selfieRequest = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.SELFIE)
                .build();
            verificationService.submitDocument(testUser.getId(), selfieFile, selfieRequest);
            
            // Step 2: Admin rejects
            verificationService.rejectVerification(
                testUser.getId(), 
                adminUser.getId(), 
                "Image is blurry, please resubmit a clearer photo"
            );
            
            // Step 3: Verify status is REJECTED
            User rejectedUser = userRepository.findById(testUser.getId()).orElseThrow();
            assertThat(rejectedUser.getDriverLicenseStatus()).isEqualTo(DriverLicenseStatus.REJECTED);
            
            // Step 4: User resubmits (after fixing document)
            MockMultipartFile newFile = new MockMultipartFile(
                "license", "license-clear.jpg", "image/jpeg", validJpegBytes((byte) 0x04)
            );
            
            verificationService.submitDocument(testUser.getId(), newFile, request);
            
            // Step 5: Verify status is back to PENDING_REVIEW
            User resubmittedUser = userRepository.findById(testUser.getId()).orElseThrow();
            assertThat(resubmittedUser.getDriverLicenseStatus()).isEqualTo(DriverLicenseStatus.PENDING_REVIEW);
            
            // Step 6: Admin approves
            verificationService.approveVerification(testUser.getId(), adminUser.getId(), "Much better, approved");
            
            // Step 7: Verify status is APPROVED
            User approvedUser = userRepository.findById(testUser.getId()).orElseThrow();
            assertThat(approvedUser.getDriverLicenseStatus()).isEqualTo(DriverLicenseStatus.APPROVED);
        }
    }

    // ==================== EDGE CASE TESTS ====================

    @Nested
    @DisplayName("License Expiry Edge Cases")
    class ExpiryEdgeCaseTests {

        @Test
        @DisplayName("License expires exactly on trip end date - should be eligible")
        void licenseExpiresOnTripEnd_Eligible() {
            // Arrange
            testUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
            testUser.setDriverLicenseExpiryDate(LocalDate.now().plusDays(7));
            userRepository.save(testUser);

            // Act - Trip ends exactly when license expires
            BookingEligibilityDTO result = verificationService.checkBookingEligibility(
                testUser.getId(), 
                LocalDate.now().plusDays(7) // Same as expiry
            );

            // Assert - Should be eligible (expires AT end, not BEFORE)
            assertThat(result.isEligible()).isTrue();
        }

        @Test
        @DisplayName("License expires one day before trip ends - should be blocked")
        void licenseExpiresDayBeforeTripEnd_NotEligible() {
            // Arrange
            testUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
            testUser.setDriverLicenseExpiryDate(LocalDate.now().plusDays(6));
            userRepository.save(testUser);

            // Act - Trip ends day after license expires
            BookingEligibilityDTO result = verificationService.checkBookingEligibility(
                testUser.getId(), 
                LocalDate.now().plusDays(7)
            );

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason())
                .isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.LICENSE_EXPIRES_DURING_TRIP);
        }

        @Test
        @DisplayName("License already expired - should be blocked")
        void licenseAlreadyExpired_NotEligible() {
            // Arrange
            testUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
            testUser.setDriverLicenseExpiryDate(LocalDate.now().minusDays(1));
            userRepository.save(testUser);

            // Act
            BookingEligibilityDTO result = verificationService.checkBookingEligibility(
                testUser.getId(), 
                LocalDate.now().plusDays(7)
            );

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason())
                .isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.LICENSE_EXPIRED);
        }
    }

    // ==================== PROFILE TESTS ====================

    @Nested
    @DisplayName("Verification Profile Tests")
    class ProfileTests {

        @Test
        @DisplayName("getVerificationProfile returns correct data for approved user")
        void getVerificationProfile_ApprovedUser() {
            // Arrange
            testUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
            testUser.setDriverLicenseExpiryDate(LocalDate.now().plusYears(1));
            testUser.setDriverLicenseVerifiedAt(LocalDateTime.now().minusDays(7));
            userRepository.save(testUser);

            // Act
            RenterVerificationProfileDTO profile = verificationService.getVerificationProfile(testUser.getId());

            // Assert
            assertThat(profile.getStatus()).isEqualTo(DriverLicenseStatus.APPROVED);
            assertThat(profile.getLicenseExpiryDate()).isEqualTo(testUser.getDriverLicenseExpiryDate());
            assertThat(profile.getVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("getVerificationProfile calculates days until expiry correctly")
        void getVerificationProfile_DaysUntilExpiry() {
            // Arrange
            testUser.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
            testUser.setDriverLicenseExpiryDate(LocalDate.now().plusDays(30));
            userRepository.save(testUser);

            // Act
            RenterVerificationProfileDTO profile = verificationService.getVerificationProfile(testUser.getId());

            // Assert
            assertThat(profile.getDaysUntilExpiry()).isBetween(29L, 31L); // Account for test timing
        }
    }

    private byte[] validJpegBytes(byte marker) {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, marker, 0x00, 0x10};
    }
}
