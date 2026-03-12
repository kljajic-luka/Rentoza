import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

import { BookingDetailsDialogComponent } from './booking-details-dialog.component';
import { BookingService } from '@core/services/booking.service';

describe('BookingDetailsDialogComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: BookingService, useValue: jasmine.createSpyObj('BookingService', ['getBookingDetails']) },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
        { provide: MatDialog, useValue: jasmine.createSpyObj('MatDialog', ['open']) },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
        { provide: MAT_DIALOG_DATA, useValue: { bookingId: 42 } },
      ],
    });
  });

  it('prioritizes agreement CTA over generic manage flow when guest still owes acceptance', () => {
    const component = TestBed.runInInjectionContext(
      () => new BookingDetailsDialogComponent({ bookingId: 42 }),
    );

    component.booking.set({
      id: 42,
      status: 'CHECK_IN_HOST_COMPLETE',
      startTime: '2026-03-12T10:00:00',
      endTime: '2026-03-14T10:00:00',
      totalPrice: 100,
      prepaidRefuel: false,
      cancellationPolicy: 'STANDARD',
      carId: 7,
      brand: 'Tesla',
      model: 'Model 3',
      year: 2024,
      location: 'Belgrade',
      hostId: 10,
      hostName: 'Host',
      hostRating: 5,
      hostTotalTrips: 11,
      hostJoinedDate: '2025-01-01',
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
    } as any);

    expect((component as any).shouldShowAgreementPrimaryAction()).toBeTrue();
    expect((component as any).canOpenCheckIn()).toBeFalse();
    expect((component as any).getAgreementActionLabel()).toBe('Pregledaj i prihvati ugovor');
  });
});