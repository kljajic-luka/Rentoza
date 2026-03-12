import { firstValueFrom, of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { BookingService } from '@core/services/booking.service';
import { checkInAgreementGuard } from './check-in-agreement.guard';

describe('checkInAgreementGuard', () => {
  let bookingService: jasmine.SpyObj<BookingService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    bookingService = jasmine.createSpyObj<BookingService>('BookingService', ['resolveCheckInAgreementGate']);
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);

    TestBed.configureTestingModule({
      providers: [
        { provide: BookingService, useValue: bookingService },
        { provide: Router, useValue: router },
      ],
    });
  });

  it('redirects to booking details when agreement summary blocks check-in', async () => {
    const tree = { redirected: true } as any;
    router.createUrlTree.and.returnValue(tree);
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'blocked', legacyBooking: false } as any),
    );

    const result = await TestBed.runInInjectionContext(() =>
      firstValueFrom(checkInAgreementGuard({ paramMap: new Map([['id', '42']]) } as any, {} as any) as any),
    );

    expect(result).toBe(tree);
  });

  it('redirects to booking details with retry UX when agreement gate lookup is unavailable', async () => {
    const tree = { retry: true } as any;
    router.createUrlTree.and.returnValue(tree);
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'retry', legacyBooking: false } as any),
    );

    const result = await TestBed.runInInjectionContext(() =>
      firstValueFrom(checkInAgreementGuard({ paramMap: new Map([['id', '42']]) } as any, {} as any) as any),
    );

    expect(result).toBe(tree);
  });

  it('allows legacy bookings through the guard', async () => {
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'allowed', legacyBooking: true } as any),
    );

    const result = await TestBed.runInInjectionContext(() =>
      firstValueFrom(checkInAgreementGuard({ paramMap: new Map([['id', '42']]) } as any, {} as any) as any),
    );

    expect(result).toBeTrue();
  });

  it('allows navigation when the backend summary says the actor can proceed', async () => {
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'allowed', legacyBooking: false } as any),
    );

    const result = await TestBed.runInInjectionContext(() =>
      firstValueFrom(checkInAgreementGuard({ paramMap: new Map([['id', '42']]) } as any, {} as any) as any),
    );

    expect(result).toBeTrue();
  });
});