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
import { AuthResponse, LoginRequest, RegisterRequest } from '@core/models/auth.model';
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

  readonly currentUser$ = this.currentUserSubject.asObservable();
  readonly accessToken$ = this.accessTokenSubject.asObservable();
  readonly sessionExpired$ = this.sessionExpiredSubject.asObservable();

  constructor(
    private readonly http: HttpClient,
    private readonly jwtHelper: JwtHelperService,
    private readonly injector: Injector
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
                })
              );
            }

            if (error.status === 403) {
              console.log('🚫 Insufficient permissions - clearing session');
              this.clearSession();
              return of(null);
            }

            this.clearSession();
            return of(null);
          })
        )
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
        map((response) => response.user as UserProfile)
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
        map((response) => response.user as UserProfile)
      );
  }

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
        }
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
   * @deprecated OAuth2 now uses cookie-only flow. This method is a no-op.
   * The access token is set via HttpOnly cookie by the backend.
   * Frontend should call verifySession() after OAuth2 redirect.
   */
  setAccessToken(_token: string): void {
    console.warn('⚠️ setAccessToken() is deprecated. Tokens are now cookie-only.');
    // No-op: tokens are managed by HttpOnly cookies
    // Hydrate user from backend instead
    this.hydrateUserFromBackend();
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
        take(1)
      );
    }

    this.isRefreshing = true;
    this.refreshSubject.next(null);

    const context = new HttpContext().set(SKIP_AUTH, true);

    console.log('🔄 [AUTH] Starting token refresh...');

    return this.http
      .post<AuthResponse>(
        `${this.apiUrl}/refresh`,
        {},
        {
          context,
          withCredentials: true,
        }
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
            this.sessionExpiredSubject.next(); // Emit session expired event
            return of(null);
          }

          // For network errors or other issues, don't immediately clear session
          // The user might just have temporary connectivity issues
          if (error.status === 0 || error.status >= 500) {
            console.warn('🔒 Refresh failed due to network/server error - preserving session');
            return of(null); // Don't throw, just return null to indicate failure
          }

          this.clearSession();
          this.sessionExpiredSubject.next(); // Emit session expired event on other errors
          return throwError(() => error);
        }),
        finalize(() => {
          this.isRefreshing = false;
        })
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
        tap((profile) => this.currentUserSubject.next(profile))
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
    const effectiveRoles = singleRole ? [singleRole] : (user as any).roles ?? [];

    const completeUser: UserProfile = {
      ...user,
      id: String(user.id),
      roles: effectiveRoles,
    };

    this.updateUserState(completeUser);
  }

  private hydrateUserFromBackend(): void {
    this.fetchBackendUserProfile()
      .pipe(take(1))
      .subscribe({
        next: (profile) => {
          this.updateUserState(profile);
          console.log('✅ Session hydrated via /api/users/me');
        },
        error: (error: HttpErrorResponse) => {
          console.error('❌ Failed to hydrate user after refresh:', error);
          if (error.status === 401) {
            this.clearSession();
            this.sessionExpiredSubject.next();
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

    return {
      id: String(response.id),
      email: response.email,
      firstName: response.firstName,
      lastName: response.lastName,
      phone: response.phone || undefined,
      age: response.age || undefined,
      avatarUrl: response.avatarUrl || undefined,
      roles: normalizedRoles,
    };
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
      roles: Array.isArray(user.roles) ? (user.roles as UserRole[]) : [],
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

  /**
   * @deprecated No longer used - tokens are HttpOnly cookies.
   * Kept for backward compatibility during migration.
   */
  private persistAccessToken(_token: string): void {
    // No-op: tokens are managed by HttpOnly cookies
    console.debug('persistAccessToken() is deprecated - tokens are HttpOnly');
  }

  /**
   * @deprecated No longer used - cannot decode HttpOnly tokens.
   */
  private extractUserFromPayload(_payload: Record<string, unknown>): UserProfile | null {
    console.debug('extractUserFromPayload() is deprecated - tokens are HttpOnly');
    return null;
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
