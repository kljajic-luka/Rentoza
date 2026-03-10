import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';

import { BookingDetailComponent } from './booking-detail.component';
import { BookingService, RentalAgreementDTO } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { BookingDetails } from '@core/models/booking-details.model';

describe('BookingDetailComponent', () => {
  let bookingService: jasmine.SpyObj<BookingService>;

  beforeEach(() => {
    bookingService = jasmine.createSpyObj<BookingService>('BookingService', [
      'getBookingDetails',
      'getAgreement',
      'getBookingExtensions',
    ]);

    TestBed.configureTestingModule({
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ id: '42' }),
              queryParamMap: convertToParamMap({}),
            },
          },
        },
        { provide: BookingService, useValue: bookingService },
        {
          provide: AuthService,
          useValue: {
            getCurrentUser: () => null,
          },
        },
        {
          provide: MatSnackBar,
          useValue: {
            open: () => undefined,
          },
        },
      ],
    });

    bookingService.getAgreement.and.returnValue(of(makeAgreement()));
  });

  it('does not request extensions for non-eligible booking statuses', () => {
    bookingService.getBookingDetails.and.returnValue(of(makeBookingDetails('PENDING_APPROVAL')));

    const component = TestBed.runInInjectionContext(() => new BookingDetailComponent());
    component.loadBooking();

    expect(bookingService.getAgreement).toHaveBeenCalledWith(42);
    expect(bookingService.getBookingExtensions).not.toHaveBeenCalled();
    expect(component.extensions()).toEqual([]);
    expect(component.pendingExtension()).toBeNull();
  });

  it('requests extensions for in-trip bookings', () => {
    bookingService.getBookingDetails.and.returnValue(of(makeBookingDetails('IN_TRIP')));
    bookingService.getBookingExtensions.and.returnValue(of([]));

    const component = TestBed.runInInjectionContext(() => new BookingDetailComponent());
    component.loadBooking();

    expect(bookingService.getBookingExtensions).toHaveBeenCalledWith(42);
  });
});

function makeBookingDetails(status: BookingDetails['status']): BookingDetails {
  return {
    id: 42,
    status,
    startTime: '2026-03-10T09:00:00Z',
    endTime: '2026-03-12T09:00:00Z',
    totalPrice: 100,
    prepaidRefuel: false,
    cancellationPolicy: 'FLEXIBLE',
    carId: 9,
    brand: 'Tesla',
    model: 'Model 3',
    year: 2024,
    location: 'Belgrade',
    hostId: 7,
    hostName: 'Host',
    hostRating: 5,
    hostTotalTrips: 10,
    hostJoinedDate: '2025-01-01',
  };
}

function makeAgreement(): RentalAgreementDTO {
  return {
    id: 1,
    bookingId: 42,
    agreementVersion: 'v1',
    agreementType: 'STANDARD',
    contentHash: 'hash',
    generatedAt: '2026-03-10T09:00:00Z',
    status: 'PENDING',
    ownerAccepted: false,
    ownerAcceptedAt: null,
    renterAccepted: false,
    renterAcceptedAt: null,
    termsTemplateId: null,
    termsTemplateHash: null,
    ownerUserId: 7,
    renterUserId: 8,
    vehicleSnapshot: {},
    termsSnapshot: {},
  };
}