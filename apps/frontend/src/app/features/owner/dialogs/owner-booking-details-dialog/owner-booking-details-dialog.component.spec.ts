import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

import { OwnerBookingDetailsDialogComponent } from './owner-booking-details-dialog.component';
import { BookingService } from '@core/services/booking.service';

describe('OwnerBookingDetailsDialogComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: BookingService, useValue: jasmine.createSpyObj('BookingService', ['getBookingById']) },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
        { provide: MAT_DIALOG_DATA, useValue: { bookingId: 42 } },
      ],
    });
  });

  it('opens check-in directly when owner can proceed and agreement CTA is no longer primary', () => {
    const component = TestBed.runInInjectionContext(
      () => new OwnerBookingDetailsDialogComponent({ bookingId: 42 }),
    );

    component.booking.set({
      id: 42,
      status: 'CHECK_IN_OPEN',
      startTime: '2026-03-12T10:00:00',
      endTime: '2026-03-14T10:00:00',
      totalPrice: 100,
      createdAt: '2026-03-01T10:00:00',
      car: { id: 7, brand: 'Tesla', model: 'Model 3', year: 2024 },
      renter: { id: 20, firstName: 'Guest', lastName: 'User' },
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
    } as any);

    expect((component as any).shouldShowAgreementPrimaryAction()).toBeFalse();
    expect((component as any).canOpenCheckIn()).toBeTrue();
  });
});