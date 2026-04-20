package org.example.rentoza. auth;

import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.security.supabase.SupabaseUserMapping;
import org.example.rentoza.security.supabase.SupabaseUserMappingRepository;
import org.example.rentoza.user.AuthProvider;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for confirmEmail() mapping recovery logic (Phase 02-05).
 *
 * <p>When a user registered but lost their Supabase-to-Rentoza mapping,
 * confirmEmail() should recover via authUid lookup or guarded email fallback
 * rather than telling the user to register again.
 *
 * <p>Recovery is fundamentally different from the login auto-linking removed in 02-01:
 * email confirmation proves email ownership via confirmation link + valid Supabase JWT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupabaseAuthService confirmEmail — Mapping Recovery")
class SupabaseAuthServiceConfirmEmailRecoveryTest {

    @Mock
    private SupabaseAuthClient supabaseClient;

    @Mock
    private SupabaseUserMappingRepository mappingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SupabaseJwtUtil supabaseJwtUtil;

    @InjectMocks
    private SupabaseAuthService authService;

    private static final UUID SUPABASE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_SUPABASE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TEST_EMAIL = "user@example.com";
    private static final String ACCESS_TOKEN = "valid.access.token";
    private static final String REFRESH_TOKEN = "valid.refresh.token";
    private static final Long RENTOZA_USER_ID = 42L;

    @BeforeEach
    void setUp() {
        // Common: token validation passes and extracts user info
        when(supabaseJwtUtil.validateToken(ACCESS_TOKEN)).thenReturn(true);
        when(supabaseJwtUtil.getSupabaseUserId(ACCESS_TOKEN)).thenReturn(SUPABASE_ID);
        when(supabaseJwtUtil.getEmailFromToken(ACCESS_TOKEN)).thenReturn(TEST_EMAIL);
    }

    private User buildSupabaseUser() {
        User user = new User();
        user.setId(RENTOZA_USER_ID);
        user.setEmail(TEST_EMAIL);
        user.setRole(Role.USER);
        user.setAuthProvider(AuthProvider.SUPABASE);
        user.setAuthUid(SUPABASE_ID);
        user.setEnabled(false);
        return user;
    }

    private SupabaseUserMapping buildMapping() {
        return SupabaseUserMapping.builder()
                .supabaseId(SUPABASE_ID)
                .rentozaUserId(RENTOZA_USER_ID)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // =========================================================================
    // Test 1: Recovery via authUid
    // =========================================================================

    @Test
    @DisplayName("recovers mapping using authUid when mapping is missing")
    void testConfirmEmailRecoversUsingAuthUid() {
        // Arrange: no mapping, but user found by authUid
        when(mappingRepository.findById(SUPABASE_ID))
                .thenReturn(Optional.empty())            // first call: empty (trigger recovery)
                .thenReturn(Optional.of(buildMapping())); // after save: found

        User user = buildSupabaseUser();
        when(userRepository.findByAuthUid(SUPABASE_ID)).thenReturn(Optional.of(user));
        when(mappingRepository.findByRentozaUserId(RENTOZA_USER_ID)).thenReturn(Optional.empty());
        when(mappingRepository.save(any(SupabaseUserMapping.class))).thenReturn(buildMapping());
        when(userRepository.findById(RENTOZA_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        // Act
        SupabaseAuthResult result = authService.confirmEmail(ACCESS_TOKEN, REFRESH_TOKEN);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUser().getId()).isEqualTo(RENTOZA_USER_ID);
        verify(mappingRepository).save(any(SupabaseUserMapping.class));
        // Email fallback should NOT be called because authUid lookup succeeded
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    // =========================================================================
    // Test 2: Reject LOCAL account on email fallback
    // =========================================================================

    @Test
    @DisplayName("rejects LOCAL account found by email — linkage conflict")
    void testConfirmEmailRejectsLocalAccountEmailFallback() {
        // Arrange: no mapping, no authUid match, but email matches a LOCAL user
        when(mappingRepository.findById(SUPABASE_ID)).thenReturn(Optional.empty());
        when(userRepository.findByAuthUid(SUPABASE_ID)).thenReturn(Optional.empty());

        User localUser = new User();
        localUser.setId(99L);
        localUser.setEmail(TEST_EMAIL);
        localUser.setAuthProvider(AuthProvider.LOCAL);
        when(userRepository.findByEmailIgnoreCase(TEST_EMAIL)).thenReturn(Optional.of(localUser));

        // Act & Assert
        assertThatThrownBy(() -> authService.confirmEmail(ACCESS_TOKEN, REFRESH_TOKEN))
                .isInstanceOf(SupabaseAuthException.class)
                .hasMessageContaining("Account linkage conflict");

        // Mapping should never be saved
        verify(mappingRepository, never()).save(any(SupabaseUserMapping.class));
    }

    // =========================================================================
    // Test 3: Reject authUid mismatch on email fallback
    // =========================================================================

    @Test
    @DisplayName("rejects SUPABASE user with different authUid — linkage conflict")
    void testConfirmEmailRejectsAuthUidMismatchOnEmailFallback() {
        // Arrange: no mapping, no authUid match, email matches SUPABASE user with different authUid
        when(mappingRepository.findById(SUPABASE_ID)).thenReturn(Optional.empty());
        when(userRepository.findByAuthUid(SUPABASE_ID)).thenReturn(Optional.empty());

        User otherSupabaseUser = new User();
        otherSupabaseUser.setId(88L);
        otherSupabaseUser.setEmail(TEST_EMAIL);
        otherSupabaseUser.setAuthProvider(AuthProvider.SUPABASE);
        otherSupabaseUser.setAuthUid(OTHER_SUPABASE_ID); // different from SUPABASE_ID
        when(userRepository.findByEmailIgnoreCase(TEST_EMAIL)).thenReturn(Optional.of(otherSupabaseUser));

        // Act & Assert
        assertThatThrownBy(() -> authService.confirmEmail(ACCESS_TOKEN, REFRESH_TOKEN))
                .isInstanceOf(SupabaseAuthException.class)
                .hasMessageContaining("Account linkage conflict");

        verify(mappingRepository, never()).save(any(SupabaseUserMapping.class));
    }

    // =========================================================================
    // Test 4: No recoverable user exists
    // =========================================================================

    @Test
    @DisplayName("fails when no recoverable user exists — register again")
    void testConfirmEmailFailsWhenNoRecoverableUserExists() {
        // Arrange: no mapping, no authUid match, no email match
        when(mappingRepository.findById(SUPABASE_ID)).thenReturn(Optional.empty());
        when(userRepository.findByAuthUid(SUPABASE_ID)).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(TEST_EMAIL)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> authService.confirmEmail(ACCESS_TOKEN, REFRESH_TOKEN))
                .isInstanceOf(SupabaseAuthException.class)
                .hasMessageContaining("register again");

        verify(mappingRepository, never()).save(any(SupabaseUserMapping.class));
    }

    // =========================================================================
    // Test 5: Normal path — mapping exists, no recovery triggered
    // =========================================================================

    @Test
    @DisplayName("normal path: mapping exists, no recovery triggered")
    void testConfirmEmailNormalPathUnchanged() {
        // Arrange: mapping exists normally
        when(mappingRepository.findById(SUPABASE_ID)).thenReturn(Optional.of(buildMapping()));

        User user = buildSupabaseUser();
        when(userRepository.findById(RENTOZA_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        // Act
        SupabaseAuthResult result = authService.confirmEmail(ACCESS_TOKEN, REFRESH_TOKEN);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUser().getId()).isEqualTo(RENTOZA_USER_ID);

        // Recovery methods should never be called
        verify(userRepository, never()).findByAuthUid(any(UUID.class));
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    // =========================================================================
    // Test 6: Concurrent recovery race — DataIntegrityViolationException
    // =========================================================================

    @Test
    @DisplayName("handles concurrent recovery race — DataIntegrityViolationException then findById succeeds")
    void testConfirmEmailHandlesConcurrentRecoveryRace() {
        // Arrange: no mapping initially, authUid found
        when(mappingRepository.findById(SUPABASE_ID))
                .thenReturn(Optional.empty())             // first call: empty
                .thenReturn(Optional.of(buildMapping())); // after race: found

        User user = buildSupabaseUser();
        when(userRepository.findByAuthUid(SUPABASE_ID)).thenReturn(Optional.of(user));
        when(mappingRepository.findByRentozaUserId(RENTOZA_USER_ID)).thenReturn(Optional.empty());

        // save throws DataIntegrityViolationException (race condition)
        when(mappingRepository.save(any(SupabaseUserMapping.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key"));

        when(userRepository.findById(RENTOZA_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        // Act
        SupabaseAuthResult result = authService.confirmEmail(ACCESS_TOKEN, REFRESH_TOKEN);

        // Assert: should succeed despite the race
        assertThat(result).isNotNull();
        assertThat(result.getUser().getId()).isEqualTo(RENTOZA_USER_ID);

        // findById called twice: once before recovery, once after race
        verify(mappingRepository, times(2)).findById(SUPABASE_ID);
    }
}
