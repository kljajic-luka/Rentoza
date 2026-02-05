package org.example.rentoza.admin.controller;

import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.OwnerVerificationService;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminOwnerVerificationController audit logging")
class AdminOwnerVerificationControllerTest {

    @Mock
    private OwnerVerificationService verificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AdminAuditService auditService;

    @InjectMocks
    private AdminOwnerVerificationController controller;

    private User admin;
    private User target;

    @Captor
    private ArgumentCaptor<AdminAction> actionCaptor;

    @Captor
    private ArgumentCaptor<String> reasonCaptor;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(1L);
        admin.setEmail("admin@test.com");

        target = new User();
        target.setId(42L);
        target.setEmail("owner@test.com");
        target.setFirstName("Test");
        target.setLastName("Owner");
    }

    @Test
    @DisplayName("approveVerification() writes immutable audit log")
    void approveWritesAuditLog() {
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(auditService.toJson(any())).thenReturn("before", "after");

        controller.approveVerification(42L);

        verify(verificationService).approveIdentityVerification(42L, admin);
        verify(auditService).logAction(
            eq(admin),
            actionCaptor.capture(),
            eq(ResourceType.USER),
            eq(42L),
            eq("before"),
            eq("after"),
            reasonCaptor.capture()
        );

        assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.USER_VERIFIED_ID);
        assertThat(reasonCaptor.getValue()).isEqualTo("Owner identity verified");
    }

    @Test
    @DisplayName("rejectVerification() writes immutable audit log with rejection reason")
    void rejectWritesAuditLogWithReason() {
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(auditService.toJson(any())).thenReturn("before", "after");

        controller.rejectVerification(42L, new AdminOwnerVerificationController.RejectRequest("Incomplete or invalid data"));

        verify(verificationService).rejectIdentityVerification(42L, "Incomplete or invalid data");
        verify(auditService).logAction(
            eq(admin),
            actionCaptor.capture(),
            eq(ResourceType.USER),
            eq(42L),
            eq("before"),
            eq("after"),
            reasonCaptor.capture()
        );

        assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.USER_VERIFICATION_REJECTED);
        assertThat(reasonCaptor.getValue()).isEqualTo("Incomplete or invalid data");
    }
}
