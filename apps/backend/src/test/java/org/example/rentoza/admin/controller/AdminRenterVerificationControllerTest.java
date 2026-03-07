package org.example.rentoza.admin.controller;

import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.document.RenterDocument;
import org.example.rentoza.user.document.RenterDocumentRepository;
import org.example.rentoza.user.document.RenterDocumentType;
import org.example.rentoza.user.document.RenterVerificationAuditRepository;
import org.example.rentoza.user.dto.RenterDocumentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRenterVerificationController - explicit access grants")
class AdminRenterVerificationControllerTest {

    @Mock private RenterVerificationService verificationService;
    @Mock private RenterDocumentRepository documentRepository;
    @Mock private RenterVerificationAuditRepository auditRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupabaseStorageService storageService;
    @Mock private CurrentUser currentUser;
    @Mock private AdminAuditService adminAuditService;

    private AdminRenterVerificationController controller;
    private User reviewer;

    @BeforeEach
    void setUp() {
        reviewer = new User();
        reviewer.setId(99L);
        reviewer.setEmail("reviewer@test.com");
        reviewer.setRole(Role.IDENTITY_REVIEWER);

        controller = new AdminRenterVerificationController(
            verificationService,
            documentRepository,
            auditRepository,
            userRepository,
            storageService,
            currentUser,
            adminAuditService
        );

        lenient().when(currentUser.id()).thenReturn(99L);
        lenient().when(userRepository.findById(99L)).thenReturn(Optional.of(reviewer));
        lenient().when(adminAuditService.toJson(any())).thenReturn("before-json", "after-json");
    }

    @Nested
    @DisplayName("downloadDocument")
    class DownloadDocumentTests {

        @Test
        @DisplayName("returns short-lived signed URL and writes immutable audit log")
        void downloadsFromRenterDocumentsBucket() {
            RenterDocument doc = buildDocument(1L, "renters/1/documents/drivers_license_front/abc.jpg");
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
            when(storageService.getRenterDocumentSignedUrl(anyString(), anyInt()))
                .thenReturn("https://supabase.io/storage/v1/object/sign/renter-documents/download");

            ResponseEntity<AdminRenterVerificationController.DocumentAccessResponse> response =
                controller.downloadDocument(
                    1L,
                    new AdminRenterVerificationController.DocumentAccessRequest(
                        "Fraud investigation case 123",
                        "CASE-123"
                    )
                );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getAccessMode()).isEqualTo("DOWNLOAD");
            assertThat(response.getBody().getFilename()).isEqualTo("test.jpg");
            verify(storageService).getRenterDocumentSignedUrl(
                "renters/1/documents/drivers_license_front/abc.jpg", 120);
            verify(adminAuditService).logAction(
                eq(reviewer),
                eq(AdminAction.DOCUMENT_VIEWED),
                eq(ResourceType.DOCUMENT),
                eq(1L),
                eq("before-json"),
                eq("after-json"),
                contains("download access")
            );
        }
    }

    @Nested
    @DisplayName("revealDocument")
    class RevealDocumentTests {

        @Test
        @DisplayName("returns short-lived review URL from renter-documents bucket")
        void callsRenterSignedUrl() {
            RenterDocument doc = buildDocument(5L, "renters/5/documents/drivers_license_front/doc.jpg");
            when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
            when(storageService.getRenterDocumentSignedUrl(anyString(), anyInt()))
                .thenReturn("https://supabase.io/storage/v1/object/sign/renter-documents/reveal");

            ResponseEntity<AdminRenterVerificationController.DocumentAccessResponse> response =
                controller.revealDocument(
                    5L,
                    new AdminRenterVerificationController.DocumentAccessRequest(
                        "Manual verification review",
                        null
                    )
                );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getAccessMode()).isEqualTo("REVEAL");
            assertThat(response.getBody().getUrl()).startsWith("https://");
            verify(storageService).getRenterDocumentSignedUrl(
                "renters/5/documents/drivers_license_front/doc.jpg", 300);
        }
    }

    @Test
    @DisplayName("getDocumentDetail omits reusable signed URL")
    void getDocumentDetailReturnsMetadataOnly() {
        RenterDocument doc = buildDocument(8L, "renters/8/documents/selfie/selfie.jpg");
        when(documentRepository.findById(8L)).thenReturn(Optional.of(doc));

        ResponseEntity<?> response = controller.getDocumentDetail(8L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(RenterDocumentDTO.class);
        assertThat(response.getBody()).hasFieldOrPropertyWithValue("filename", "test.jpg");
        assertThat(response.getBody()).hasFieldOrProperty("userId");
    }

    @Nested
    @DisplayName("checkDocumentExists")
    class ExistsEndpointTests {

        @Test
        @DisplayName("returns existsInStorage=true when file is present")
        void returnsExistsTrueWhenPresent() {
            RenterDocument doc = buildDocument(10L, "renters/10/documents/selfie/s.jpg");
            when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));
            when(storageService.objectExistsInRenterDocuments(anyString())).thenReturn(true);

            var response = controller.checkDocumentExists(10L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("existsInStorage", true);
            assertThat(response.getBody()).containsEntry("bucket", SupabaseStorageService.BUCKET_RENTER_DOCUMENTS);
        }

        @Test
        @DisplayName("returns existsInStorage=false when file is absent")
        void returnsExistsFalseWhenAbsent() {
            RenterDocument doc = buildDocument(11L, "renters/11/documents/selfie/gone.jpg");
            when(documentRepository.findById(11L)).thenReturn(Optional.of(doc));
            when(storageService.objectExistsInRenterDocuments(anyString())).thenReturn(false);

            var response = controller.checkDocumentExists(11L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("existsInStorage", false);
        }
    }

    private RenterDocument buildDocument(Long id, String documentUrl) {
        User user = new User();
        user.setId(id);
        user.setFirstName("Test");
        user.setLastName("User");

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
