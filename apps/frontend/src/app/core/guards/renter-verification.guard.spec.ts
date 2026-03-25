import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import {
  DriverLicenseStatus,
  RenterVerificationProfile,
} from '@core/models/renter-verification.model';
import { RenterVerificationService } from '@core/services/renter-verification.service';
import { RenterVerificationGuard } from './renter-verification.guard';

describe('RenterVerificationGuard', () => {
  let guard: RenterVerificationGuard;
  let currentUser$: BehaviorSubject<unknown | null>;
  let status$: BehaviorSubject<RenterVerificationProfile | null>;
  let verificationService: jasmine.SpyObj<RenterVerificationService> & {
    status$: BehaviorSubject<RenterVerificationProfile | null>;
  };
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    currentUser$ = new BehaviorSubject<unknown | null>({ id: 42 });
    status$ = new BehaviorSubject<RenterVerificationProfile | null>(null);

    verificationService = jasmine.createSpyObj<RenterVerificationService>(
      'RenterVerificationService',
      ['loadStatus'],
      { status$ },
    ) as jasmine.SpyObj<RenterVerificationService> & {
      status$: BehaviorSubject<RenterVerificationProfile | null>;
    };

    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.callFake(
      (commands: readonly unknown[], extras?: unknown) =>
        ({ commands, extras } as unknown as UrlTree),
    );

    TestBed.configureTestingModule({
      providers: [
        RenterVerificationGuard,
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: { currentUser$ } },
        { provide: RenterVerificationService, useValue: verificationService },
      ],
    });

    guard = TestBed.inject(RenterVerificationGuard);
  });

  it('allows navigation when the renter is approved', fakeAsync(() => {
    let result: boolean | UrlTree | undefined;

    guard
      .canActivate({} as any, { url: '/cars/42/book' } as any)
      .subscribe((value) => (result = value));

    status$.next(createProfile('APPROVED'));
    tick();

    expect(result).toBeTrue();
    expect(verificationService.loadStatus).toHaveBeenCalled();
  }));

  it('redirects to verification when status never resolves', fakeAsync(() => {
    let result: boolean | UrlTree | undefined;

    guard
      .canActivate({} as any, { url: '/cars/42/book' } as any)
      .subscribe((value) => (result = value));

    tick(5001);

    expect(result).toEqual(
      jasmine.objectContaining({
        commands: ['/verify-license'],
        extras: jasmine.objectContaining({
          queryParams: jasmine.objectContaining({
            returnUrl: '/cars/42/book',
            reason: 'verification_required',
          }),
        }),
      }),
    );
  }));
});

function createProfile(status: DriverLicenseStatus): RenterVerificationProfile {
  return {
    userId: 42,
    fullName: 'Test User',
    email: 'test@example.com',
    status,
    statusDisplay: status,
    canBook: status === 'APPROVED',
    bookingBlockedReason: status === 'APPROVED' ? null : 'blocked',
    maskedLicenseNumber: null,
    licenseExpiryDate: null,
    daysUntilExpiry: null,
    expiryWarning: false,
    licenseCountry: null,
    licenseCategories: null,
    licenseTenureMonths: null,
    submittedAt: null,
    verifiedAt: null,
    verifiedByName: null,
    riskLevel: null,
    riskLevelDisplay: null,
    documents: [],
    requiredDocumentsComplete: true,
    missingDocuments: [],
    canSubmit: false,
    estimatedWaitTime: null,
    rejectionReason: null,
    nextSteps: null,
  };
}
