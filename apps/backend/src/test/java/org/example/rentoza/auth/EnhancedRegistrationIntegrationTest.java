package org.example.rentoza.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.deprecated.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.csrf.CustomCookieCsrfTokenRepository;
import org.example.rentoza.security.network.TrustedProxyIpExtractor;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.user.*;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.example.rentoza.user.validation.IdentityDocumentValidator;
import org.example.rentoza.util.HashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for EnhancedAuthController registration endpoints.
 * Tests /api/auth/register/user and /api/auth/register/owner.
 */
@WebMvcTest(controllers = EnhancedAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(EnhancedRegistrationIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "registration.enhanced=true",
        "app.cookie.domain=localhost",
        "app.cookie.secure=false",
        "app.cookie.sameSite=Lax",
        "jwt.expiration=3600000"
})
class EnhancedRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupabaseAuthService supabaseAuthService;

    @MockBean
    private UserService userService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RefreshTokenServiceEnhanced refreshTokenService;

    @MockBean
    private IdentityDocumentValidator identityValidator;

    @MockBean
    private OwnerVerificationService ownerVerificationService;

    @MockBean
    private HashUtil hashUtil;

    @MockBean
    private MissingResourceMetrics missingResourceMetrics;

    @MockBean
    private TrustedProxyIpExtractor trustedProxyIpExtractor;

    // =========================================================================
    // /api/auth/register/user Tests
    // =========================================================================

    @Test
    @DisplayName("registerUser: Happy path with phone, DOB (21+), confirmsAgeEligibility → 200")
    void registerUser_happyPath_returns200() throws Exception {
        User user = createUser(1L, "petar@example.com", Role.USER);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(userRepository.findByEmail("petar@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234567")).thenReturn(Optional.empty());
        when(supabaseAuthService.register(
                eq("petar@example.com"), anyString(), eq("Petar"), eq("Petrovic"), eq(Role.USER)
        )).thenReturn(result);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        String dob = LocalDate.now().minusYears(25).toString();

        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Petar",
                                  "lastName": "Petrovic",
                                  "email": "petar@example.com",
                                  "phone": "0641234567",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationRequired").value(true));

        verify(supabaseAuthService).register(
                eq("petar@example.com"), eq("StrongP@ss1"), eq("Petar"), eq("Petrovic"), eq(Role.USER));
    }

    @Test
    @DisplayName("registerUser: Under-age rejection (DOB < 21 years) → 400")
    void registerUser_underAge_returns400() throws Exception {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByPhone(anyString())).thenReturn(Optional.empty());

        String dob = LocalDate.now().minusYears(20).toString();

        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Mladi",
                                  "lastName": "Korisnik",
                                  "email": "mladi@example.com",
                                  "phone": "0641234568",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerUser: Missing phone → 400")
    void registerUser_missingPhone_returns400() throws Exception {
        String dob = LocalDate.now().minusYears(25).toString();

        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Petar",
                                  "lastName": "Petrovic",
                                  "email": "petar2@example.com",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerUser: Duplicate email → 409 CONFLICT")
    void registerUser_duplicateEmail_returns409() throws Exception {
        when(userRepository.findByEmail("taken@example.com")).thenReturn(
                Optional.of(createUser(99L, "taken@example.com", Role.USER)));

        String dob = LocalDate.now().minusYears(25).toString();

        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Petar",
                                  "lastName": "Petrovic",
                                  "email": "taken@example.com",
                                  "phone": "0641234569",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("registerUser: Missing dateOfBirth → 400")
    void registerUser_missingDob_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Petar",
                                  "lastName": "Petrovic",
                                  "email": "petar3@example.com",
                                  "phone": "0641234570",
                                  "password": "StrongP@ss1",
                                  "confirmsAgeEligibility": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // /api/auth/register/owner Tests
    // =========================================================================

    @Test
    @DisplayName("registerOwner: Happy path individual with JMBG and agreements → 200")
    void registerOwner_individual_happyPath_returns200() throws Exception {
        User user = createUser(10L, "vlasnik@example.com", Role.OWNER);
        user.setOwnerType(OwnerType.INDIVIDUAL);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(userRepository.findByEmail("vlasnik@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234580")).thenReturn(Optional.empty());
        when(userRepository.existsByJmbgHash(anyString())).thenReturn(false);
        when(hashUtil.hash(anyString())).thenReturn("hashed-jmbg");
        when(supabaseAuthService.registerOwner(
                eq("vlasnik@example.com"), anyString(), eq("Vlasnik"), eq("Vlasnikovic")
        )).thenReturn(result);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        String dob = LocalDate.now().minusYears(30).toString();

        mockMvc.perform(post("/api/auth/register/owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Vlasnik",
                                  "lastName": "Vlasnikovic",
                                  "email": "vlasnik@example.com",
                                  "phone": "0641234580",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true,
                                  "ownerType": "INDIVIDUAL",
                                  "jmbg": "1234567890123",
                                  "agreesToHostAgreement": true,
                                  "confirmsVehicleInsurance": true,
                                  "confirmsVehicleRegistration": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationRequired").value(true));

        verify(supabaseAuthService).registerOwner(
                eq("vlasnik@example.com"), eq("StrongP@ss1"), eq("Vlasnik"), eq("Vlasnikovic"));
    }

    @Test
    @DisplayName("registerOwner: Happy path legal entity with PIB and bank account → 200")
    void registerOwner_legalEntity_happyPath_returns200() throws Exception {
        User user = createUser(11L, "firma@example.com", Role.OWNER);
        user.setOwnerType(OwnerType.LEGAL_ENTITY);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(userRepository.findByEmail("firma@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234581")).thenReturn(Optional.empty());
        when(userRepository.existsByPibHash(anyString())).thenReturn(false);
        when(hashUtil.hash(anyString())).thenReturn("hashed-pib");
        when(supabaseAuthService.registerOwner(
                eq("firma@example.com"), anyString(), eq("Firma"), eq("Firmovic")
        )).thenReturn(result);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        String dob = LocalDate.now().minusYears(35).toString();

        mockMvc.perform(post("/api/auth/register/owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Firma",
                                  "lastName": "Firmovic",
                                  "email": "firma@example.com",
                                  "phone": "0641234581",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true,
                                  "ownerType": "LEGAL_ENTITY",
                                  "pib": "123456789",
                                  "bankAccountNumber": "RS1234567890123456789012",
                                  "agreesToHostAgreement": true,
                                  "confirmsVehicleInsurance": true,
                                  "confirmsVehicleRegistration": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationRequired").value(true));

        verify(supabaseAuthService).registerOwner(
                eq("firma@example.com"), eq("StrongP@ss1"), eq("Firma"), eq("Firmovic"));
    }

    @Test
    @DisplayName("registerOwner: Missing JMBG for individual → 400")
    void registerOwner_individual_missingJmbg_returns400() throws Exception {
        when(userRepository.findByEmail("nojmbg@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234582")).thenReturn(Optional.empty());

        String dob = LocalDate.now().minusYears(30).toString();

        mockMvc.perform(post("/api/auth/register/owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Vlasnik",
                                  "lastName": "Vlasnikovic",
                                  "email": "nojmbg@example.com",
                                  "phone": "0641234582",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true,
                                  "ownerType": "INDIVIDUAL",
                                  "agreesToHostAgreement": true,
                                  "confirmsVehicleInsurance": true,
                                  "confirmsVehicleRegistration": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerOwner: Missing PIB for legal entity → 400")
    void registerOwner_legalEntity_missingPib_returns400() throws Exception {
        when(userRepository.findByEmail("nopib@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234583")).thenReturn(Optional.empty());

        String dob = LocalDate.now().minusYears(30).toString();

        mockMvc.perform(post("/api/auth/register/owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Firma",
                                  "lastName": "Firmovic",
                                  "email": "nopib@example.com",
                                  "phone": "0641234583",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true,
                                  "ownerType": "LEGAL_ENTITY",
                                  "bankAccountNumber": "RS1234567890123456789012",
                                  "agreesToHostAgreement": true,
                                  "confirmsVehicleInsurance": true,
                                  "confirmsVehicleRegistration": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerOwner: Missing host agreement → 400")
    void registerOwner_missingHostAgreement_returns400() throws Exception {
        when(userRepository.findByEmail("noagree@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234584")).thenReturn(Optional.empty());

        String dob = LocalDate.now().minusYears(30).toString();

        mockMvc.perform(post("/api/auth/register/owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Vlasnik",
                                  "lastName": "Vlasnikovic",
                                  "email": "noagree@example.com",
                                  "phone": "0641234584",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true,
                                  "ownerType": "INDIVIDUAL",
                                  "jmbg": "1234567890123",
                                  "agreesToHostAgreement": false,
                                  "confirmsVehicleInsurance": true,
                                  "confirmsVehicleRegistration": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Phase 3: Validation regression tests
    // =========================================================================

    @Test
    @DisplayName("registerUser: Password >72 chars rejected with 400")
    void registerUser_passwordTooLong_returns400() throws Exception {
        String dob = LocalDate.now().minusYears(25).toString();
        String longPassword = "Aa1" + "x".repeat(70); // 73 chars, has uppercase, lowercase, digit

        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Petar",
                                  "lastName": "Petrovic",
                                  "email": "longpw@example.com",
                                  "phone": "0641234599",
                                  "password": "%s",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true
                                }
                                """.formatted(longPassword, dob)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerOwner: Password >72 chars rejected with 400")
    void registerOwner_passwordTooLong_returns400() throws Exception {
        String dob = LocalDate.now().minusYears(30).toString();
        String longPassword = "Aa1" + "x".repeat(70); // 73 chars

        mockMvc.perform(post("/api/auth/register/owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Vlasnik",
                                  "lastName": "Vlasnikovic",
                                  "email": "longpw-owner@example.com",
                                  "phone": "0641234598",
                                  "password": "%s",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true,
                                  "ownerType": "INDIVIDUAL",
                                  "jmbg": "1234567890123",
                                  "agreesToHostAgreement": true,
                                  "confirmsVehicleInsurance": true,
                                  "confirmsVehicleRegistration": true
                                }
                                """.formatted(longPassword, dob)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerUser: Works without role in payload (role determined by route)")
    void registerUser_noRoleInPayload_returns200() throws Exception {
        User user = createUser(2L, "norole@example.com", Role.USER);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(userRepository.findByEmail("norole@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("0641234597")).thenReturn(Optional.empty());
        when(supabaseAuthService.register(
                eq("norole@example.com"), anyString(), eq("NoRole"), eq("User"), eq(Role.USER)
        )).thenReturn(result);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        String dob = LocalDate.now().minusYears(25).toString();

        // No "role" field in JSON payload at all
        mockMvc.perform(post("/api/auth/register/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "NoRole",
                                  "lastName": "User",
                                  "email": "norole@example.com",
                                  "phone": "0641234597",
                                  "password": "StrongP@ss1",
                                  "dateOfBirth": "%s",
                                  "confirmsAgeEligibility": true
                                }
                                """.formatted(dob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationRequired").value(true));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User createUser(Long id, String email, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setPassword("SUPABASE_AUTH");
        user.setRole(role);
        user.setAuthUid(UUID.randomUUID());
        return user;
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties appProperties() {
            AppProperties properties = new AppProperties();
            properties.getCookie().setDomain("localhost");
            properties.getCookie().setSecure(false);
            properties.getCookie().setSameSite("Lax");
            return properties;
        }

        @Bean
        CustomCookieCsrfTokenRepository csrfTokenRepository(AppProperties appProperties) {
            return new CustomCookieCsrfTokenRepository(appProperties);
        }
    }
}
