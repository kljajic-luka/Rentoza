import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';

import { BookingHistoryComponent } from './booking-history.component';
import { BookingService } from '@core/services/booking.service';
import { UserBooking } from '@core/models/booking.model';

describe('BookingHistoryComponent', () => {
  let bookingService: jasmine.SpyObj<BookingService>;

  beforeEach(() => {
    bookingService = jasmine.createSpyObj<BookingService>('BookingService', ['getMyBookings']);
    bookingService.getMyBookings.and.returnValue(of([]));

    TestBed.configureTestingModule({
      providers: [
        { provide: BookingService, useValue: bookingService },
        { provide: MatDialog, useValue: jasmine.createSpyObj('MatDialog', ['open']) },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
      ],
    });
  });

  it('prioritizes agreement action over guest check-in when current actor still owes acceptance', () => {
    const component = TestBed.runInInjectionContext(() => new BookingHistoryComponent());
    const booking = makeBooking({
      status: 'CHECK_IN_HOST_COMPLETE',
      agreementSummary: {
        workflowStatus: 'AGREEMENT_PENDING_RENTER',
        ownerAccepted: true,
        renterAccepted: false,
        currentActorNeedsAcceptance: true,
        currentActorCanProceedToCheckIn: false,
        legacyBooking: false,
        acceptanceDeadlineAt: '2026-03-12T12:00:00',
        urgencyLevel: 'URGENT',
        recommendedPrimaryAction: 'ACCEPT_RENTAL_AGREEMENT',
      },
    });

    expect((component as any).shouldShowAgreementPrimaryAction(booking)).toBeTrue();
    expect((component as any).canGuestCheckIn(booking)).toBeFalse();
  });
});

function makeBooking(overrides: Partial<UserBooking>): UserBooking {
  return {
    id: 1,
    carId: 2,
    carBrand: 'Tesla',
    carModel: 'Model 3',
    carYear: 2024,
    carImageUrl: null,
    carLocation: 'Belgrade',
    carPricePerDay: 100,
    startTime: '2026-03-12T10:00:00',
    endTime: '2026-03-14T10:00:00',
    totalPrice: 200,
    status: 'ACTIVE',
    hasReview: false,
    reviewRating: null,
    reviewComment: null,
    ...overrides,
  };
}