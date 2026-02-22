package org.example.rentoza.admin.controller;

import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.document.RenterDocument;
import org.example.rentoza.user.document.RenterDocumentRepository;
import org.example.rentoza.user.document.RenterDocumentType;
import org.example.rentoza.user.document.RenterVerificationAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminRenterVerificationController storage routing.
 *
 * <p>Acceptance criteria:
 * <ul>
 *   <li>downloadDocument uses renter-documents bucket, never car-documents</li>
 *   <li>Missing file returns clean 404 with stable code DOCUMENT_FILE_MISSING</li>
 *   <li>getDocumentSignedUrl calls getRenterDocumentSignedUrl, not car-documents methods</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRenterVerificationController - storage routing")
class AdminRenterVerificationControllerTest {

    @Mock private RenterVerificationService verificationService;
    @Mock private RenterDocumentRepository documentRepository;
    @Mock private RenterVerificationAuditRepository auditRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupabaseStorageService storageService;
    @Mock private CurrentUser currentUser;

    private AdminRenterVerificationController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminRenterVerificationController(
            verificationService,
            documentRepository,
            auditRepository,
            userRepository,
            storageService,
            currentUser
        );
    }

    // ==================== downloadDocument TESTS ====================

    @Nested
    @DisplayName("downloadDocument")
    class DownloadDocumentTests {

        @Test
        @DisplayName("calls downloadRenterDocument from renter-documents bucket")
        void downloadsFromRenterDocumentsBucket() throws Exception {
            // Arrange
            RenterDocument doc = buildDocument(1L, "renters/1/documents/drivers_license_front/abc.jpg");
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
            when(storageService.downloadRenterDocument(anyString())).thenReturn(new byte[]{0x01, 0x02});

            // Act
            ResponseEntity<byte[]> response = controller.downloadDocument(1L);

            // Assert: correct method called with exact path
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(storageService).downloadRenterDocument("renters/1/documents/drivers_license_front/abc.jpg");

            // Must never call car-documents methods
            verify(storageService, never()).downloadCarDocument(anyString());
        }

        @Test
        @DisplayName("returns 404 JSON with code DOCUMENT_FILE_MISSING when file is absent in storage")
        void returns404WithCodeWhenFileMissing() throws Exception {
            // Arrange
            RenterDocument doc = buildDocument(2L, "renters/2/documents/selfie/missing.jpg");
            when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));
            when(storageService.downloadRenterDocument(anyString()))
                .thenThrow(new IOException("File not found: renters/2/documents/selfie/missing.jpg"));

            // Act
            ResponseEntity<byte[]> response = controller.downloadDocument(2L);

            // Assert: clean 404
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);

            // Response body must contain stable error code
            String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).contains("DOCUMENT_FILE_MISSING");
        }

        @Test
        @DisplayName("re-throws as RuntimeException for non-404 IO errors (not a missing-file situation)")
        void rethrowsRuntimeExceptionForGenericIOError() throws Exception {
            // Arrange
            RenterDocument doc = buildDocument(3L, "renters/3/documents/drivers_license_back/img.jpg");
            when(documentRepository.findById(3L)).thenReturn(Optional.of(doc));
            when(storageService.downloadRenterDocument(anyString()))
                .thenThrow(new IOException("Network timeout"));

            // Act & Assert: non-404 errors bubble as RuntimeException (→ 500)
            assertThatThrownBy(() -> controller.downloadDocument(3L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read document");
        }
    }

    // ==================== getDocumentSignedUrl TESTS ====================

    @Nested
    @DisplayName("getDocumentSignedUrl")
    class SignedUrlTests {

        @Test
        @DisplayName("calls getRenterDocumentSignedUrl with 900-second expiry")
        void callsRenterSignedUrl() {
            // Arrange
            RenterDocument doc = buildDocument(5L, "renters/5/documents/drivers_license_front/doc.jpg");
            when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
            when(storageService.getRenterDocumentSignedUrl(anyString(), anyInt()))
                .thenReturn("https://supabase.io/storage/v1/object/sign/renter-documents/...");

            // Act
            var response = controller.getDocumentSignedUrl(5L);

            // Assert: real signed URL returned, correct bucket method used
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(storageService).getRenterDocumentSignedUrl(
                "renters/5/documents/drivers_license_front/doc.jpg", 900);
            assertThat(response.getBody().getUrl()).startsWith("https://");

            // Must never call car-documents signing
            verify(storageService, never()).getCarDocumentSignedUrl(anyString(), anyInt());
        }
    }

    // ==================== checkDocumentExists TESTS ====================

    @Nested
    @DisplayName("checkDocumentExists")
    class ExistsEndpointTests {

        @Test
        @DisplayName("returns existsInStorage=true when file is present")
        void returnsExistsTrueWhenPresent() {
            // Arrange
            RenterDocument doc = buildDocument(10L, "renters/10/documents/selfie/s.jpg");
            when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));
            when(storageService.objectExistsInRenterDocuments(anyString())).thenReturn(true);

            // Act
            var response = controller.checkDocumentExists(10L);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("existsInStorage", true);
            assertThat(response.getBody()).containsEntry("bucket", SupabaseStorageService.BUCKET_RENTER_DOCUMENTS);
        }

        @Test
        @DisplayName("returns existsInStorage=false when file is absent")
        void returnsExistsFalseWhenAbsent() {
            // Arrange
            RenterDocument doc = buildDocument(11L, "renters/11/documents/selfie/gone.jpg");
            when(documentRepository.findById(11L)).thenReturn(Optional.of(doc));
            when(storageService.objectExistsInRenterDocuments(anyString())).thenReturn(false);

            // Act
            var response = controller.checkDocumentExists(11L);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("existsInStorage", false);
        }
    }

    // ==================== HELPERS ====================

    private RenterDocument buildDocument(Long id, String documentUrl) {
        User user = new User();
        user.setId(id);

        RenterDocument doc = new RenterDocument();
        doc.setId(id);
        doc.setUser(user);
        doc.setType(RenterDocumentType.DRIVERS_LICENSE_FRONT);
        doc.setDocumentUrl(documentUrl);
        doc.setMimeType("image/jpeg");
        doc.setOriginalFilename("test.jpg");
        return doc;
    }
}
