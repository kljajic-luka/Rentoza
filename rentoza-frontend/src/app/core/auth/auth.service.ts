import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { JwtHelperService } from '@auth0/angular-jwt';
import { BehaviorSubject, Observable, map, tap } from 'rxjs';

import { environment } from '@environments/environment';
import { AuthResponse, LoginRequest, RegisterRequest } from '@core/models/auth.model';
import { UserProfile } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';

const ACCESS_TOKEN_KEY = 'rentoza.accessToken';
const REFRESH_TOKEN_KEY = 'rentoza.refreshToken';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly apiUrl = `${environment.baseApiUrl}/auth`;
  private readonly currentUserSubject = new BehaviorSubject<UserProfile | null>(null);

  readonly currentUser$ = this.currentUserSubject.asObservable();

  constructor(
    private readonly http: HttpClient,
    private readonly jwtHelper: JwtHelperService
  ) {
    this.restoreSession();
  }

  login(payload: LoginRequest): Observable<UserProfile> {
    return this.http.post<AuthResponse>(`${this.apiUrl}/login`, payload).pipe(
      tap((response) => this.persistSession(response)),
      map((response) => response.user as UserProfile)
    );
  }

  register(payload: RegisterRequest): Observable<UserProfile> {
    return this.http.post<AuthResponse>(`${this.apiUrl}/register`, payload).pipe(
      tap((response) => this.persistSession(response)),
      map((response) => response.user as UserProfile)
    );
  }

  logout(): void {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    this.currentUserSubject.next(null);
  }

  hasAnyRole(roles: UserRole | UserRole[]): boolean {
    const desiredRoles = Array.isArray(roles) ? roles : [roles];
    const current = this.currentUserSubject.value;
    return current?.roles?.some((role) => desiredRoles.includes(role)) ?? false;
  }

  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_KEY);
  }

  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  }

  isAuthenticated(): boolean {
    const token = this.getAccessToken();
    return Boolean(token && !this.jwtHelper.isTokenExpired(token));
  }

  refreshUserProfile(): Observable<UserProfile> {
    return this.http
      .get<UserProfile>(`${environment.baseApiUrl}/users/profile`)
      .pipe(tap((profile) => this.currentUserSubject.next(profile)));
  }

  private restoreSession(): void {
    const token = this.getAccessToken();
    if (!token) {
      return;
    }

    if (this.jwtHelper.isTokenExpired(token)) {
      this.logout();
      return;
    }

    const decodedPayload = this.jwtHelper.decodeToken(token) as Record<string, unknown>;
    const user = this.extractUserFromPayload(decodedPayload);
    if (user) {
      this.currentUserSubject.next(user);
    }
  }

  private persistSession(response: AuthResponse): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken);
    if (response.refreshToken) {
      localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken);
    }

    const enrichedUser = response.user as UserProfile;
    if (!enrichedUser.roles?.length) {
      const decodedPayload = this.jwtHelper.decodeToken(response.accessToken) as Record<string, unknown>;
      enrichedUser.roles = this.extractRoles(decodedPayload);
    }

    this.currentUserSubject.next(enrichedUser);
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
      roles
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
