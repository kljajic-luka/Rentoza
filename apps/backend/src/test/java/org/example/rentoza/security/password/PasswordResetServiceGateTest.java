package org.example.rentoza.security.password;

import org.example.rentoza.notification.mail.MailService;
import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceGateTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository tokenRepository;
    private PasswordPolicyService passwordPolicyService;
    private PasswordHistoryRepository passwordHistoryRepository;
    private SupabaseAuthClient supabaseAuthClient;
    private MailService mailService;

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(PasswordResetTokenRepository.class);
        passwordPolicyService = mock(PasswordPolicyService.class);
        passwordHistoryRepository = mock(PasswordHistoryRepository.class);
        supabaseAuthClient = mock(SupabaseAuthClient.class);
        mailService = mock(MailService.class);

        passwordResetService = new PasswordResetService(
                userRepository,
                tokenRepository,
                passwordPolicyService,
                passwordHistoryRepository,
                supabaseAuthClient,
                mailService
        );

        ReflectionTestUtils.setField(passwordResetService, "frontendUrl", "https://staging.rentoza.rs");
    }

    @Test
    @DisplayName("Forgot-password sends template email with /auth/reset-password?token= link")
    void requestPasswordReset_sendsResetLinkEmail() {
        User user = new User();
        user.setId(42L);
        user.setEmail("user@example.com");
        user.setPassword("$2a$10$abc");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        passwordResetService.requestPasswordReset("user@example.com", "1.2.3.4");

        verify(tokenRepository).invalidateAllForUser(eq(42L), any(Instant.class));
        verify(mailService).sendTemplatedEmail(
                eq("user@example.com"),
                contains("Reset"),
                eq("emails/password-reset"),
                argThat(vars -> {
                    Object link = vars.get("resetLink");
                    return link instanceof String
                            && ((String) link).startsWith("https://staging.rentoza.rs/auth/reset-password?token=")
                            && ((String) link).length() > "https://staging.rentoza.rs/auth/reset-password?token=".length();
                })
        );
    }

    @Test
    @DisplayName("Reset rejects invalid/expired token")
    void resetPassword_invalidOrExpiredToken_throws() {
        when(tokenRepository.findValidToken(anyString(), any(Instant.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.resetPassword("bad-token", "Aa123456!"))
                .isInstanceOf(PasswordResetService.PasswordResetException.class)
                .hasMessageContaining("invalid or has expired");
    }

    @Test
    @DisplayName("Reset token is one-time: successful reset marks token used")
    void resetPassword_marksTokenUsed() {
        PasswordResetToken resetToken = new PasswordResetToken(
                42L,
                "abc",
                Instant.now().plusSeconds(3600),
                "1.2.3.4"
        );

        User user = new User();
        user.setId(42L);
        user.setEmail("user@example.com");
        user.setPassword("old-hash");
        user.setAuthUid(UUID.randomUUID());

        when(tokenRepository.findValidToken(anyString(), any(Instant.class))).thenReturn(Optional.of(resetToken));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(passwordPolicyService.validatePasswordChange(eq(42L), eq("Aa123456!"))).thenReturn(List.of());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        passwordResetService.resetPassword("raw-token", "Aa123456!");

        assertThat(resetToken.isUsed()).isTrue();
        assertThat(resetToken.getUsedAt()).isNotNull();
        verify(passwordPolicyService).recordPassword(eq(42L), anyString());
        verify(supabaseAuthClient).updateUserPassword(eq(user.getAuthUid()), eq("Aa123456!"));
    }

    @Test
    @DisplayName("Token expiry policy is exactly 1 hour")
    void expiryDuration_isOneHour() {
        assertThat(PasswordResetToken.EXPIRY_DURATION_SECONDS).isEqualTo(3600L);
    }

    @Test
    @DisplayName("Service still starts if MailService is absent")
    void constructor_allowsNullMailService() {
        PasswordResetService nullableMailService = new PasswordResetService(
                userRepository,
                tokenRepository,
                passwordPolicyService,
                passwordHistoryRepository,
                supabaseAuthClient,
                null
        );

        ReflectionTestUtils.setField(nullableMailService, "frontendUrl", "https://staging.rentoza.rs");

        User user = new User();
        user.setId(7L);
        user.setEmail("nomail@example.com");
        user.setPassword("$2a$10$abc");

        when(userRepository.findByEmail("nomail@example.com")).thenReturn(Optional.of(user));

        nullableMailService.requestPasswordReset("nomail@example.com", "1.2.3.4");

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verifyNoInteractions(mailService);
    }
}
