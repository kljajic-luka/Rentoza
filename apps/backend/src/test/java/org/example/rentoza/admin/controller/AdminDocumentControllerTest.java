package org.example.rentoza.admin.controller;

import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.car.CarDocument;
import org.example.rentoza.car.CarDocumentService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDocumentController document download")
class AdminDocumentControllerTest {

    @Mock
    private CarDocumentService documentService;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminDocumentController controller;

    private User admin;

    @Captor
    private ArgumentCaptor<AdminAction> actionCaptor;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(1L);
        admin.setEmail("admin@test.com");
        admin.setFirstName("Admin");
        admin.setLastName("User");
    }

    @Test
    @DisplayName("downloadDocument() returns bytes and writes audit log")
    void downloadDocumentReturnsBytesAndAudits() {
        CarDocument document = new CarDocument();
        document.setId(99L);
        document.setOriginalFilename("registration.jpg");
        document.setMimeType("image/jpeg");
        document.setDocumentUrl("/Users/someone/user-uploads/cars/39/documents/x.jpg");

        byte[] payload = new byte[] { 1, 2, 3 };

        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(documentService.getDocumentById(99L)).thenReturn(document);
        when(documentService.getDocumentContent(document)).thenReturn(payload);

        ResponseEntity<byte[]> response = controller.downloadDocument(99L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
            .contains("inline")
            .contains("registration.jpg");
        assertThat(response.getBody()).isEqualTo(payload);

        verify(auditService).logAction(
            eq(admin),
            actionCaptor.capture(),
            eq(ResourceType.DOCUMENT),
            eq(99L),
            isNull(),
            isNull(),
            anyString()
        );
        assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.DOCUMENT_VIEWED);
    }
}
