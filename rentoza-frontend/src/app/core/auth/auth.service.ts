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
   */
  initializeSession(): Promise<void> {
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

    // Clear favorited cars on logout
    import('@core/services/favorite.service').then(({ FavoriteService }) => {
      const favoriteService = this.injector.get(FavoriteService);
      favoriteService.clearFavorites();
    });
  }

  private persistSession(response: AuthResponse): void {
    this.accessTokenSubject.next(response.accessToken ?? null);

    if (!response.accessToken) {
      return;
    }

    const decodedPayload = this.jwtHelper.decodeToken(response.accessToken) as Record<
      string,
      unknown
    >;
    const incomingUser = (response.user as UserProfile | undefined) ?? undefined;
    const currentUser = this.currentUserSubject.value;
    const rolesFromToken = this.extractRoles(decodedPayload);
    const singleRole = (response.user as any)?.role ?? decodedPayload['role'];
    const effectiveRoles = rolesFromToken.length ? rolesFromToken : singleRole ? [singleRole] : [];

    const mergedUser: UserProfile | null =
      incomingUser ??
      (currentUser
        ? {
            ...currentUser,
            roles: effectiveRoles.length ? effectiveRoles : currentUser.roles,
          }
        : this.extractUserFromPayload(decodedPayload));

    if (mergedUser) {
      if (!mergedUser.roles?.length && effectiveRoles.length) {
        mergedUser.roles = effectiveRoles;
      }
      this.currentUserSubject.next({ ...mergedUser });

      // Load user's favorited cars after successful login/register
      // Use dynamic import to avoid circular dependency
      import('@core/services/favorite.service').then(({ FavoriteService }) => {
        const favoriteService = this.injector.get(FavoriteService);
        favoriteService.loadFavoritedCarIds().subscribe({
          error: (err) => console.error('Failed to load favorited cars:', err)
        });
      });
    }
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
