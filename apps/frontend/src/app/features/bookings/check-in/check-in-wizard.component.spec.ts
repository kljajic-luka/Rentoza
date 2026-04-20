import { BehaviorSubject, of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';

import { CheckInWizardComponent } from './check-in-wizard.component';
import { BookingService } from '@core/services/booking.service';
import { CheckInService } from '@core/services/check-in.service';
import { GeolocationService } from '@core/services/geolocation.service';
import { OfflineQueueService } from '@core/services/offline-queue.service';
import { AuthService } from '@core/auth/auth.service';
import { RenterVerificationService } from '@core/services/renter-verification.service';

describe('CheckInWizardComponent', () => {
  let bookingService: jasmine.SpyObj<BookingService>;

  beforeEach(() => {
    bookingService = jasmine.createSpyObj<BookingService>('BookingService', ['resolveCheckInAgreementGate']);
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({
        state: 'allowed',
        legacyBooking: false,
      } as any),
    );

    TestBed.configureTestingModule({
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: '42' }) } },
        },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj('MatSnackBar', ['open']) },
        { provide: MatDialog, useValue: jasmine.createSpyObj('MatDialog', ['open']) },
        {
          provide: AuthService,
          useValue: {
            hasAnyRole: () => false,
            currentUser$: new BehaviorSubject(null),
          },
        },
        {
          provide: RenterVerificationService,
          useValue: { checkBookingEligibility: () => of({ eligible: true }) },
        },
        { provide: BookingService, useValue: bookingService },
        {
          provide: CheckInService,
          useValue: {
            loadStatus: () => of({ host: true, guest: false, status: 'CHECK_IN_OPEN' }),
            stopPolling: () => undefined,
            renderDecision: () => 'HOST_EDIT',
            error: () => null,
            currentStatus: () => null,
          },
        },
        {
          provide: GeolocationService,
          useValue: {
            getCurrentPosition: () => Promise.resolve(null),
            startWatching: () => undefined,
            stopWatching: () => undefined,
          },
        },
        {
          provide: OfflineQueueService,
          useValue: {
            isOnline: () => true,
            queueLength: () => 0,
            isProcessing: () => false,
          },
        },
      ],
    });
  });

  it('does not hard-block when backend summary says check-in can proceed', () => {
    const component = TestBed.runInInjectionContext(() => new CheckInWizardComponent());
    component.ngOnInit();

    expect(component.agreementGateState()).toBe('ready');
  });

  it('blocks early with retryable UX when agreement gate lookup is unavailable', () => {
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'retry', legacyBooking: false } as any),
    );

    const component = TestBed.runInInjectionContext(() => new CheckInWizardComponent());
    component.ngOnInit();

    expect(component.agreementGateState()).toBe('blocked');
    expect(component.agreementGateMessage()).toContain('ne možemo da proverimo status ugovora');
  });
});