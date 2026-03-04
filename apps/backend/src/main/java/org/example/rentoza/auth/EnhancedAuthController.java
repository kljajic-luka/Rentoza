package org.example.rentoza.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.network.TrustedProxyIpExtractor;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.user.*;
import org.example.rentoza.user.dto.*;
import org.example.rentoza.user.validation.IdentityDocumentValidator;
import org.example.rentoza.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;

/**
 * Enhanced registration endpoints for Phase 1 registration upgrade.
 * 
 * <p>Provides:
 * <ul>
 *   <li>POST /api/auth/register/user - Enhanced user registration with DOB</li>
 *   <li>POST /api/auth/register/owner - Owner registration with JMBG/PIB</li>
 *   <li>POST /api/auth/oauth-complete - Complete Google OAuth registration</li>
 * </ul>
 * 
 * <p>Enabled via: registration.enhanced=true in application.properties
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "registration.enhanced", havingValue = "true", matchIfMissing = false)
public class EnhancedAuthController {

    private static final Logger log = LoggerFactory.getLogger(EnhancedAuthController.class);

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final CsrfTokenRepository csrfTokenRepository;
    private final IdentityDocumentValidator identityValidator;
    private final OwnerVerificationService ownerVerificationService;
    private final HashUtil hashUtil;
    private final SupabaseAuthService supabaseAuthService;
    private final TrustedProxyIpExtractor ipExtractor;

    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    public EnhancedAuthController(
            UserService userService,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AppProperties appProperties,
            CsrfTokenRepository csrfTokenRepository,
            IdentityDocumentValidator identityValidator,
            OwnerVerificationService ownerVerificationService,
            HashUtil hashUtil,
            SupabaseAuthService supabaseAuthService,
            TrustedProxyIpExtractor ipExtractor) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
        this.csrfTokenRepository = csrfTokenRepository;
        this.identityValidator = identityValidator;
        this.ownerVerificationService = ownerVerificationService;
        this.hashUtil = hashUtil;
        this.supabaseAuthService = supabaseAuthService;
        this.ipExtractor = ipExtractor;
    }

    /**
     * Enhanced user registration with dateOfBirth and required phone.
     * Now uses Supabase Auth for authentication with Rentoza user record for profile data.
     * 
     * <p>Handles two scenarios:
     * <ol>
     *   <li><b>Email confirmation required</b>: Returns success with emailConfirmationRequired=true,
     *       no tokens issued. User must confirm email before logging in.</li>
     *   <li><b>No email confirmation</b>: Returns success with tokens in cookies,
     *       user can proceed immediately.</li>
     * </ol>
     */
    @Transactional
    @PostMapping("/register/user")
    public ResponseEntity<?> registerUser(@Valid @RequestBody UserRegisterDTO dto,
                                          HttpServletRequest request,
                                          HttpServletResponse res) {
        try {
            // Canonicalize email at boundary
            dto.setEmail(dto.getEmail().trim().toLowerCase(java.util.Locale.ROOT));

            // Validate age eligibility (21+)
            validateAgeEligibility(dto.getDateOfBirth());

            // Check for existing email in Rentoza
            if (userRepository.findByEmail(dto.getEmail().toLowerCase()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
            }

            // Check for existing phone
            if (userRepository.findByPhone(dto.getPhone()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Phone already registered"));
            }

            // Register via Supabase Auth (creates Supabase user + Rentoza user + mapping)
            SupabaseAuthResult result = supabaseAuthService.register(
                    dto.getEmail(),
                    dto.getPassword(),
                    dto.getFirstName(),
                    dto.getLastName(),
                    Role.USER
            );

            // Update additional fields that Supabase registration doesn't set
            User user = result.getUser();
            user.setPhone(dto.getPhone());
            user.setDateOfBirth(dto.getDateOfBirth());
            user.setDobVerified(false); // User-provided, not verified
            user = userRepository.save(user);

            log.info("User registered via Supabase: email={}, emailConfirmationPending={}", 
                    user.getEmail(), result.isEmailConfirmationPending());
            
            // Handle email confirmation pending scenario
            if (result.isEmailConfirmationPending()) {
                UserResponseDTO userResponse = userService.toUserResponse(user);
                return ResponseEntity.ok(AuthResponseDTO.emailConfirmationRequired(
                        userResponse,
                        "Account created! Please check your email to confirm your account before logging in."
                ));
            }
            
            // No email confirmation required - issue tokens and log user in
            return issueSupabaseTokensAndRespond(result, user, request, res, "Account created successfully");

        } catch (ValidationException e) {
            log.warn("User registration validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        // Unexpected exceptions propagate to GlobalExceptionHandler (sanitized 500)
    }

    /**
     * Owner registration with JMBG/PIB validation and auto-submit to verification queue.
     * Now uses Supabase Auth for authentication with Rentoza user record for profile data.
     *
     * <p>Handles two scenarios:
     * <ol>
     *   <li><b>Email confirmation required</b>: Returns success with emailConfirmationRequired=true,
     *       no tokens issued. User must confirm email before logging in.</li>
     *   <li><b>No email confirmation</b>: Returns success with Supabase tokens in cookies,
     *       user can proceed immediately.</li>
     * </ol>
     */
    @Transactional
    @PostMapping("/register/owner")
    public ResponseEntity<?> registerOwner(@Valid @RequestBody OwnerRegistrationDTO dto,
                                           HttpServletRequest request,
                                           HttpServletResponse res) {
        try {
            // Canonicalize email at boundary
            dto.setEmail(dto.getEmail().trim().toLowerCase(java.util.Locale.ROOT));

            // Validate age eligibility (21+)
            validateAgeEligibility(dto.getDateOfBirth());

            // Validate owner-specific requirements
            validateOwnerRegistration(dto);

            // Check for existing email
            if (userRepository.findByEmail(dto.getEmail().toLowerCase()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
            }

            // Check for existing phone
            if (userRepository.findByPhone(dto.getPhone()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Phone already registered"));
            }

            // Register via Supabase Auth (creates Supabase user + basic Rentoza user + mapping)
            SupabaseAuthResult result = supabaseAuthService.registerOwner(
                    dto.getEmail(),
                    dto.getPassword(),
                    dto.getFirstName(),
                    dto.getLastName()
            );

            // Enrich the Rentoza user with owner-specific fields
            User user = result.getUser();
            user.setPhone(dto.getPhone());
            user.setDateOfBirth(dto.getDateOfBirth());
            user.setDobVerified(false); // User-provided, not verified
            user.setOwnerType(dto.getOwnerType());
            user.setRegistrationStatus(RegistrationStatus.ACTIVE);

            // Set identity document with encryption and hash
            if (dto.getOwnerType() == OwnerType.INDIVIDUAL) {
                user.setJmbg(dto.getJmbg());
                user.setJmbgHash(hashUtil.hash(dto.getJmbg()));
            } else {
                user.setPib(dto.getPib());
                user.setPibHash(hashUtil.hash(dto.getPib()));
                user.setBankAccountNumber(dto.getBankAccountNumber());
            }

            // Persist owner consent provenance for legal compliance
            Instant now = Instant.now();
            user.setHostAgreementAcceptedAt(now);
            user.setVehicleInsuranceConfirmedAt(now);
            user.setVehicleRegistrationConfirmedAt(now);
            user.setConsentIp(ipExtractor.extractClientIp(request));
            user.setConsentUserAgent(truncate(request.getHeader("User-Agent"), 500));
            user.setConsentPolicyVersion(appProperties.getConsent().getPolicyVersion());
            user.setConsentPolicyHash(appProperties.getConsent().getPolicyHash());

            user = userRepository.save(user);

            // Auto-submit to owner verification queue
            ownerVerificationService.submitForVerification(user);

            log.info("Owner registered via Supabase: email={}, type={}, emailConfirmationPending={}",
                    user.getEmail(), dto.getOwnerType(), result.isEmailConfirmationPending());

            // Handle email confirmation pending scenario
            if (result.isEmailConfirmationPending()) {
                UserResponseDTO userResponse = userService.toUserResponse(user);
                return ResponseEntity.ok(AuthResponseDTO.emailConfirmationRequired(
                        userResponse,
                        "Account created! Please check your email to confirm your account before logging in."
                ));
            }

            // No email confirmation required - issue Supabase tokens
            return issueSupabaseTokensAndRespond(result, user, request, res,
                    "Account created successfully. Verification pending.");

        } catch (ValidationException e) {
            log.warn("Owner registration validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IdentityDocumentValidator.ValidationException e) {
            log.warn("Owner identity validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        // Unexpected exceptions propagate to GlobalExceptionHandler (sanitized 500)
    }

    /**
     * Complete Google OAuth registration with missing required fields.
     */
    @Transactional
    @PostMapping("/oauth-complete")
    public ResponseEntity<?> completeOAuth2Registration(
            @Valid @RequestBody GoogleOAuthCompletionDTO dto,
            @AuthenticationPrincipal JwtUserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse res) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }

        try {
            String email = principal.getUsername();
            User user = userService.getOrThrow(email);

            // Validate user is incomplete (prevent re-completion)
            if (user.getRegistrationStatus() == RegistrationStatus.ACTIVE) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Registration already completed"));
            }

            // Validate age eligibility
            validateAgeEligibility(dto.getDateOfBirth());

            // Check for existing phone (excluding current user)
            if (userRepository.existsByPhoneAndIdNot(dto.getPhone(), user.getId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Phone already registered"));
            }

            // Update basic info
            user.setPhone(dto.getPhone());
            user.setDateOfBirth(dto.getDateOfBirth());

            // Update last name if was placeholder
            if (User.GOOGLE_PLACEHOLDER_LAST_NAME.equals(user.getLastName())
                    && dto.getLastName() != null && !dto.getLastName().isBlank()) {
                user.setLastName(dto.getLastName());
            }

            // If owner registration
            if (dto.getOwnerType() != null) {
                validateOwnerCompletion(dto);
                user.setRole(Role.OWNER);
                user.setOwnerType(dto.getOwnerType());

                if (dto.getOwnerType() == OwnerType.INDIVIDUAL) {
                    user.setJmbg(dto.getJmbg());
                    user.setJmbgHash(hashUtil.hash(dto.getJmbg()));
                } else {
                    user.setPib(dto.getPib());
                    user.setPibHash(hashUtil.hash(dto.getPib()));
                    user.setBankAccountNumber(dto.getBankAccountNumber());
                }

                // Persist owner consent provenance for legal compliance
                Instant now = Instant.now();
                user.setHostAgreementAcceptedAt(now);
                user.setVehicleInsuranceConfirmedAt(now);
                user.setVehicleRegistrationConfirmedAt(now);
                user.setConsentIp(ipExtractor.extractClientIp(request));
                user.setConsentUserAgent(truncate(request.getHeader("User-Agent"), 500));
                user.setConsentPolicyVersion(appProperties.getConsent().getPolicyVersion());
                user.setConsentPolicyHash(appProperties.getConsent().getPolicyHash());
            } else {
                // USER (Renter) registration now collects only basic profile metadata here.
                // Driver license data is captured via /verify-license document flow.
                user.setRole(Role.USER);
                user.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);
            }

            // Mark registration as complete
            user.setRegistrationStatus(RegistrationStatus.ACTIVE);
            user = userRepository.save(user);

            // Submit owner to verification queue if owner
            if (user.getRole() == Role.OWNER) {
                ownerVerificationService.submitForVerification(user);
            }

            log.info("OAuth registration completed: email={}, role={}", user.getEmail(), user.getRole());
            
            // SECURITY FIX: Do NOT mint legacy JWT tokens here. 
            // The user already has a valid Supabase session from Google OAuth.
            // Just return the updated user profile — existing Supabase cookies are preserved.
            UserResponseDTO userResponse = userService.toUserResponse(user);
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse, "Profile completed successfully"));

        } catch (ValidationException e) {
            log.warn("OAuth completion validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IdentityDocumentValidator.ValidationException e) {
            log.warn("OAuth completion identity validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        // Unexpected exceptions propagate to GlobalExceptionHandler (sanitized 500)
    }

    // ==================== VALIDATION HELPERS ====================

    private void validateAgeEligibility(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new ValidationException("Date of birth is required");
        }
        LocalDate minDate = SerbiaTimeZone.today().minusYears(21);
        if (dateOfBirth.isAfter(minDate)) {
            throw new ValidationException("You must be at least 21 years old");
        }
    }

    private void validateOwnerRegistration(OwnerRegistrationDTO dto) {
        if (dto.getOwnerType() == null) {
            throw new ValidationException("Owner type is required");
        }

        if (dto.getOwnerType() == OwnerType.INDIVIDUAL) {
            if (dto.getJmbg() == null || dto.getJmbg().isBlank()) {
                throw new ValidationException("JMBG is required for individual owners");
            }
            identityValidator.validateJmbg(dto.getJmbg());
            
            // Check uniqueness
            if (userRepository.existsByJmbgHash(hashUtil.hash(dto.getJmbg()))) {
                throw new ValidationException("JMBG already registered");
            }
        } else {
            if (dto.getPib() == null || dto.getPib().isBlank()) {
                throw new ValidationException("PIB is required for legal entities");
            }
            identityValidator.validatePib(dto.getPib());
            
            // Check uniqueness
            if (userRepository.existsByPibHash(hashUtil.hash(dto.getPib()))) {
                throw new ValidationException("PIB already registered");
            }

            if (dto.getBankAccountNumber() == null || dto.getBankAccountNumber().isBlank()) {
                throw new ValidationException("Bank account is required for legal entities");
            }
            identityValidator.validateIban(dto.getBankAccountNumber());
        }
    }

    private void validateOwnerCompletion(GoogleOAuthCompletionDTO dto) {
        // Enforce same agreement checks as direct owner registration
        if (!Boolean.TRUE.equals(dto.getAgreesToHostAgreement())) {
            throw new ValidationException("You must agree to the host agreement");
        }
        if (!Boolean.TRUE.equals(dto.getConfirmsVehicleInsurance())) {
            throw new ValidationException("You must confirm vehicle insurance");
        }
        if (!Boolean.TRUE.equals(dto.getConfirmsVehicleRegistration())) {
            throw new ValidationException("You must confirm vehicle registration");
        }

        if (dto.getOwnerType() == OwnerType.INDIVIDUAL) {
            if (dto.getJmbg() == null || dto.getJmbg().isBlank()) {
                throw new ValidationException("JMBG is required for individual owners");
            }
            identityValidator.validateJmbg(dto.getJmbg());
        } else {
            if (dto.getPib() == null || dto.getPib().isBlank()) {
                throw new ValidationException("PIB is required for legal entities");
            }
            identityValidator.validatePib(dto.getPib());

            if (dto.getBankAccountNumber() == null || dto.getBankAccountNumber().isBlank()) {
                throw new ValidationException("Bank account is required for legal entities");
            }
            identityValidator.validateIban(dto.getBankAccountNumber());
        }
    }

    // ==================== TOKEN ISSUING ====================

    /**
     * Issue Supabase tokens in cookies and return user response.
     * Uses Supabase access_token and refresh_token instead of custom JWT.
     */
    private ResponseEntity<?> issueSupabaseTokensAndRespond(SupabaseAuthResult result,
                                                            User user,
                                                            HttpServletRequest request,
                                                            HttpServletResponse res,
                                                            String message) {
        // Set Supabase access token cookie
        ResponseCookie accessCookie = createAccessTokenCookie(result.getAccessToken());
        res.addHeader("Set-Cookie", accessCookie.toString());

        // Set Supabase refresh token cookie
        if (result.getRefreshToken() != null) {
            ResponseCookie refreshCookie = createRefreshTokenCookie(result.getRefreshToken());
            res.addHeader("Set-Cookie", refreshCookie.toString());
        }

        ensureCsrfCookie(request, res);

        UserResponseDTO userResponse = userService.toUserResponse(user);
        log.info("User authenticated via Supabase: email={}, role={}", user.getEmail(), user.getRole());

        return ResponseEntity.ok(AuthResponseDTO.success(userResponse, message));
    }

    private ResponseCookie createRefreshTokenCookie(String token) {
        var builder = ResponseCookie.from(CookieConstants.REFRESH_TOKEN, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth/supabase")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofDays(14));

        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }

        return builder.build();
    }

    private ResponseCookie createAccessTokenCookie(String token) {
        var builder = ResponseCookie.from(CookieConstants.ACCESS_TOKEN, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofMillis(jwtExpirationMs));

        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }

        return builder.build();
    }

    private void ensureCsrfCookie(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
        }
        csrfTokenRepository.saveToken(token, request, response);
    }

    // ==================== CONSENT PROVENANCE HELPERS ====================

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
