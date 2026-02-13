import { Injectable, Injector } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { JwtHelperService } from '@auth0/angular-jwt';
import {
  BehaviorSubject,
  Observable,
  Subject,
  catchError,
  finalize,
  filter,
  firstValueFrom,
  map,
  of,
  switchMap,
  take,
  tap,
  throwError,
} from 'rxjs';

import { environment } from '@environments/environment';
import {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserRegisterRequest,
  OwnerRegisterRequest,
  GoogleOAuthCompletionRequest,
  GoogleAuthInitResponse,
  SupabaseGoogleCallbackResponse,
} from '@core/models/auth.model';
import { UserProfile } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';
import { SKIP_AUTH } from './auth.tokens';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly apiUrl = `${environment.baseApiUrl}/auth`;
  private readonly accessTokenSubject = new BehaviorSubject<string | null>(null);
  private readonly currentUserSubject = new BehaviorSubject<UserProfile | null>(null);
  private readonly refreshSubject = new BehaviorSubject<string | null>(null);
  private readonly sessionExpiredSubject = new Subject<void>();
  private isRefreshing = false;
  private tokenWatcherInterval: ReturnType<typeof setInterval> | null = null;
  private favoritesLoadedForSession = false;

  /**
   * SECURITY FIX: Track whether we've had a successful session.
   * Only emit sessionExpired$ if there was a previous active session.
   * This prevents showing "session expired" toast when a user visits
   * the site for the first time without any cookies.
   */
  private hasHadActiveSession = false;

  readonly currentUser$ = this.currentUserSubject.asObservable();
  readonly accessToken$ = this.accessTokenSubject.asObservable();
  readonly sessionExpired$ = this.sessionExpiredSubject.asObservable();

  constructor(
    private readonly http: HttpClient,
    private readonly jwtHelper: JwtHelperService,
    private readonly injector: Injector,
  ) {}

  /**
   * SECURITY HARDENING: Cookie-only authentication is now mandatory.
   * localStorage fallback has been completely removed.
   *
   * This method is kept for backward compatibility but always returns true.
   * @deprecated Will be removed in next major version
   */
  private shouldUseCookies(): boolean {
    // KILL SWITCH: Cookies are MANDATORY. No localStorage fallback.
    return true;
  }

  /**
   * Invoked during app bootstrap to silently restore a session using the refresh cookie.
   *
   * SECURITY HARDENING: This method now exclusively uses cookie-based authentication.
   * No localStorage is involved - the browser automatically sends HttpOnly cookies.
   */
  async initializeSession(): Promise<void> {
    console.log('🍪 [AUTH] Initializing session via refresh endpoint (cookie-only mode)');

    try {
      // Attempt to refresh the session using HttpOnly refresh cookie
      // If successful, user state will be hydrated from /api/users/me
      await firstValueFrom(this.refreshAccessToken().pipe(catchError(() => of(null))));
    } catch (error) {
      console.log('ℹ️ [AUTH] No active session found');
    }
  }

  /**
   * Verifies active session by querying backend /api/users/me endpoint.
   * This ensures frontend state never diverges from backend authorization reality.
   *
   * SECURITY HARDENING:
   * - Does NOT check local token (tokens are HttpOnly, JS cannot access)
   * - Relies entirely on backend session state via cookies
   * - Backend-verified roles (not client-side token claims)
   *
   * ✅ Handles 401 (expired token) → triggers refresh or logout
   * ✅ Handles 403 (insufficient permissions) → clears session
   * ✅ Syncs currentUser$ with backend state
   */
  async verifySession(): Promise<UserProfile | null> {
    // In cookie-only mode, we don't check local token
    // The browser will send HttpOnly cookies automatically
    const hasActiveUser = this.currentUserSubject.value !== null;

    if (!hasActiveUser) {
      console.log('🔒 No current user - attempting session verification');
    }

    try {
      const backendUser = await firstValueFrom(
        this.fetchBackendUserProfile().pipe(
          tap((verifiedUser) => {
            this.updateUserState(verifiedUser);
            console.log('✅ Session verified with backend:', verifiedUser);
          }),
          catchError((error: HttpErrorResponse) => {
            console.warn('⚠️ Session verification failed:', error.status);

            if (error.status === 401) {
              console.log('🔄 Token expired - attempting refresh');
              return this.refreshAccessToken().pipe(
                switchMap((newToken) => {
                  if (!newToken) {
                    return of(null);
                  }
                  return this.fetchBackendUserProfile();
                }),
                tap((refreshedUser) => {
                  if (refreshedUser) {
                    this.updateUserState(refreshedUser);
                  }
                }),
                catchError(() => {
                  console.log('❌ Session refresh failed - clearing session');
                  this.clearSession();
                  this.sessionExpiredSubject.next();
                  return of(null);
                }),
              );
            }

            if (error.status === 403) {
              console.log('🚫 Insufficient permissions - clearing session');
              this.clearSession();
              return of(null);
            }

            this.clearSession();
            return of(null);
          }),
        ),
      );

      return backendUser;
    } catch (error) {
      console.error('❌ Session verification error:', error);
      this.clearSession();
      return null;
    }
  }

  login(payload: LoginRequest): Observable<UserProfile> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/login`, payload, {
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => this.persistSession(response)),
        // FIX: Return the normalized user from internal state, not the raw response
        // This ensures 'roles' array is populated correctly for RedirectService
        map(() => this.currentUserSubject.value!),
      );
  }

  register(payload: RegisterRequest): Observable<UserProfile> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/register`, payload, {
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => this.persistSession(response)),
        map((response) => response.user as UserProfile),
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 2: Enhanced Registration Methods
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Register a new user with enhanced fields (phone, DOB, age confirmation).
   * Uses the new POST /api/auth/register/user endpoint.
   *
   * @param payload Enhanced user registration data
   * @returns Observable of the newly created user profile, or null if email confirmation required
   *
   * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 931-940
   */
  registerUser(payload: UserRegisterRequest): Observable<UserProfile | null> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/register/user`, payload, {
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => {
          // Only persist session if email confirmation is NOT required
          if (!response.emailConfirmationRequired && response.authenticated) {
            this.persistSession(response);
          }
        }),
        map((response) => {
          // Return null if email confirmation required - don't try to get user
          if (response.emailConfirmationRequired) {
            return null;
          }
          return this.currentUserSubject.value!;
        }),
      );
  }

  /**
   * Register a new owner/host with identity verification fields.
   * Uses the new POST /api/auth/register/owner endpoint.
   *
   * Owner registration includes:
   * - All user fields (phone, DOB, age confirmation)
   * - Owner type (INDIVIDUAL or LEGAL_ENTITY)
   * - Identity document (JMBG or PIB)
   * - Optional bank account (IBAN)
   * - Agreement checkboxes
   *
   * @param payload Owner registration data with identity documents
   * @returns Observable of the newly created owner profile
   *
   * @see REGISTRATION_IMPLEMENTATION_PLAN.md
   */
  registerOwner(payload: OwnerRegisterRequest): Observable<UserProfile> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/register/owner`, payload, {
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => this.persistSession(response)),
        map(() => this.currentUserSubject.value!),
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 3: Supabase Auth Integration
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Register a new user via Supabase Auth.
   * Uses the new POST /api/auth/supabase/register endpoint.
   *
   * @param payload Registration data (email, password, firstName, lastName, role)
   * @returns Observable of the newly created user profile
   *
   * SECURITY: Password is validated by Supabase Auth, not backend.
   * Tokens are delivered via HttpOnly cookies (not in JSON body).
   */
  supabaseRegister(payload: RegisterRequest): Observable<UserProfile | null> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse & { emailConfirmationPending?: boolean }>(
        `${this.apiUrl}/supabase/register`,
        payload,
        {
          context,
          withCredentials: true,
        },
      )
      .pipe(
        tap((response) => {
          // CRITICAL: Only persist session if email confirmation is NOT pending
          // When emailConfirmationPending is true, tokens are null (empty cookies would cause issues)
          if (!response.emailConfirmationPending) {
            this.persistSession(response);
          } else {
            console.log('📧 Email confirmation pending - session not persisted');
          }
        }),
        map((response) => {
          // Return null if email confirmation pending - don't try to access user
          if (response.emailConfirmationPending) {
            return null;
          }
          return this.currentUserSubject.value!;
        }),
        catchError((error: HttpErrorResponse) => {
          console.error('Supabase registration failed:', error);

          if (error.status === 422) {
            const message = error.error?.message || 'Validation failed';
            return throwError(() => new Error(message));
          }

          if (error.status === 400) {
            return throwError(() => new Error('Invalid registration data'));
          }

          return throwError(() => new Error('Registration failed'));
        }),
      );
  }

  /**
   * Login via Supabase Auth.
   * Uses the new POST /api/auth/supabase/login endpoint.
   *
   * @param payload Login credentials (email, password)
   * @returns Observable of the authenticated user profile
   *
   * SECURITY: Email/password validated against Supabase Auth.
   * Tokens are in HttpOnly cookies (not accessible to JS).
   */
  supabaseLogin(payload: LoginRequest): Observable<UserProfile> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/supabase/login`, payload, {
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => this.persistSession(response)),
        map(() => this.currentUserSubject.value!),
      );
  }

  /**
   * Logout via Supabase Auth.
   * Uses the new POST /api/auth/supabase/logout endpoint.
   * Revokes all Supabase tokens.
   *
   * @returns Observable of logout response
   *
   * SECURITY: Backend clears HttpOnly cookies.
   * Supabase tokens are revoked at Supabase level.
   */
  supabaseLogout(): Observable<void> {
    return this.http
      .post<any>(
        `${this.apiUrl}/supabase/logout`,
        {},
        {
          withCredentials: true,
        },
      )
      .pipe(
        tap(() => {
          console.log('User logged out via Supabase');
          this.clearSession();
          this.stopTokenWatcher();
        }),
        map(() => undefined),
        catchError((error: HttpErrorResponse) => {
          console.warn('Supabase logout error (clearing session anyway):', error);
          this.clearSession();
          return of(undefined);
        }),
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // GOOGLE OAUTH VIA SUPABASE
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Initiate Google OAuth2 authentication via Supabase.
   *
   * This method:
   * 1. Calls backend to get the authorization URL and state token
   * 2. Stores state token in sessionStorage for CSRF validation
   * 3. Returns the URL for the caller to redirect to
   *
   * @param role User role (USER or OWNER). Defaults to USER if not specified.
   * @returns Observable of GoogleAuthInitResponse containing the authorization URL
   *
   * SECURITY: State token is stored in sessionStorage for later validation.
   * The actual authentication happens via redirect to Google → Supabase → backend.
   */
  initiateSupabaseGoogleAuth(role: 'USER' | 'OWNER' = 'USER'): Observable<GoogleAuthInitResponse> {
    const context = new HttpContext().set(SKIP_AUTH, true);

    // Build callback URL based on current origin for proper redirect handling
    // This allows the app to work on different hosts (localhost, LAN IP, production)
    const callbackUrl = `${window.location.origin}/auth/supabase/google/callback`;

    return this.http
      .get<GoogleAuthInitResponse>(`${this.apiUrl}/supabase/google/authorize`, {
        params: {
          role: role,
          redirectUri: callbackUrl,
        },
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => {
          // Store state in sessionStorage for CSRF validation during callback
          sessionStorage.setItem('supabase_google_oauth_state', response.state);
          console.log('🔗 Google OAuth initiated via Supabase, redirecting...');
        }),
        catchError((error: HttpErrorResponse) => {
          console.error('Failed to initiate Google OAuth:', error);
          if (error.status === 400) {
            return throwError(() => new Error(error.error?.message || 'Invalid role specified'));
          }
          return throwError(() => new Error('Failed to initiate Google authentication'));
        }),
      );
  }

  /**
   * Redirect user to Google OAuth via Supabase.
   *
   * This is a convenience method that combines initiateSupabaseGoogleAuth
   * with the actual redirect. Use this when you want to trigger the OAuth
   * flow with a single method call.
   *
   * @param role User role (USER or OWNER). Defaults to USER.
   */
  loginWithSupabaseGoogle(role: 'USER' | 'OWNER' = 'USER'): void {
    this.initiateSupabaseGoogleAuth(role)
      .pipe(take(1))
      .subscribe({
        next: (response) => {
          // Redirect to Google OAuth consent screen
          window.location.href = response.authorizationUrl;
        },
        error: (error) => {
          console.error('Failed to start Google OAuth:', error);
          // Let the component handle the error
        },
      });
  }

  /**
   * Handle Google OAuth callback from Supabase.
   *
   * This method:
   * 1. Validates the state parameter against the stored value (CSRF protection)
   * 2. Exchanges the authorization code for tokens via backend
   * 3. Persists the session with the returned user data
   *
   * @param code Authorization code from the OAuth callback URL
   * @param state State parameter from the OAuth callback URL
   * @returns Observable of the authenticated user profile
   *
   * SECURITY: State validation is performed client-side for early failure,
   * but the actual CSRF protection happens server-side.
   */
  handleSupabaseGoogleCallback(code: string, state: string): Observable<UserProfile | null> {
    // Client-side state validation (server also validates)
    const storedState = sessionStorage.getItem('supabase_google_oauth_state');
    if (storedState && storedState !== state) {
      console.warn('⚠️ OAuth state mismatch - possible CSRF attempt');
      sessionStorage.removeItem('supabase_google_oauth_state');
      return throwError(() => new Error('Security validation failed. Please try again.'));
    }

    // Clear stored state
    sessionStorage.removeItem('supabase_google_oauth_state');

    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .get<SupabaseGoogleCallbackResponse>(`${this.apiUrl}/supabase/google/callback`, {
        params: { code, state },
        context,
        withCredentials: true,
      })
      .pipe(
        tap((response) => {
          if (response.success && response.user) {
            // Convert response to AuthResponse format for persistSession
            const authResponse: AuthResponse = {
              authenticated: true,
              user: response.user as any,
            };
            this.persistSession(authResponse);
            console.log('✅ Google OAuth callback successful:', response.registrationStatus);
          }
        }),
        map((response) => {
          if (!response.success || !response.user) {
            return null;
          }
          return this.currentUserSubject.value;
        }),
        catchError((error: HttpErrorResponse) => {
          console.error('Google OAuth callback failed:', error);

          if (error.status === 401) {
            return throwError(() => new Error('Authentication session expired. Please try again.'));
          }

          if (error.status === 409) {
            return throwError(
              () => new Error(error.error?.message || 'Account conflict detected.'),
            );
          }

          const message = error.error?.message || 'Google authentication failed. Please try again.';
          return throwError(() => new Error(message));
        }),
      );
  }

  /**
   * Handle Supabase implicit OAuth flow callback.
   *
   * This is used when Supabase returns tokens directly in the URL fragment
   * (implicit flow) instead of an authorization code (PKCE flow).
   *
   * @param accessToken The Supabase access token from URL fragment
   * @param refreshToken The Supabase refresh token (optional)
   * @param role The user role (USER or OWNER) from URL query params
   * @returns Observable of the authenticated user profile
   */
  handleSupabaseImplicitCallback(
    accessToken: string,
    refreshToken?: string,
    role?: string,
  ): Observable<UserProfile | null> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<SupabaseGoogleCallbackResponse>(
        `${this.apiUrl}/supabase/google/token-callback`,
        {
          accessToken,
          refreshToken,
          role: role || 'USER', // Send role instead of state
        },
        {
          context,
          withCredentials: true,
        },
      )
      .pipe(
        tap((response) => {
          if (response.success && response.user) {
            const authResponse: AuthResponse = {
              authenticated: true,
              user: response.user as any,
            };
            this.persistSession(authResponse);
            console.log('✅ Implicit OAuth callback successful:', response.registrationStatus);
          }
        }),
        map((response) => {
          if (!response.success || !response.user) {
            return null;
          }
          return this.currentUserSubject.value;
        }),
        catchError((error: HttpErrorResponse) => {
          console.error('Implicit OAuth callback failed:', error);

          if (error.status === 401) {
            return throwError(() => new Error('Invalid or expired token. Please try again.'));
          }

          if (error.status === 409) {
            return throwError(
              () => new Error(error.error?.message || 'Account conflict detected.'),
            );
          }

          const message = error.error?.message || 'Google authentication failed. Please try again.';
          return throwError(() => new Error(message));
        }),
      );
  }

  /**
   * Handle email confirmation callback.
   * Called when user clicks email verification link.
   * Uses the new POST /api/auth/supabase/confirm-email endpoint.
   *
   * @param accessToken Supabase access token from email link
   * @param refreshToken Supabase refresh token (if provided)
   * @returns Observable of the confirmed user profile
   */
  supabaseConfirmEmail(accessToken: string, refreshToken?: string): Observable<UserProfile> {
    const context = new HttpContext().set(SKIP_AUTH, true);
    return this.http
      .post<AuthResponse>(
        `${this.apiUrl}/supabase/confirm-email`,
        {
          accessToken,
          refreshToken,
        },
        {
          context,
          withCredentials: true,
        },
      )
      .pipe(
        tap((response) => this.persistSession(response)),
        map(() => this.currentUserSubject.value!),
        catchError((error: HttpErrorResponse) => {
          console.error('Email confirmation failed:', error);
          return throwError(() => new Error('Email confirmation failed'));
        }),
      );
  }

  /**
   * Complete OAuth profile for users with INCOMPLETE registration status.
   * Used when Google OAuth provides limited data (no phone, DOB).
   * Uses POST /api/auth/oauth-complete endpoint.
   *
   * Flow:
   * 1. User authenticates via Google OAuth
   * 2. Backend creates user with registrationStatus=INCOMPLETE
   * 3. auth-callback detects INCOMPLETE → redirects to /auth/complete-profile
   * 4. User fills in missing data → this method is called
   * 5. Profile updated → user redirected to dashboard
   *
   * @param payload Profile completion data (phone, DOB, optional owner fields)
   * @returns Observable of the updated user profile
   *
   * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 942-951
   */
  completeOAuthProfile(payload: GoogleOAuthCompletionRequest): Observable<UserProfile> {
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/oauth-complete`, payload, {
        withCredentials: true,
      })
      .pipe(
        tap((response) => this.persistSession(response)),
        map(() => this.currentUserSubject.value!),
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════

  logout(): void {
    this.clearSession();
    // Don't emit sessionExpired$ on manual logout (only on token expiry)

    const context = new HttpContext().set(SKIP_AUTH, true);
    this.http
      .post<void>(
        `${this.apiUrl}/logout`,
        {},
        {
          context,
          withCredentials: true,
        },
      )
      .pipe(catchError(() => of(void 0)))
      .subscribe();
  }

  hasAnyRole(roles: UserRole | UserRole[]): boolean {
    const desiredRoles = Array.isArray(roles) ? roles : [roles];
    const current = this.currentUserSubject.value;
    return current?.roles?.some((role) => desiredRoles.includes(role)) ?? false;
  }

  getAccessToken(): string | null {
    return this.accessTokenSubject.value;
  }

  /**
   * Get the current user synchronously
   */
  getCurrentUser(): UserProfile | null {
    return this.currentUserSubject.value;
  }

  /**
   * Check if user is authenticated.
   *
   * SECURITY HARDENING: In cookie-only mode, we cannot inspect the token
   * (it's HttpOnly). We rely on currentUser$ being populated.
   */
  isAuthenticated(): boolean {
    // Cookie-only mode: check if we have a current user
    // The actual token is HttpOnly and cannot be inspected by JS
    return this.currentUserSubject.value !== null;
  }

  /**
   * @deprecated In cookie-only mode, we cannot inspect token expiration.
   * The interceptor handles 401 responses and triggers refresh automatically.
   */
  willExpireSoon(_thresholdSeconds = 60): boolean {
    // Cookie-only mode: we cannot inspect HttpOnly token expiration
    // Rely on interceptor's 401 → refresh flow instead
    return false;
  }

  /**
   * Shares a single refresh call across concurrent subscribers to avoid duplicate HTTP traffic.
   */
  refreshAccessToken(): Observable<string | null> {
    if (this.isRefreshing) {
      return this.refreshSubject.pipe(
        filter((value): value is string => typeof value === 'string' && value.length > 0),
        take(1),
      );
    }

    this.isRefreshing = true;
    this.refreshSubject.next(null);

    const context = new HttpContext().set(SKIP_AUTH, true);

    console.log('🔄 [AUTH] Starting token refresh...');

    return this.http
      .post<AuthResponse>(
        `${this.apiUrl}/supabase/refresh`,
        {},
        {
          context,
          withCredentials: true,
        },
      )
      .pipe(
        tap((response) => {
          console.log('🔄 [AUTH] Refresh response received:', {
            authenticated: response.authenticated,
            hasUser: !!response.user,
          });

          // SECURITY HARDENING: Response no longer contains accessToken
          // Token is delivered via HttpOnly cookie by backend
          if (!response.authenticated) {
            throw new Error('Refresh failed: not authenticated');
          }

          // Persist user data (not token - that's in HttpOnly cookie)
          this.persistSession(response);

          // Mark that we've had an active session (for session expired detection)
          this.hasHadActiveSession = true;

          // Signal success (we don't have the token value, but session is valid)
          this.refreshSubject.next('session-refreshed');
          console.log('✅ [AUTH] Token refresh successful');
        }),
        map(() => 'session-refreshed'),
        catchError((error: HttpErrorResponse) => {
          this.refreshSubject.next(null);
          console.error('🔒 Token refresh failed:', {
            status: error.status,
            statusText: error.statusText,
            url: error.url,
            message: error.message,
            error: error.error,
            headers: error.headers?.keys(),
          });

          if (error.status === 401) {
            console.log('🔒 Refresh token expired or invalid - session ended');
            this.clearSession();
            // SECURITY FIX: Only show session expired if there was a previous session
            // This prevents false "session expired" toasts for first-time visitors
            if (this.hasHadActiveSession) {
              this.sessionExpiredSubject.next(); // Emit session expired event
            } else {
              console.log('ℹ️ No prior session - skipping session expired notification');
            }
            return of(null);
          }

          // For network errors or other issues, don't immediately clear session
          // The user might just have temporary connectivity issues
          if (error.status === 0 || error.status >= 500) {
            console.warn('🔒 Refresh failed due to network/server error - preserving session');
            return of(null); // Don't throw, just return null to indicate failure
          }

          this.clearSession();
          // SECURITY FIX: Only show session expired if there was a previous session
          if (this.hasHadActiveSession) {
            this.sessionExpiredSubject.next(); // Emit session expired event on other errors
          }
          return throwError(() => error);
        }),
        finalize(() => {
          this.isRefreshing = false;
        }),
      );
  }

  refreshUserProfile(): Observable<UserProfile> {
    return this.http
      .get<UserProfile>(`${environment.baseApiUrl}/users/profile`, {
        withCredentials: true,
      })
      .pipe(
        map((profile) => ({
          ...profile,
          id: String(profile.id),
          roles: profile.roles ?? [],
        })),
        tap((profile) => this.currentUserSubject.next(profile)),
      );
  }

  /**
   * Start periodic session health watcher.
   *
   * SECURITY HARDENING: In cookie-only mode, we cannot inspect tokens.
   * Instead, we periodically verify the session is still active.
   */
  startTokenWatcher(intervalMs = 60000): void {
    // Clear any existing watcher
    if (this.tokenWatcherInterval) {
      clearInterval(this.tokenWatcherInterval);
    }

    // Cookie-only mode: periodically verify session health
    this.tokenWatcherInterval = setInterval(() => {
      const currentUser = this.currentUserSubject.value;

      // If we have a user, periodically verify session is still valid
      if (currentUser) {
        // Silent verification - don't clear session on network errors
        this.fetchBackendUserProfile()
          .pipe(take(1))
          .subscribe({
            next: (profile) => {
              this.currentUserSubject.next(profile);
            },
            error: (error: HttpErrorResponse) => {
              if (error.status === 401) {
                console.log('⏰ Session expired - clearing');
                this.clearSession();
                this.sessionExpiredSubject.next();
              }
              // Ignore other errors (network issues, etc.)
            },
          });
      }
    }, intervalMs);

    console.log(`✅ Session watcher started (checking every ${intervalMs}ms)`);
  }

  /**
   * Stop the token expiration watcher.
   * Called during logout or when no longer needed.
   */
  stopTokenWatcher(): void {
    if (this.tokenWatcherInterval) {
      clearInterval(this.tokenWatcherInterval);
      this.tokenWatcherInterval = null;
      console.log('🛑 Token watcher stopped');
    }
  }

  /**
   * Update the current user's avatar URL in memory.
   * Called after successful profile picture upload/delete.
   *
   * @param avatarUrl The new avatar URL (or null to remove)
   */
  updateCurrentUserAvatar(avatarUrl: string | null): void {
    const currentUser = this.currentUserSubject.value;

    if (!currentUser) {
      console.warn('⚠️ Cannot update avatar: no current user');
      return;
    }

    // Create updated user with new avatar URL
    const updatedUser: UserProfile = {
      ...currentUser,
      avatarUrl: avatarUrl ?? undefined,
    };

    // Update observable (no localStorage in cookie-only mode)
    this.currentUserSubject.next(updatedUser);

    console.log('✅ Avatar URL updated in user state:', avatarUrl);
  }

  /**
   * Clear all session state.
   *
   * SECURITY HARDENING: No localStorage to clear.
   * HttpOnly cookies are managed by backend via /api/auth/logout.
   */
  clearSession(): void {
    this.accessTokenSubject.next(null);
    this.currentUserSubject.next(null);

    this.favoritesLoadedForSession = false;

    // Stop token watcher when session is cleared
    this.stopTokenWatcher();

    // Clear favorited cars on logout
    import('@core/services/favorite.service').then(({ FavoriteService }) => {
      const favoriteService = this.injector.get(FavoriteService);
      favoriteService.clearFavorites();
    });
  }

  /**
   * Persist session from auth response.
   *
   * SECURITY HARDENING: Tokens are NOT stored (they're in HttpOnly cookies).
   * Only user profile data is persisted in memory.
   */
  private persistSession(response: AuthResponse): void {
    const { user } = response;

    if (!user) {
      console.log('ℹ️ Response missing user payload — hydrating via /api/users/me');
      this.hydrateUserFromBackend();
      return;
    }

    // Extract role from user object (no token decoding - token is HttpOnly)
    const singleRole = (user as any)?.role;
    const effectiveRoles = singleRole ? [singleRole] : ((user as any).roles ?? []);

    const completeUser: UserProfile = {
      ...user,
      id: String(user.id),
      roles: effectiveRoles,
      // CRITICAL: Preserve registrationStatus for profile completion flow
      registrationStatus: (user as any)?.registrationStatus,
      ownerType: (user as any)?.ownerType,
    } as UserProfile;

    this.updateUserState(completeUser);

    // Mark that we've had an active session (for session expired detection)
    this.hasHadActiveSession = true;
  }

  private hydrateUserFromBackend(): void {
    this.fetchBackendUserProfile()
      .pipe(take(1))
      .subscribe({
        next: (profile) => {
          this.updateUserState(profile);
          this.hasHadActiveSession = true;
          console.log('✅ Session hydrated via /api/users/me');
        },
        error: (error: HttpErrorResponse) => {
          console.error('❌ Failed to hydrate user after refresh:', error);
          if (error.status === 401) {
            this.clearSession();
            // SECURITY FIX: Only show session expired if there was a previous session
            if (this.hasHadActiveSession) {
              this.sessionExpiredSubject.next();
            }
          }
        },
      });
  }

  private fetchBackendUserProfile(): Observable<UserProfile> {
    return this.http
      .get<any>(`${environment.baseApiUrl}/users/me`, {
        withCredentials: true,
      })
      .pipe(map((response) => this.mapBackendUserResponse(response)));
  }

  private mapBackendUserResponse(response: any): UserProfile {
    if (!response?.authenticated) {
      throw new Error('Backend reports user not authenticated');
    }

    const normalizedRoles: UserRole[] = Array.isArray(response.roles)
      ? (response.roles as UserRole[])
      : typeof response.roles === 'string'
        ? (response.roles.split(',') as UserRole[])
        : [];

    // Map to UserProfile with registrationStatus for profile completion flow
    const profile: UserProfile & { registrationStatus?: string; ownerType?: string } = {
      id: String(response.id),
      email: response.email,
      firstName: response.firstName,
      lastName: response.lastName,
      phone: response.phone || undefined,
      age: response.age || undefined,
      avatarUrl: response.avatarUrl || undefined,
      roles: normalizedRoles,
      // CRITICAL: Include registrationStatus for profile completion guard
      registrationStatus: response.registrationStatus,
      ownerType: response.ownerType,
    };

    return profile as UserProfile;
  }

  /**
   * Update user state in memory.
   *
   * SECURITY HARDENING: No localStorage storage.
   */
  private updateUserState(user: UserProfile): void {
    const normalizedUser: UserProfile = {
      ...user,
      id: String(user.id),
      roles: Array.isArray(user.roles) ? user.roles : [],
      // Preserve critical fields for profile completion flow
      registrationStatus: (user as any).registrationStatus,
      ownerType: (user as any).ownerType,
    };

    // Update observable only (no localStorage in cookie-only mode)
    this.currentUserSubject.next(normalizedUser);
    this.loadFavoritesForSession();
  }

  private loadFavoritesForSession(): void {
    if (this.favoritesLoadedForSession) {
      return;
    }

    this.favoritesLoadedForSession = true;
    import('@core/services/favorite.service').then(({ FavoriteService }) => {
      const favoriteService = this.injector.get(FavoriteService);
      favoriteService
        .loadFavoritedCarIds()
        .pipe(take(1))
        .subscribe({
          error: (err) => console.error('Failed to load favorited cars:', err),
        });
    });
  }

  private extractRoles(payload: Record<string, unknown>): UserRole[] {
    const roles = payload['roles'];
    if (Array.isArray(roles)) {
      return roles.filter((role): role is UserRole => typeof role === 'string');
    }

    if (typeof roles === 'string') {
      return roles.split(',') as UserRole[];
    }

    return [];
  }
}
