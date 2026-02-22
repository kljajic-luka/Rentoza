import { User, UserProfile } from './user.model';

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * @deprecated Use UserRegisterRequest or OwnerRegisterRequest instead.
 * Kept for backward compatibility with legacy registration endpoint.
 */
export interface RegisterRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phone?: string;
  role?: string;
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 2: ENHANCED REGISTRATION DTOs
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Enhanced user registration request with required phone, DOB, and age confirmation.
 * Used for standard USER role registration via POST /api/auth/register/user
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 25-59
 */
export interface UserRegisterRequest {
  /** User role - REQUIRED for Supabase Auth (usually "USER" for standard registration) */
  role?: 'USER' | 'OWNER';
  /** First name (3-50 characters) */
  firstName: string;
  /** Last name (3-50 characters) */
  lastName: string;
  /** Valid email address (unique) */
  email: string;
  /** Phone number (8-15 digits, unique) - REQUIRED in enhanced flow */
  phone: string;
  /** Password (8+ chars, uppercase, lowercase, number) */
  password: string;
  /** Date of birth (YYYY-MM-DD format, must be 21+) - REQUIRED in enhanced flow */
  dateOfBirth: string;
  /** User confirms they are 21 years or older - REQUIRED */
  confirmsAgeEligibility: boolean;
}

/**
 * Owner type enumeration for host registration.
 * Determines which identity document is required (JMBG vs PIB).
 */
export type OwnerType = 'INDIVIDUAL' | 'LEGAL_ENTITY';

/**
 * Enhanced owner/host registration request with identity verification fields.
 * Used for OWNER role registration via POST /api/auth/register/owner
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 62-138
 */
export interface OwnerRegisterRequest extends UserRegisterRequest {
  /** User role - REQUIRED for Supabase Auth (always "OWNER" for owner registration) */
  role?: 'OWNER';
  /** Owner type determines required identity document */
  ownerType: OwnerType;
  /** Serbian personal ID (13 digits) - Required if ownerType=INDIVIDUAL */
  jmbg?: string;
  /** Serbian tax ID (9 digits) - Required if ownerType=LEGAL_ENTITY */
  pib?: string;
  /** Serbian IBAN (RS + 22 digits) - Optional for INDIVIDUAL, Required for LEGAL_ENTITY */
  bankAccountNumber?: string;
  /** Host agreement acceptance - REQUIRED */
  agreesToHostAgreement: boolean;
  /** Vehicle insurance confirmation - REQUIRED */
  confirmsVehicleInsurance: boolean;
  /** Vehicle registration confirmation - REQUIRED */
  confirmsVehicleRegistration: boolean;
}

/**
 * Google OAuth profile completion request for users with INCOMPLETE registration status.
 * Used when Google OAuth provides incomplete profile data (missing phone, DOB).
 * Called via POST /api/auth/oauth-complete
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 141-187
 */
export interface GoogleOAuthCompletionRequest {
  /** Last name (3-50 chars) - Required only if was "GooglePlaceholder" */
  lastName?: string;
  /** Phone number (8-15 digits) - Required (Google doesn't provide) */
  phone: string;
  /** Date of birth (YYYY-MM-DD) - Required (Google doesn't provide) */
  dateOfBirth: string;
  /** Age confirmation checkbox */
  confirmsAgeEligibility: boolean;
  // ─────────────────────────────────────────────────────────────────────────
  // Driver license fields (only if completing as USER/Renter)
  // ─────────────────────────────────────────────────────────────────────────
  /** Driver license number - Required for USER role */
  driverLicenseNumber?: string;
  /** Driver license expiry date (YYYY-MM-DD) - Required for USER role */
  driverLicenseExpiryDate?: string;
  /** Driver license issuing country - Required for USER role */
  driverLicenseCountry?: string;
  // ─────────────────────────────────────────────────────────────────────────
  // Optional owner fields (only if completing as OWNER)
  // ─────────────────────────────────────────────────────────────────────────
  /** Owner type - Required if registering as owner */
  ownerType?: OwnerType;
  /** JMBG (13 digits) - Required if ownerType=INDIVIDUAL */
  jmbg?: string;
  /** PIB (9 digits) - Required if ownerType=LEGAL_ENTITY */
  pib?: string;
  /** Bank account IBAN - Optional for INDIVIDUAL, Required for LEGAL_ENTITY */
  bankAccountNumber?: string;
  /** Host agreement - Required if owner */
  agreesToHostAgreement?: boolean;
  /** Insurance confirmation - Required if owner */
  confirmsVehicleInsurance?: boolean;
  /** Registration confirmation - Required if owner */
  confirmsVehicleRegistration?: boolean;
}

/**
 * Registration status for tracking incomplete OAuth profiles.
 * Backend sets INCOMPLETE for Google OAuth users until profile completion.
 */
export type RegistrationStatus = 'INCOMPLETE' | 'ACTIVE' | 'SUSPENDED' | 'DELETED';

// ═══════════════════════════════════════════════════════════════════════════

/**
 * Authentication response from backend.
 *
 * SECURITY HARDENING (Phase 1):
 * - Access tokens are NEVER returned in JSON body (XSS prevention)
 * - Tokens are delivered exclusively via HttpOnly cookies
 * - This interface only contains user profile data and metadata
 */
export interface AuthResponse {
  /** Authenticated user profile */
  user: User | UserProfile;

  /** Authentication status indicator */
  authenticated: boolean;

  /** Optional message (e.g., "Welcome back!") */
  message?: string;

  /**
   * Email confirmation required flag (Supabase Auth).
   * When true: user must confirm email before logging in.
   * Frontend should show "check your email" message and NOT persist session.
   */
  emailConfirmationRequired?: boolean;
}

/**
 * Extended user response with registration status for OAuth completion detection.
 * Used in auth callback to determine if user needs to complete profile.
 */
export interface EnhancedUserProfile extends UserProfile {
  /** Registration completion status (INCOMPLETE = needs OAuth completion) */
  registrationStatus?: RegistrationStatus;
  /** Date of birth (ISO format) */
  dateOfBirth?: string;
  /** Owner type if OWNER role */
  ownerType?: OwnerType;
}

// ═══════════════════════════════════════════════════════════════════════════
// SUPABASE GOOGLE OAUTH TYPES
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Response from Google OAuth initialization via Supabase.
 * Contains the authorization URL to redirect to and state token for CSRF protection.
 */
export interface GoogleAuthInitResponse {
  /** Full authorization URL to redirect user to (Google via Supabase) */
  authorizationUrl: string;
  /** State token for CSRF validation (stored in sessionStorage) */
  state: string;
}

/**
 * Response from Google OAuth callback via Supabase.
 * Contains the authenticated user and their registration status.
 */
export interface SupabaseGoogleCallbackResponse {
  /** Whether the OAuth flow was successful */
  success: boolean;
  /** Authenticated user profile (may have INCOMPLETE registration status) */
  user: EnhancedUserProfile;
  /** Success message */
  message?: string;
  /** Registration status - INCOMPLETE means user needs to complete profile */
  registrationStatus: RegistrationStatus;
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 3: PASSWORD RECOVERY DTOs (Turo Standard)
// ═══════════════════════════════════════════════════════════════════════════

/** Request to initiate password reset (forgot password) */
export interface ForgotPasswordRequest {
  email: string;
}

/** Request to reset password using a one-time token */
export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

/** Generic success response from password operations */
export interface PasswordActionResponse {
  success: boolean;
  message: string;
}