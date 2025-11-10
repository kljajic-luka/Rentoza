import { Injectable, Injector } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { JwtHelperService } from '@auth0/angular-jwt';
import {
  BehaviorSubject,
  Observable,
  catchError,
  finalize,
  filter,
  firstValueFrom,
  map,
  of,
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
  private isRefreshing = false;

  readonly currentUser$ = this.currentUserSubject.asObservable();
  readonly accessToken$ = this.accessTokenSubject.asObservable();

  constructor(
    private readonly http: HttpClient,
    private readonly jwtHelper: JwtHelperService,
    private readonly injector: Injector
  ) {}

  /**
   * Invoked during app bootstrap to silently restore a session using the refresh cookie.
   * Also restores user from localStorage for immediate availability.
   */
  initializeSession(): Promise<void> {
    // ✅ First, try to restore user from localStorage (immediate availability)
    const storedToken = localStorage.getItem('access_token');
    const storedUserJson = localStorage.getItem('current_user');

    if (storedToken && storedUserJson) {
      try {
        const user: UserProfile = JSON.parse(storedUserJson);
        this.accessTokenSubject.next(storedToken);
        this.currentUserSubject.next(user);
        console.log('✅ Restored full user from localStorage:', user);
      } catch (error) {
        console.warn('⚠️ Failed to parse stored user, clearing storage');
        localStorage.removeItem('access_token');
        localStorage.removeItem('current_user');
      }
    }

    // Then attempt to refresh the access token via refresh cookie
    return firstValueFrom(this.refreshAccessToken().pipe(catchError(() => of(null)))).then(
      () => void 0
    );
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
   * Set access token directly (used for OAuth2 callback)
   * This allows Google OAuth2 to inject the JWT token received from backend
   */
  setAccessToken(token: string): void {
    this.accessTokenSubject.next(token);
  }

  isAuthenticated(): boolean {
    const token = this.accessTokenSubject.value;
    return Boolean(token && !this.jwtHelper.isTokenExpired(token));
  }

  willExpireSoon(thresholdSeconds = 60): boolean {
    const token = this.accessTokenSubject.value;
    if (!token) {
      return false;
    }

    const expirationDate = this.jwtHelper.getTokenExpirationDate(token);
    if (!expirationDate) {
      return false;
    }

    return expirationDate.getTime() - Date.now() <= thresholdSeconds * 1000;
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
          if (!response.accessToken) {
            throw new Error('Refresh response did not include an access token.');
          }

          this.persistSession(response);
          this.refreshSubject.next(response.accessToken);
        }),
        map((response) => response.accessToken),
        catchError((error: HttpErrorResponse) => {
          this.refreshSubject.next(null);
          if (error.status === 401) {
            this.clearSession();
            return of(null);
          }
          this.clearSession();
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

  clearSession(): void {
    this.accessTokenSubject.next(null);
    this.currentUserSubject.next(null);

    // Clear localStorage
    localStorage.removeItem('access_token');
    localStorage.removeItem('current_user');

    // Clear favorited cars on logout
    import('@core/services/favorite.service').then(({ FavoriteService }) => {
      const favoriteService = this.injector.get(FavoriteService);
      favoriteService.clearFavorites();
    });
  }

  private persistSession(response: AuthResponse): void {
    const { accessToken, user } = response;

    if (!accessToken || !user) {
      console.warn('⚠️ Invalid session data (missing token or user), skipping persist.');
      return;
    }

    // Decode token to extract roles
    const decodedPayload = this.jwtHelper.decodeToken(accessToken) as Record<string, unknown>;
    const rolesFromToken = this.extractRoles(decodedPayload);
    const singleRole = (user as any)?.role ?? decodedPayload['role'];
    const effectiveRoles = rolesFromToken.length ? rolesFromToken : singleRole ? [singleRole] : [];

    // ✅ Build complete user profile with all fields
    const completeUser: UserProfile = {
      ...user,
      id: String(user.id), // Ensure id is string
      roles: effectiveRoles.length ? effectiveRoles : user.roles ?? [],
    };

    // ✅ Store token and complete user in localStorage for session persistence
    localStorage.setItem('access_token', accessToken);
    localStorage.setItem('current_user', JSON.stringify(completeUser));

    // ✅ Update observables with complete data
    this.accessTokenSubject.next(accessToken);
    this.currentUserSubject.next(completeUser);

    console.log('✅ Persisted full user session:', completeUser);

    // Load user's favorited cars after successful login/register
    import('@core/services/favorite.service').then(({ FavoriteService }) => {
      const favoriteService = this.injector.get(FavoriteService);
      favoriteService.loadFavoritedCarIds().subscribe({
        error: (err) => console.error('Failed to load favorited cars:', err),
      });
    });
  }

  private extractUserFromPayload(payload: Record<string, unknown>): UserProfile | null {
    const roles = this.extractRoles(payload);
    const subject = payload['sub'];
    const email = payload['email'];
    if (!subject || typeof subject !== 'string') {
      return null;
    }

    return {
      id: subject,
      email: typeof email === 'string' ? email : '',
      firstName: (payload['firstName'] as string) ?? '',
      lastName: (payload['lastName'] as string) ?? '',
      age: typeof payload['age'] === 'number' ? payload['age'] : undefined,
      phone: (payload['phone'] as string) ?? undefined,
      avatarUrl: (payload['avatarUrl'] as string) ?? undefined,
      roles,
    };
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
