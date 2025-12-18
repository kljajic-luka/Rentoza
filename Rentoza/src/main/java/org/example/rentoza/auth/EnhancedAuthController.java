package org.example.rentoza.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.JwtUtil;
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
    private final JwtUtil jwtUtil;
    private final RefreshTokenServiceEnhanced refreshTokenService;
    private final AppProperties appProperties;
    private final CsrfTokenRepository csrfTokenRepository;
    private final IdentityDocumentValidator identityValidator;
    private final OwnerVerificationService ownerVerificationService;
    private final HashUtil hashUtil;

    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    public EnhancedAuthController(
            UserService userService,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            RefreshTokenServiceEnhanced refreshTokenService,
            AppProperties appProperties,
            CsrfTokenRepository csrfTokenRepository,
            IdentityDocumentValidator identityValidator,
            OwnerVerificationService ownerVerificationService,
            HashUtil hashUtil) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.appProperties = appProperties;
        this.csrfTokenRepository = csrfTokenRepository;
        this.identityValidator = identityValidator;
        this.ownerVerificationService = ownerVerificationService;
        this.hashUtil = hashUtil;
    }

    /**
     * Enhanced user registration with dateOfBirth and required phone.
     */
    @Transactional
    @PostMapping("/register/user")
    public ResponseEntity<?> registerUser(@Valid @RequestBody UserRegisterDTO dto,
                                          HttpServletRequest request,
                                          HttpServletResponse res) {
        try {
            // Validate age eligibility (21+)
            validateAgeEligibility(dto.getDateOfBirth());

            // Check for existing email
            if (userRepository.findByEmail(dto.getEmail().toLowerCase()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
            }

            // Check for existing phone
            if (userRepository.findByPhone(dto.getPhone()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Phone already registered"));
            }

            // Create user
            User user = new User();
            user.setFirstName(dto.getFirstName());
            user.setLastName(dto.getLastName());
            user.setEmail(dto.getEmail().toLowerCase());
            user.setPhone(dto.getPhone());
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
            user.setDateOfBirth(dto.getDateOfBirth());
            user.setDobVerified(false); // User-provided, not verified
            user.setRole(Role.USER);
            user.setRegistrationStatus(RegistrationStatus.ACTIVE);
            user.setAuthProvider(AuthProvider.LOCAL);
            user.setEnabled(true);
            user.setLocked(false);
            user.setCars(new ArrayList<>());
            user.setBookings(new ArrayList<>());
            user.setReviewsGiven(new ArrayList<>());
            user.setReviewsReceived(new ArrayList<>());

            user = userRepository.save(user);

            log.info("User registered successfully via enhanced flow: email={}", user.getEmail());
            return issueTokensAndRespond(user, request, res, "Account created successfully");

        } catch (ValidationException e) {
            log.warn("User registration validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("User registration failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Registration failed"));
        }
    }

    /**
     * Owner registration with JMBG/PIB validation and auto-submit to verification queue.
     */
    @Transactional
    @PostMapping("/register/owner")
    public ResponseEntity<?> registerOwner(@Valid @RequestBody OwnerRegistrationDTO dto,
                                           HttpServletRequest request,
                                           HttpServletResponse res) {
        try {
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

            // Create user
            User user = new User();
            user.setFirstName(dto.getFirstName());
            user.setLastName(dto.getLastName());
            user.setEmail(dto.getEmail().toLowerCase());
            user.setPhone(dto.getPhone());
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
            user.setDateOfBirth(dto.getDateOfBirth());
            user.setDobVerified(false);
            user.setRole(Role.OWNER);
            user.setOwnerType(dto.getOwnerType());
            user.setRegistrationStatus(RegistrationStatus.ACTIVE);
            user.setAuthProvider(AuthProvider.LOCAL);
            user.setEnabled(true);
            user.setLocked(false);
            user.setCars(new ArrayList<>());
            user.setBookings(new ArrayList<>());
            user.setReviewsGiven(new ArrayList<>());
            user.setReviewsReceived(new ArrayList<>());

            // Set identity document with encryption and hash
            if (dto.getOwnerType() == OwnerType.INDIVIDUAL) {
                user.setJmbg(dto.getJmbg());
                user.setJmbgHash(hashUtil.hash(dto.getJmbg()));
            } else {
                user.setPib(dto.getPib());
                user.setPibHash(hashUtil.hash(dto.getPib()));
                user.setBankAccountNumber(dto.getBankAccountNumber());
            }

            user = userRepository.save(user);

            // Auto-submit to owner verification queue
            ownerVerificationService.submitForVerification(user);

            log.info("Owner registered successfully via enhanced flow: email={}, type={}",
                    user.getEmail(), dto.getOwnerType());
            return issueTokensAndRespond(user, request, res, "Account created successfully. Verification pending.");

        } catch (ValidationException e) {
            log.warn("Owner registration validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IdentityDocumentValidator.ValidationException e) {
            log.warn("Owner identity validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Owner registration failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Registration failed"));
        }
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
            }

            // Mark registration as complete
            user.setRegistrationStatus(RegistrationStatus.ACTIVE);
            user = userRepository.save(user);

            // Submit owner to verification queue if owner
            if (user.getRole() == Role.OWNER) {
                ownerVerificationService.submitForVerification(user);
            }

            log.info("OAuth registration completed: email={}, role={}", user.getEmail(), user.getRole());
            return issueTokensAndRespond(user, request, res, "Profile completed successfully");

        } catch (ValidationException e) {
            log.warn("OAuth completion validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IdentityDocumentValidator.ValidationException e) {
            log.warn("OAuth completion identity validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("OAuth completion failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Profile completion failed"));
        }
    }

    // ==================== VALIDATION HELPERS ====================

    private void validateAgeEligibility(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new ValidationException("Date of birth is required");
        }
        LocalDate minDate = LocalDate.now().minusYears(21);
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

    private ResponseEntity<?> issueTokensAndRespond(User user,
                                                    HttpServletRequest request,
                                                    HttpServletResponse res,
                                                    String message) {
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

        String ipAddress = RefreshTokenServiceEnhanced.extractIpAddress(request);
        String userAgent = RefreshTokenServiceEnhanced.extractUserAgent(request);
        String refreshRaw = refreshTokenService.issue(user.getEmail(), ipAddress, userAgent);

        ResponseCookie refreshCookie = createRefreshTokenCookie(refreshRaw);
        ResponseCookie accessCookie = createAccessTokenCookie(accessToken);

        res.addHeader("Set-Cookie", refreshCookie.toString());
        res.addHeader("Set-Cookie", accessCookie.toString());
        ensureCsrfCookie(request, res);

        UserResponseDTO userResponse = userService.toUserResponse(user);
        log.info("User authenticated successfully: email={}, role={}", user.getEmail(), user.getRole());

        return ResponseEntity.ok(AuthResponseDTO.success(userResponse, message));
    }

    private ResponseCookie createRefreshTokenCookie(String token) {
        var builder = ResponseCookie.from(CookieConstants.REFRESH_TOKEN, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth/refresh")
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
}
