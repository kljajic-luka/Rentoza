package org.example.rentoza.user;

import org.example.rentoza.booking.checkin.verification.IdVerificationProvider;
import org.example.rentoza.booking.checkin.verification.SerbianNameNormalizer;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.user.document.*;
import org.example.rentoza.user.dto.*;
import org.example.rentoza.user.verification.event.VerificationApprovedEvent;
import org.example.rentoza.user.verification.event.VerificationRejectedEvent;
import org.example.rentoza.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for RenterVerificationService.
 * 
 * <p>Covers:
 * <ul>
 *   <li>Document submission (valid/invalid MIME types, sizes)</li>
 *   <li>Booking eligibility (approved, expired, expiring during trip)</li>
 *   <li>Risk scoring and auto-approval</li>
 *   <li>Admin approval/rejection flows</li>
 *   <li>Event publishing</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RenterVerificationService Unit Tests")
class RenterVerificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RenterDocumentRepository documentRepository;
    @Mock private RenterVerificationAuditRepository auditRepository;
    @Mock private SupabaseStorageService storageService;
    @Mock private IdVerificationProvider verificationProvider;
    @Mock private SerbianNameNormalizer nameNormalizer;
    @Mock private HashUtil hashUtil;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ApplicationContext applicationContext;
    @Mock private MultipartFile mockFile;

    @Captor private ArgumentCaptor<VerificationApprovedEvent> approvedEventCaptor;
    @Captor private ArgumentCaptor<VerificationRejectedEvent> rejectedEventCaptor;
    @Captor private ArgumentCaptor<RenterDocument> documentCaptor;

    private RenterVerificationService service;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new RenterVerificationService(
            userRepository,
            documentRepository,
            auditRepository,
            storageService,
            verificationProvider,
            nameNormalizer,
            hashUtil,
            objectMapper,
            eventPublisher,
            applicationContext
        );
        
        // Set default @Value properties
        ReflectionTestUtils.setField(service, "nameMatchThreshold", 0.80);
        ReflectionTestUtils.setField(service, "faceMatchThreshold", 0.95);
        ReflectionTestUtils.setField(service, "selfieRequired", true);
        ReflectionTestUtils.setField(service, "licenseRequired", true);
        ReflectionTestUtils.setField(service, "newAccountThresholdDays", 30);
    }

    // ==================== DOCUMENT SUBMISSION TESTS ====================
    
    @Nested
    @DisplayName("Document Submission Tests")
    class DocumentSubmissionTests {
        
        @Test
        @DisplayName("submitDocument stores document successfully")
        void submitDocument_Success() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);
            when(storageService.uploadRenterDocument(anyLong(), any(), any())).thenReturn("renters/1/documents/drivers_license_front/abc.jpg");
            when(documentRepository.save(any(RenterDocument.class))).thenAnswer(i -> {
                RenterDocument d = i.getArgument(0);
                d.setId(100L);
                return d;
            });

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .expiryDate(LocalDate.now().plusYears(1))
                .build();

            // Act
            RenterDocumentDTO result = service.submitDocument(userId, mockFile, request);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(100L);
            verify(documentRepository).save(any(RenterDocument.class));
            verify(storageService).uploadRenterDocument(anyLong(), any(), any());
        }
        
        @Test
        @DisplayName("submitDocument rejects empty file")
        void submitDocument_EmptyFile_ThrowsValidation() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(mockFile.isEmpty()).thenReturn(true);

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act & Assert
            assertThatThrownBy(() -> service.submitDocument(userId, mockFile, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("empty");
        }
        
        @Test
        @DisplayName("submitDocument rejects file exceeding 5MB")
        void submitDocument_FileTooLarge_ThrowsValidation() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(mockFile.isEmpty()).thenReturn(false);
            when(mockFile.getSize()).thenReturn(15 * 1024 * 1024L); // 15MB

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act & Assert
            assertThatThrownBy(() -> service.submitDocument(userId, mockFile, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("5MB");
        }
        
        @ParameterizedTest
        @ValueSource(strings = {"application/pdf", "image/gif", "text/plain", "application/octet-stream"})
        @DisplayName("submitDocument rejects invalid MIME types")
        void submitDocument_InvalidMimeType_ThrowsValidation(String mimeType) throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(mockFile.isEmpty()).thenReturn(false);
            when(mockFile.getSize()).thenReturn(1000L);
            when(mockFile.getContentType()).thenReturn(mimeType);

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act & Assert
            assertThatThrownBy(() -> service.submitDocument(userId, mockFile, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid file type");
        }
        
        @ParameterizedTest
        @ValueSource(strings = {"image/jpeg", "image/jpg", "image/png"})
        @DisplayName("submitDocument accepts valid MIME types")
        void submitDocument_ValidMimeTypes_Succeeds(String mimeType) throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile(mimeType, 1000L);
            when(storageService.uploadRenterDocument(anyLong(), any(), any())).thenReturn("renters/1/documents/drivers_license_front/abc.jpg");
            when(documentRepository.save(any(RenterDocument.class))).thenAnswer(i -> {
                RenterDocument d = i.getArgument(0);
                d.setId(100L);
                return d;
            });

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act
            RenterDocumentDTO result = service.submitDocument(userId, mockFile, request);

            // Assert
            assertThat(result).isNotNull();
        }
        
        @Test
        @DisplayName("submitDocument updates user status to PENDING_REVIEW")
        void submitDocument_UpdatesUserStatus() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);
            when(storageService.uploadRenterDocument(anyLong(), any(), any())).thenReturn("renters/1/documents/drivers_license_front/abc.jpg");
            when(documentRepository.save(any(RenterDocument.class))).thenAnswer(i -> {
                RenterDocument d = i.getArgument(0);
                d.setId(100L);
                return d;
            });

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act
            service.submitDocument(userId, mockFile, request);

            // Assert
            assertThat(user.getDriverLicenseStatus()).isEqualTo(DriverLicenseStatus.PENDING_REVIEW);
            verify(userRepository, atLeast(1)).save(user);
        }

        @Test
        @DisplayName("submitDocument - same user, same type, same hash => idempotent success (no 500)")
        void submitDocument_SameUserSameTypeSameHash_IdempotentSuccess() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);

            // Simulate: hash already exists for same user + same doc type
            RenterDocument existingDoc = createTestDocument(userId, RenterDocumentType.DRIVERS_LICENSE_FRONT, "aabbccdd");
            when(documentRepository.existsByDocumentHashForDifferentUser(any(), eq(userId))).thenReturn(false);
            when(documentRepository.findByUserIdAndDocumentHash(eq(userId), any())).thenReturn(Optional.of(existingDoc));

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act - must NOT throw
            RenterDocumentDTO result = service.submitDocument(userId, mockFile, request);

            // Assert: returns existing document, no new insert
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(existingDoc.getId());
            verify(documentRepository, never()).save(any(RenterDocument.class));
            verify(storageService, never()).uploadRenterDocument(anyLong(), any(), any());
        }

        @Test
        @DisplayName("submitDocument - same user, different type, same hash => validation reject (no 500)")
        void submitDocument_SameUserDifferentTypeSameHash_RejectsWithValidation() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);

            // Simulate: same hash exists for FRONT, but user is submitting BACK with the same image
            RenterDocument existingFront = createTestDocument(userId, RenterDocumentType.DRIVERS_LICENSE_FRONT, "aabbccdd");
            when(documentRepository.existsByDocumentHashForDifferentUser(any(), eq(userId))).thenReturn(false);
            when(documentRepository.findByUserIdAndDocumentHash(eq(userId), any())).thenReturn(Optional.of(existingFront));

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_BACK) // different type
                .build();

            // Act & Assert: clear validation error, not a 500
            assertThatThrownBy(() -> service.submitDocument(userId, mockFile, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Front and back documents must be different photos");
        }

        @Test
        @DisplayName("submitDocument - different user, same hash => fraud detection reject")
        void submitDocument_DifferentUserSameHash_FraudReject() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);

            // Simulate: hash already exists for a different user
            when(documentRepository.existsByDocumentHashForDifferentUser(any(), eq(userId))).thenReturn(true);

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .build();

            // Act & Assert
            assertThatThrownBy(() -> service.submitDocument(userId, mockFile, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already been submitted by another user");
        }

        @Test
        @DisplayName("submitDocument sets storageBucket = renter-documents on saved document")
        void submitDocument_SetsStorageBucketOnSavedDocument() throws Exception {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);
            when(storageService.uploadRenterDocument(anyLong(), any(), any()))
                .thenReturn("renters/1/documents/drivers_license_front/abc.jpg");
            when(documentRepository.save(any(RenterDocument.class))).thenAnswer(i -> {
                RenterDocument d = i.getArgument(0);
                d.setId(101L);
                return d;
            });

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .expiryDate(LocalDate.now().plusYears(1))
                .build();

            // Act
            service.submitDocument(userId, mockFile, request);

            // Assert: storageBucket must always be renter-documents, never car-documents
            verify(documentRepository).save(documentCaptor.capture());
            assertThat(documentCaptor.getValue().getStorageBucket()).isEqualTo("renter-documents");
        }

        @Test
        @DisplayName("submitDocument calls compensating delete when DB save fails after upload")
        void submitDocument_DbSaveFailure_CompensatingDeleteCalled() throws Exception {
            // Arrange
            Long userId = 2L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            String uploadedPath = "renters/2/documents/drivers_license_front/xyz.jpg";

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            setupValidMockFile("image/jpeg", 1000L);
            when(storageService.uploadRenterDocument(anyLong(), any(), any())).thenReturn(uploadedPath);
            when(documentRepository.save(any(RenterDocument.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB connection lost"));

            DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
                .expiryDate(LocalDate.now().plusYears(1))
                .build();

            // Act & Assert: original DB exception is re-thrown
            assertThatThrownBy(() -> service.submitDocument(userId, mockFile, request))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

            // Compensating delete must be called with the exact uploaded path to prevent orphaned files
            verify(storageService).deleteRenterDocument(uploadedPath);
        }
    }
    
    // ==================== BOOKING ELIGIBILITY TESTS ====================
    
    @Nested
    @DisplayName("Booking Eligibility Tests")
    class BookingEligibilityTests {
        
        @Test
        @DisplayName("checkBookingEligibility allows approved license")
        void checkBookingEligibility_ApprovedLicense_Eligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(1));
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isTrue();
            assertThat(result.getBlockReason()).isNull();
        }
        
        @Test
        @DisplayName("checkBookingEligibility blocks expired license")
        void checkBookingEligibility_ExpiredLicense_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().minusDays(1)); // Expired yesterday
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.LICENSE_EXPIRED);
        }
        
        @Test
        @DisplayName("checkBookingEligibility blocks when license expires during trip")
        void checkBookingEligibility_ExpiresDuringTrip_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusDays(2)); // Expires in 2 days
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act - Trip ends in 5 days (after license expiry)
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.LICENSE_EXPIRES_DURING_TRIP);
        }
        
        @Test
        @DisplayName("checkBookingEligibility blocks NOT_STARTED status")
        void checkBookingEligibility_NotStarted_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.LICENSE_NOT_VERIFIED);
        }
        
        @Test
        @DisplayName("checkBookingEligibility blocks PENDING_REVIEW status")
        void checkBookingEligibility_PendingReview_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.PENDING_REVIEW);
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.VERIFICATION_PENDING);
        }
        
        @Test
        @DisplayName("checkBookingEligibility blocks SUSPENDED status")
        void checkBookingEligibility_Suspended_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.SUSPENDED);
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.ACCOUNT_SUSPENDED);
        }
        
        @Test
        @DisplayName("checkBookingEligibility blocks REJECTED status")
        void checkBookingEligibility_Rejected_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.REJECTED);
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isFalse();
        }
        
        // ==================== H1 FIX: LICENSE TENURE TESTS ====================
        
        @Test
        @DisplayName("H1: checkBookingEligibility blocks license tenure less than 24 months")
        void checkBookingEligibility_TenureLessThan24Months_NotEligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(5));
            user.setDriverLicenseTenureMonths(23); // 23 months - just under 2 years
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert - Should be blocked due to insufficient tenure
            assertThat(result.isEligible()).isFalse();
            assertThat(result.getBlockReason())
                    .isEqualTo(BookingEligibilityDTO.EligibilityBlockReason.LICENSE_TENURE_TOO_SHORT);
            assertThat(result.getMessage()).contains("2 years");
        }
        
        @Test
        @DisplayName("H1: checkBookingEligibility allows license tenure exactly 24 months")
        void checkBookingEligibility_TenureExactly24Months_Eligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(5));
            user.setDriverLicenseTenureMonths(24); // Exactly 2 years
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert - Should be eligible (boundary case)
            assertThat(result.isEligible()).isTrue();
        }
        
        @Test
        @DisplayName("H1: checkBookingEligibility allows license tenure greater than 24 months")
        void checkBookingEligibility_TenureGreaterThan24Months_Eligible() {
            // Arrange
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(5));
            user.setDriverLicenseTenureMonths(60); // 5 years
            user.setAge(30);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert
            assertThat(result.isEligible()).isTrue();
        }
        
        @Test
        @DisplayName("H1: checkBookingEligibility allows null tenure (unverified OCR data)")
        void checkBookingEligibility_TenureNull_Eligible() {
            // Arrange - Tenure might be null if OCR didn't extract issue date
            Long userId = 1L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(5));
            user.setDriverLicenseTenureMonths(null);
            user.setAge(25);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // Act
            BookingEligibilityDTO result = service.checkBookingEligibility(userId, LocalDate.now().plusDays(5));

            // Assert - Should be eligible (null is treated as unknown, not as blocking)
            assertThat(result.isEligible()).isTrue();
        }
    }
    
    // ==================== ADMIN APPROVAL/REJECTION TESTS ====================
    
    @Nested
    @DisplayName("Admin Approval/Rejection Tests")
    class AdminActionTests {
        
        @Test
        @DisplayName("approveVerification updates status and publishes event")
        void approveVerification_Success() {
            // Arrange
            Long userId = 1L;
            Long adminId = 99L;
            User user = createTestUser(userId, DriverLicenseStatus.PENDING_REVIEW);
            user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(1));
            User admin = createTestUser(adminId, DriverLicenseStatus.APPROVED);
            admin.setEmail("admin@rentoza.rs");
            
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
            
            // Provide required documents to pass hardening checks
            RenterDocument frontDoc = createTestDocument(10L, user, RenterDocumentType.DRIVERS_LICENSE_FRONT);
            RenterDocument backDoc = createTestDocument(11L, user, RenterDocumentType.DRIVERS_LICENSE_BACK);
            RenterDocument selfieDoc = createTestDocument(12L, user, RenterDocumentType.SELFIE);
            when(documentRepository.findByUserId(userId)).thenReturn(List.of(frontDoc, backDoc, selfieDoc));

            // Act
            service.approveVerification(userId, adminId, "Looks good");

            // Assert
            assertThat(user.getDriverLicenseStatus()).isEqualTo(DriverLicenseStatus.APPROVED);
            assertThat(user.getDriverLicenseVerifiedAt()).isNotNull();
            assertThat(user.getDriverLicenseVerifiedBy()).isEqualTo(admin);
            
            // Verify event published
            verify(eventPublisher).publishEvent(approvedEventCaptor.capture());
            VerificationApprovedEvent event = approvedEventCaptor.getValue();
            assertThat(event.getUser()).isEqualTo(user);
            assertThat(event.getVerifiedBy()).isEqualTo(admin.getEmail());
        }
        
        @Test
        @DisplayName("approveVerification rejects non-PENDING_REVIEW status")
        void approveVerification_WrongStatus_Throws() {
            // Arrange
            Long userId = 1L;
            Long adminId = 99L;
            User user = createTestUser(userId, DriverLicenseStatus.APPROVED); // Already approved
            User admin = createTestUser(adminId, DriverLicenseStatus.APPROVED);
            
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

            // Act & Assert
            assertThatThrownBy(() -> service.approveVerification(userId, adminId, "Notes"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not pending review");
        }
        
        @Test
        @DisplayName("rejectVerification updates status and publishes event")
        void rejectVerification_Success() {
            // Arrange
            Long userId = 1L;
            Long adminId = 99L;
            User user = createTestUser(userId, DriverLicenseStatus.PENDING_REVIEW);
            User admin = createTestUser(adminId, DriverLicenseStatus.APPROVED);
            admin.setEmail("admin@rentoza.rs");
            
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
            when(documentRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

            // Act
            service.rejectVerification(userId, adminId, "Blurry image, please resubmit");

            // Assert
            assertThat(user.getDriverLicenseStatus()).isEqualTo(DriverLicenseStatus.REJECTED);
            
            // Verify event published
            verify(eventPublisher).publishEvent(rejectedEventCaptor.capture());
            VerificationRejectedEvent event = rejectedEventCaptor.getValue();
            assertThat(event.getUser()).isEqualTo(user);
            assertThat(event.getRejectionReason()).isEqualTo("Blurry image, please resubmit");
        }
        
        @Test
        @DisplayName("rejectVerification requires rejection reason")
        void rejectVerification_NoReason_Throws() {
            // Arrange
            Long userId = 1L;
            Long adminId = 99L;
            User user = createTestUser(userId, DriverLicenseStatus.PENDING_REVIEW);
            User admin = createTestUser(adminId, DriverLicenseStatus.APPROVED);
            
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

            // Act & Assert
            assertThatThrownBy(() -> service.rejectVerification(userId, adminId, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reason is required");
            
            assertThatThrownBy(() -> service.rejectVerification(userId, adminId, "   "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reason is required");
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    private User createTestUser(Long id, DriverLicenseStatus status) {
        User user = new User();
        user.setId(id);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEmail("test" + id + "@example.com");
        user.setDriverLicenseStatus(status);
        user.setCreatedAt(Instant.now().minus(60, java.time.temporal.ChronoUnit.DAYS)); // Not a new account
        return user;
    }
    
    private void setupValidMockFile(String mimeType, long size) throws IOException {
        // JPEG magic bytes: FF D8 FF + padding
        byte[] jpegBytes = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A + padding
        byte[] pngBytes = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        
        byte[] fileBytes = mimeType.contains("png") ? pngBytes : jpegBytes;
        
        when(mockFile.isEmpty()).thenReturn(false);
        when(mockFile.getSize()).thenReturn(size);
        when(mockFile.getContentType()).thenReturn(mimeType);
        when(mockFile.getBytes()).thenReturn(fileBytes);
        when(mockFile.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(fileBytes));
        when(mockFile.getOriginalFilename()).thenReturn("license.jpg");
    }
    
    private RenterDocument createTestDocument(Long id, User user, RenterDocumentType type) {
        RenterDocument doc = new RenterDocument();
        doc.setId(id);
        doc.setUser(user);
        doc.setType(type);
        doc.setDocumentUrl("renters/" + user.getId() + "/documents/" + type.name().toLowerCase() + "/test.jpg");
        doc.setStatus(DocumentVerificationStatus.PENDING);
        doc.setProcessingStatus(RenterDocument.ProcessingStatus.COMPLETED);
        doc.setCreatedAt(LocalDateTime.now());
        return doc;
    }

    /**
     * Overload for duplicate-hash tests: creates a document with a specific hash, no User object needed.
     */
    private RenterDocument createTestDocument(Long userId, RenterDocumentType type, String hash) {
        User user = createTestUser(userId, DriverLicenseStatus.NOT_STARTED);
        RenterDocument doc = new RenterDocument();
        doc.setId(99L);
        doc.setUser(user);
        doc.setType(type);
        doc.setDocumentHash(hash);
        doc.setDocumentUrl("renters/" + userId + "/documents/" + type.name().toLowerCase() + "/test.jpg");
        doc.setStatus(DocumentVerificationStatus.PENDING);
        doc.setProcessingStatus(RenterDocument.ProcessingStatus.COMPLETED);
        doc.setCreatedAt(LocalDateTime.now());
        return doc;
    }
}
