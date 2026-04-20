import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';

import { CarDetailComponent } from './car-detail.component';
import { CarService } from '@core/services/car.service';
import { ReviewService } from '@core/services/review.service';
import { BookingService } from '@core/services/booking.service';
import { AvailabilityService } from '@core/services/availability.service';
import { ToastService } from '@core/services/toast.service';
import { AuthService } from '@core/auth/auth.service';
import { RenterVerificationService } from '@core/services/renter-verification.service';
import { Car } from '@core/models/car.model';

describe('CarDetailComponent', () => {
  let dialog: jasmine.SpyObj<MatDialog>;

  beforeEach(() => {
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({
      afterClosed: () => of(undefined),
    } as MatDialogRef<unknown>);

    TestBed.configureTestingModule({
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ id: '123' })),
          },
        },
        {
          provide: Router,
          useValue: {
            url: '/cars/123',
            navigate: jasmine.createSpy('navigate').and.returnValue(Promise.resolve(true)),
          },
        },
        {
          provide: CarService,
          useValue: {
            getCarById: () => of(makeCar()),
          },
        },
        {
          provide: ReviewService,
          useValue: {
            getReviewsForCar: () => of([]),
          },
        },
        {
          provide: BookingService,
          useValue: {
            getPublicBookingsForCar: () => of([]),
          },
        },
        {
          provide: AvailabilityService,
          useValue: {
            getBlockedDatesForCar: () => of([]),
          },
        },
        {
          provide: ToastService,
          useValue: {
            success: () => undefined,
            error: () => undefined,
            info: () => undefined,
            warning: () => undefined,
          },
        },
        {
          provide: AuthService,
          useValue: {
            currentUser$: of(null),
          },
        },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: () => undefined } },
        {
          provide: RenterVerificationService,
          useValue: {
            checkBookingEligibility: () => of({ eligible: true }),
          },
        },
      ],
    });
  });

  it('opens the booking dialog with a dedicated panel class', () => {
    const component = TestBed.runInInjectionContext(() => new CarDetailComponent());

    component['openBookingDialogInternal'](makeCar());

    expect(dialog.open).toHaveBeenCalled();
    const config = dialog.open.calls.mostRecent().args[1]!;
    expect(config.panelClass).toEqual(['booking-dialog-panel']);
  });
});

function makeCar(): Car {
  return {
    id: '123',
    make: 'Tesla',
    model: 'Model 3',
    year: 2024,
    pricePerDay: 100,
    ownerEmail: 'owner@example.com',
    city: 'Belgrade',
    imageUrls: [],
    listingStatus: 'ACTIVE',
    approvalStatus: 'APPROVED',
    address: 'Belgrade',
    latitude: 44.8,
    longitude: 20.46,
    available: true,
    ownerId: 1,
    features: [],
  } as unknown as Car;
}