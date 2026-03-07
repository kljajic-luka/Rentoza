package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.user.trust.AccountTrustSnapshot;
import org.example.rentoza.user.trust.AccountTrustStateService;

import org.example.rentoza.user.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@Slf4j
@PreAuthorize("isAuthenticated()")
public class UserController {

    private final UserService service;
    private final ProfileService profileService;
    private final ProfileCompletionService profileCompletionService;
    private final AccountTrustStateService accountTrustStateService;
    private final CurrentUser currentUser;

    public UserController(UserService service, ProfileService profileService, 
                          ProfileCompletionService profileCompletionService,
                          AccountTrustStateService accountTrustStateService,
                          CurrentUser currentUser) {
        this.service = service;
        this.profileService = profileService;
        this.profileCompletionService = profileCompletionService;
        this.accountTrustStateService = accountTrustStateService;
        this.currentUser = currentUser;
    }

    /**
     * GET /api/users/me - Backend-verified session endpoint for RLS synchronization
     * Returns authenticated user's complete profile with backend-verified roles
     * Used by frontend for session initialization and role verification
     * 
     * <p><b>CRITICAL:</b> Must return {@code registrationStatus} for:
     * <ul>
     *   <li>Frontend OAuth callback to detect INCOMPLETE profiles</li>
     *   <li>ProfileCompletionGuard to block critical routes</li>
     *   <li>Conditional UI rendering based on profile completeness</li>
     * </ul>
     * 
     * @return CurrentUserDTO with id, email, roles array, registrationStatus, authenticated flag
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile() {
        // SECURITY (H-2): No catch block — let GlobalExceptionHandler handle errors
        // without leaking internal exception messages to client.
        // SECURITY (H-5): Uses typed CurrentUserDTO instead of raw HashMap
        // to prevent accidental data leaks.
        JwtUserPrincipal principal = currentUser.getPrincipal()
                .orElseThrow(() -> new EntityNotFoundException("User not authenticated"));

        var user = service.getUserById(principal.id())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        CurrentUserDTO response = CurrentUserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone() != null ? user.getPhone() : "")
                .age(user.getAge() != null ? user.getAge() : 0)
                .avatarUrl(user.getAvatarUrl() != null ? user.getAvatarUrl() : "")
                .roles(List.of(user.getRole().name()))
                .authenticated(true)
                .registrationStatus(user.getRegistrationStatus() != null
                        ? user.getRegistrationStatus().name()
                        : "ACTIVE")
                .ownerType(user.getOwnerType() != null
                        ? user.getOwnerType().name()
                        : null)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * DEPRECATED: This endpoint is locked. Identity fields (firstName, lastName, password)
     * must not be mutated through this path. Use PATCH /api/users/me for safe profile updates.
     *
     * @deprecated Locked since 2026-02-13 security hardening. Returns HTTP 410 GONE.
     */
    @Deprecated(since = "2026-02-13", forRemoval = true)
    @PatchMapping("/profile")
    public ResponseEntity<?> updateProfile(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
            @Valid @RequestBody UserProfileDTO dto
    ) {
        String caller = (principal != null) ? principal.getUsername() : "anonymous";
        log.warn("SECURITY: Blocked legacy PATCH /api/users/profile call from user={}", caller);
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "ENDPOINT_DEPRECATED",
                "message", "This endpoint has been retired for security reasons. Use PATCH /api/users/me instead."
        ));
    }

    // SECURITY (H-2): Removed try-catch blocks that leaked e.getMessage() to client.
    // GlobalExceptionHandler now returns safe error messages.
    // SECURITY (H-6): @PreAuthorize inherited from class-level annotation.
    @GetMapping("/profile")
    public ResponseEntity<?> getProfileSummary(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        return ResponseEntity.ok(profileService.getProfileSummary(principal.getUsername()));
    }

    @GetMapping("/profile/details")
    public ResponseEntity<?> getProfileDetails(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        return ResponseEntity.ok(profileService.getProfileDetails(principal.getUsername()));
    }

    /**
     * PATCH /api/users/me - Secure partial profile update endpoint
     * Only allows updating safe fields: phone, avatarUrl, bio
     * Sensitive fields (name, email, role) are blocked to enforce identity integrity
     */
    // SECURITY (H-2): Removed broad RuntimeException catch.
    // BadRequestException still caught for validation feedback.
    @PatchMapping("/me")
    public ResponseEntity<?> updateMyProfile(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequestDTO dto
    ) {
        try {
            User updated = service.updateProfileSecure(principal.getUsername(), dto);
            ProfileDetailsDTO details = profileService.getProfileDetails(principal.getUsername());
            return ResponseEntity.ok(details);
        } catch (UserService.BadRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get user profile by ID (for chat service enrichment)
     * Public endpoint for inter-service communication
     */
    // SECURITY (H-6): Internal/service-to-service endpoint — requires authentication
    // via class-level @PreAuthorize. H-2: Let GlobalExceptionHandler handle EntityNotFoundException.
    @GetMapping("/profile/{userId}")
    public ResponseEntity<?> getUserProfileById(@PathVariable Long userId) {
        User user = service.getUserById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return ResponseEntity.ok(new UserResponseDTO(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getAge(),
                user.getRole().name()
        ));
    }

    // ========================================================================
    // PROFILE COMPLETION ENDPOINT (Google OAuth users)
    // ========================================================================

    /**
     * POST /api/users/complete-profile - Complete profile for Google OAuth users.
     * 
     * <p>This endpoint accepts required fields based on user's role:
     * <ul>
        *   <li><b>USER (Renter):</b> phone, dateOfBirth</li>
     *   <li><b>OWNER (Individual):</b> phone, dateOfBirth, jmbg, bankAccountNumber (optional), agreements</li>
     *   <li><b>OWNER (Legal Entity):</b> phone, dateOfBirth, pib, bankAccountNumber (required), agreements</li>
     * </ul>
     * 
     * <p><b>Security:</b>
     * <ul>
     *   <li>Requires authentication (JWT token via cookie)</li>
     *   <li>User can only complete their own profile (validated server-side)</li>
     *   <li>Sensitive fields (JMBG, PIB, license) are encrypted</li>
     *   <li>Duplicate identifiers return 409 Conflict</li>
     * </ul>
     * 
     * @param principal Authenticated user principal
     * @param request Profile completion data
     * @return CompleteProfileResponseDTO with updated status = ACTIVE
     */
    @PostMapping("/complete-profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> completeProfile(
            @org.springframework.security.core.annotation.AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody CompleteProfileRequestDTO request,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        try {
            Long userId = principal.id();
            log.info("Profile completion request for userId={}", userId);

            CompleteProfileResponseDTO response = profileCompletionService.completeProfile(
                    userId, request, httpRequest);
            
            log.info("Profile completed successfully for userId={}, role={}", 
                    userId, response.getRole());
            
            return ResponseEntity.ok(response);

        } catch (EntityNotFoundException e) {
            log.warn("Profile completion failed - user not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "USER_NOT_FOUND",
                    "message", e.getMessage()
            ));
            
        } catch (ProfileCompletionService.DuplicateIdentifierException e) {
            log.warn("Profile completion failed - duplicate identifier: {} - {}", 
                    e.getIdentifierType(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "DUPLICATE_IDENTIFIER",
                    "identifierType", e.getIdentifierType(),
                    "message", e.getMessage()
            ));
            
        } catch (ProfileCompletionService.ValidationException e) {
            log.warn("Profile completion failed - validation errors: {}", e.getErrors());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "VALIDATION_ERROR",
                    "fieldErrors", e.getErrors(),
                    "message", "Proverite unete podatke i pokušajte ponovo"
            ));
            
        } catch (ProfileCompletionService.ProfileCompletionException e) {
            log.warn("Profile completion failed - {}: {}", e.getErrorCode(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getErrorCode(),
                    "message", e.getMessage()
            ));
            
        } catch (Exception e) {
            log.error("Profile completion failed - unexpected error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Došlo je do greške. Molimo pokušajte ponovo."
            ));
        }
    }

    /**
     * GET /api/users/profile-completion-status - Check if profile completion is required.
     * 
     * <p>Returns the user's registration status and which fields are missing.
     * Used by frontend to determine if profile completion modal should be shown.
     */
    @GetMapping("/profile-completion-status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getProfileCompletionStatus(
            @org.springframework.security.core.annotation.AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        try {
            User user = service.getUserById(principal.id())
                    .orElseThrow(() -> new EntityNotFoundException("User not found"));

                AccountTrustSnapshot snapshot = accountTrustStateService.snapshot(user);
            
            return ResponseEntity.ok(Map.of(
                    "registrationStatus", snapshot.registrationStatus().name(),
                    "isComplete", !snapshot.needsProfileCompletion(),
                    "role", user.getRole().name(),
                    "ownerType", user.getOwnerType() != null ? user.getOwnerType().name() : "INDIVIDUAL",
                    "missingFields", snapshot.missingProfileFields(),
                    "accountAccessState", snapshot.accountAccessState().name(),
                    "canAuthenticate", snapshot.canAuthenticate(),
                    "canBookAsRenter", snapshot.canBookAsRenter(),
                    "renterVerificationState", snapshot.renterVerificationState().name(),
                    "ownerVerificationState", snapshot.ownerVerificationState().name()
            ));
            
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "USER_NOT_FOUND",
                    "message", e.getMessage()
            ));
        }
    }

    // ========== DOB CORRECTION REQUEST (M-9) ==========

    /**
     * SECURITY (M-9): Request a date-of-birth correction when verified DOB is incorrect.
     * Creates a pending correction request that requires admin review.
     * Only allowed if DOB is currently verified (from license OCR) and no pending request exists.
     */
    @PostMapping("/me/dob-correction")
    public ResponseEntity<?> requestDobCorrection(
            @org.springframework.security.core.annotation.AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody DobCorrectionRequestDTO request
    ) {
        User user = service.getUserById(principal.id())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        if (!user.isDobVerified()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "DOB_NOT_VERIFIED",
                    "message", "Korekcija datuma rođenja je moguća samo nakon verifikacije"
            ));
        }

        if ("PENDING".equals(user.getDobCorrectionStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "CORRECTION_ALREADY_PENDING",
                    "message", "Zahtev za korekciju datuma rođenja je već podnet"
            ));
        }

        // Validate new DOB age bounds
        int newAge = java.time.Period.between(request.getNewDateOfBirth(), 
                org.example.rentoza.config.timezone.SerbiaTimeZone.today()).getYears();
        if (newAge < 21 || newAge > 120) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_DOB",
                    "message", "Novi datum rođenja nije validan"
            ));
        }

        user.setDobCorrectionRequestedValue(request.getNewDateOfBirth());
        user.setDobCorrectionRequestedAt(java.time.LocalDateTime.now());
        user.setDobCorrectionReason(request.getReason());
        user.setDobCorrectionStatus("PENDING");
        service.saveUser(user);

        log.info("AUDIT: DOB correction requested for userId={}, currentDob={}, requestedDob={}", 
                principal.id(), user.getDateOfBirth(), request.getNewDateOfBirth());

        return ResponseEntity.ok(Map.of(
                "status", "PENDING",
                "message", "Zahtev za korekciju datuma rođenja je uspešno podnet. Administrator će pregledati vaš zahtev."
        ));
    }

}
