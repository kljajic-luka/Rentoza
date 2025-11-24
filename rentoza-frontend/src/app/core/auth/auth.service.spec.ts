import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { JwtHelperService } from '@auth0/angular-jwt';
import { of } from 'rxjs';

import { AuthService } from './auth.service';
import { UserProfile } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';

describe('AuthService (cookie-mode session)', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let jwtHelperMock: JwtHelperService;

  beforeEach(() => {
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
      providers: [
        AuthService,
        { provide: JwtHelperService, useValue: jwtHelperMock },
      ],
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    spyOn<any>(service, 'loadFavoritesForSession').and.stub();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
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
});
