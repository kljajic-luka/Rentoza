package org.example.rentoza.admin.controller;

import org.example.rentoza.admin.AdminUserController;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.admin.service.AdminUserService;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserController Tests")
class AdminUserControllerTest {

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private HateoasAssembler hateoasAssembler;

    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(
                adminUserService,
            auditService,
                currentUser,
                adminUserRepository,
                hateoasAssembler
        );
    }

    @Test
    @DisplayName("DELETE /api/admin/users/{id} returns 410 Gone and never reaches hard-delete service")
    void deleteUser_returnsGone_hardDeleteDisabled() {
        ResponseEntity<Map<String, String>> response = controller.deleteUser(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry("code", "HARD_DELETE_DISABLED");
        assertThat(response.getBody()).containsEntry("error", "HARD_DELETE_DISABLED");

        verify(adminUserService, never()).deleteUser(anyLong(), anyString(), any());
        verifyNoInteractions(adminUserRepository, currentUser, hateoasAssembler);
    }

    @Test
    @DisplayName("approveDobCorrection delegates to service and returns approved response")
    void approveDobCorrection_delegatesToService_returnsApproved() {
        User admin = new User();
        admin.setId(99L);
        User targetUser = new User();
        targetUser.setId(42L);
        targetUser.setDobCorrectionStatus("PENDING");
        targetUser.setDateOfBirth(java.time.LocalDate.of(1990, 1, 1));
        targetUser.setDobCorrectionRequestedValue(java.time.LocalDate.of(1991, 2, 2));
        when(currentUser.id()).thenReturn(99L);
        when(adminUserRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(adminUserRepository.findById(42L)).thenReturn(Optional.of(targetUser));
        when(auditService.toJson(targetUser.getDateOfBirth())).thenReturn("1990-01-01");
        when(auditService.toJson(targetUser.getDobCorrectionRequestedValue())).thenReturn("1991-02-02");

        ResponseEntity<Map<String, String>> response = controller.approveDobCorrection(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "APPROVED");
        assertThat(targetUser.getDateOfBirth()).isEqualTo(java.time.LocalDate.of(1991, 2, 2));
        assertThat(targetUser.getDobCorrectionStatus()).isEqualTo("APPROVED");
        assertThat(targetUser.getDobCorrectionRequestedValue()).isNull();
        verify(adminUserRepository).save(targetUser);
        verify(auditService).logAction(
                eq(admin),
                eq(AdminAction.DOB_CORRECTION_APPROVED),
                eq(ResourceType.USER),
                eq(42L),
                eq("1990-01-01"),
                eq("1991-02-02"),
                eq("DOB correction approved by admin")
        );
    }

    @Test
    @DisplayName("approveDobCorrection returns bad request when no pending request exists")
    void approveDobCorrection_noPendingRequest_returnsBadRequest() {
        User admin = new User();
        admin.setId(99L);
        User targetUser = new User();
        targetUser.setId(42L);
        targetUser.setDobCorrectionStatus("APPROVED");
        when(currentUser.id()).thenReturn(99L);
        when(adminUserRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(adminUserRepository.findById(42L)).thenReturn(Optional.of(targetUser));

        ResponseEntity<Map<String, String>> response = controller.approveDobCorrection(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "NO_PENDING_REQUEST");
        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("rejectDobCorrection delegates to service and returns rejected response")
    void rejectDobCorrection_delegatesToService_returnsRejected() {
        User admin = new User();
        admin.setId(99L);
        User targetUser = new User();
        targetUser.setId(42L);
        targetUser.setDobCorrectionStatus("PENDING");
        when(currentUser.id()).thenReturn(99L);
        when(adminUserRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(adminUserRepository.findById(42L)).thenReturn(Optional.of(targetUser));

        ResponseEntity<Map<String, String>> response = controller.rejectDobCorrection(42L, "reason");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "REJECTED");
        assertThat(targetUser.getDobCorrectionStatus()).isEqualTo("REJECTED");
        assertThat(targetUser.getDobCorrectionReason()).isEqualTo("reason");
        verify(adminUserRepository).save(targetUser);
        verify(auditService).logAction(
                eq(admin),
                eq(AdminAction.DOB_CORRECTION_REJECTED),
                eq(ResourceType.USER),
                eq(42L),
                eq("PENDING"),
                eq("REJECTED"),
                eq("reason")
        );
    }

    @Test
    @DisplayName("rejectDobCorrection returns bad request when no pending request exists")
    void rejectDobCorrection_noPendingRequest_returnsBadRequest() {
        User admin = new User();
        admin.setId(99L);
        User targetUser = new User();
        targetUser.setId(42L);
        targetUser.setDobCorrectionStatus("APPROVED");
        when(currentUser.id()).thenReturn(99L);
        when(adminUserRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(adminUserRepository.findById(42L)).thenReturn(Optional.of(targetUser));

        ResponseEntity<Map<String, String>> response = controller.rejectDobCorrection(42L, "reason");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "NO_PENDING_REQUEST");
        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any(), any());
    }
}