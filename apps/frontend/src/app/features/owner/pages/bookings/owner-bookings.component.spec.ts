import { BehaviorSubject, of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';

import { OwnerBookingsComponent } from './owner-bookings.component';
import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { Booking } from '@core/models/booking.model';

describe('OwnerBookingsComponent', () => {
  let bookingService: jasmine.SpyObj<BookingService>;

  beforeEach(() => {
    bookingService = jasmine.createSpyObj<BookingService>('BookingService', ['getOwnerBookings']);
    bookingService.getOwnerBookings.and.returnValue(of([]));

    TestBed.configureTestingModule({
      providers: [
        { provide: BookingService, useValue: bookingService },
        {
          provide: AuthService,
          useValue: { currentUser$: new BehaviorSubject({ id: 10, email: 'owner@example.com' }) },
        },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj('MatSnackBar', ['open']) },
        { provide: MatDialog, useValue: jasmine.createSpyObj('MatDialog', ['open']) },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
      ],
    });
  });

  it('blocks host check-in until owner accepts agreement but allows host prep after owner acceptance', () => {
    const component = TestBed.runInInjectionContext(() => new OwnerBookingsComponent());

    const ownerPending = makeOwnerBooking({
      status: 'CHECK_IN_OPEN',
      agreementSummary: {
        workflowStatus: 'AGREEMENT_PENDING_OWNER',
        ownerAccepted: false,
        renterAccepted: true,
        currentActorNeedsAcceptance: true,
        currentActorCanProceedToCheckIn: false,
        legacyBooking: false,
        acceptanceDeadlineAt: '2026-03-12T12:00:00',
        urgencyLevel: 'URGENT',
        recommendedPrimaryAction: 'ACCEPT_RENTAL_AGREEMENT',
      },
    });
    const renterPending = makeOwnerBooking({
      status: 'CHECK_IN_OPEN',
      agreementSummary: {
        workflowStatus: 'AGREEMENT_PENDING_RENTER',
        ownerAccepted: true,
        renterAccepted: false,
        currentActorNeedsAcceptance: false,
        currentActorCanProceedToCheckIn: true,
        legacyBooking: false,
        acceptanceDeadlineAt: '2026-03-12T12:00:00',
        urgencyLevel: 'URGENT',
        recommendedPrimaryAction: 'OPEN_CHECK_IN',
      },
    });

    expect((component as any).canCheckIn(ownerPending)).toBeFalse();
    expect((component as any).shouldShowAgreementPrimaryAction(ownerPending)).toBeTrue();
    expect((component as any).canCheckIn(renterPending)).toBeTrue();
  });
});

function makeOwnerBooking(overrides: Partial<Booking>): Booking {
  return {
    id: 1,
    car: { id: 2, brand: 'Tesla', model: 'Model 3', year: 2024 },
    renter: { id: 20, firstName: 'Guest', lastName: 'User' },
    startTime: '2026-03-12T10:00:00',
    endTime: '2026-03-14T10:00:00',
    totalPrice: 200,
    status: 'ACTIVE',
    createdAt: '2026-03-01T10:00:00',
    ...overrides,
  };
}