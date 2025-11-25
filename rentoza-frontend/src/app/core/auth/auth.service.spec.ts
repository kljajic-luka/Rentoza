import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { JwtHelperService } from '@auth0/angular-jwt';
import { of } from 'rxjs';

import { AuthService } from './auth.service';
import { UserProfile } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';
import { environment } from '@environments/environment';

describe('AuthService (cookie-mode session)', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let jwtHelperMock: JwtHelperService;
  let originalUseCookies: boolean | undefined;

  beforeEach(() => {
    // Save original value to restore later
    originalUseCookies = environment.auth?.useCookies;

    // Enable cookie mode for these tests
    if (!environment.auth) {
      (environment as any).auth = {};
    }
    environment.auth.useCookies = true;

    jwtHelperMock = {
      decodeToken: jasmine.createSpy('decodeToken').and.returnValue({
        roles: ['ROLE_USER'],
        sub: '1',
        email: 'cookie@example.com',
      }),
      getTokenExpirationDate: jasmine
        .createSpy('getTokenExpirationDate')
        .and.returnValue(new Date(Date.now() + 60000)),
      isTokenExpired: jasmine.createSpy('isTokenExpired').and.returnValue(false),
    } as unknown as JwtHelperService;

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuthService, { provide: JwtHelperService, useValue: jwtHelperMock }],
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    spyOn<any>(service, 'loadFavoritesForSession').and.stub();

    // Clear localStorage before each test
    localStorage.clear();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();

    // Restore original value
    if (environment.auth) {
      environment.auth.useCookies = originalUseCookies ?? false;
    }
  });

  it('bootstraps session via refresh when cookies are enabled', async () => {
    const refreshSpy = spyOn(service, 'refreshAccessToken').and.returnValue(of('token'));

    await service.initializeSession();

    expect(refreshSpy).toHaveBeenCalled();
  });

  it('hydrates access token and user after refresh response', () => {
    let emitted: string | null = null;

    service.refreshAccessToken().subscribe((token) => {
      emitted = token;
    });

    const request = httpMock.expectOne('/api/auth/refresh');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();

    request.flush({
      accessToken: 'signed.jwt.token',
      user: {
        id: '7',
        email: 'cookie@example.com',
        firstName: 'Cookie',
        lastName: 'User',
        roles: ['USER'] as UserRole[],
      } satisfies Partial<UserProfile>,
    });

    expect(emitted).not.toBeNull();
    const token = emitted!;
    expect(token).toBe('signed.jwt.token');
    expect(service.getAccessToken()).toBe('signed.jwt.token');
    expect(service.getCurrentUser()?.email).toBe('cookie@example.com');
  });

  /**
   * CRITICAL SECURITY TEST: Verify localStorage is NOT used in cookie mode
   *
   * This test ensures that when useCookies=true, sensitive tokens and user data
   * are never written to localStorage (which is vulnerable to XSS attacks).
   * Instead, authentication state is managed via:
   * - HttpOnly cookies (server-managed, inaccessible to JavaScript)
   * - In-memory BehaviorSubject observables
   */
  it('should NOT write to localStorage when in cookie mode', () => {
    // Spy on localStorage.setItem to verify it's never called with auth data
    const setItemSpy = spyOn(localStorage, 'setItem').and.callThrough();

    // Trigger a login flow (simulated via refresh with user data)
    service.refreshAccessToken().subscribe();

    const request = httpMock.expectOne('/api/auth/refresh');
    request.flush({
      accessToken: 'test.jwt.token',
      user: {
        id: '123',
        email: 'secure@example.com',
        firstName: 'Secure',
        lastName: 'User',
        roles: ['USER'] as UserRole[],
      },
    });

    // Verify localStorage.setItem was NOT called with auth-related keys
    const authRelatedCalls = setItemSpy.calls
      .all()
      .filter((call) => call.args[0] === 'access_token' || call.args[0] === 'current_user');

    expect(authRelatedCalls.length).toBe(
      0,
      'localStorage.setItem should NOT be called for access_token or current_user in cookie mode'
    );

    // Also verify the values are not actually in localStorage
    expect(localStorage.getItem('access_token')).toBeNull(
      'access_token should not be in localStorage'
    );
    expect(localStorage.getItem('current_user')).toBeNull(
      'current_user should not be in localStorage'
    );

    // But verify in-memory state IS updated
    expect(service.getAccessToken()).toBe(
      'test.jwt.token',
      'Token should still be available in memory'
    );
    expect(service.getCurrentUser()?.email).toBe(
      'secure@example.com',
      'User should still be available in memory'
    );
  });

  /**
   * Verify that clearSession() still clears localStorage (defense in depth)
   * even if somehow tokens were written there by legacy code.
   */
  it('should clear localStorage on logout regardless of cookie mode', () => {
    // Pre-populate localStorage (simulating legacy data or manual tampering)
    localStorage.setItem('access_token', 'old.token');
    localStorage.setItem('current_user', '{"id":"1","email":"old@example.com"}');

    // Clear session
    service.clearSession();

    // Verify localStorage is cleared
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('current_user')).toBeNull();
  });
});
