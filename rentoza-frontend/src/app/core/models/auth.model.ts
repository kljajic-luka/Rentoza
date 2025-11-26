import { User, UserProfile } from './user.model';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phone?: string;
  role?: string;
}

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
}
