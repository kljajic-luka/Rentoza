import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GuestBookingPreviewDialogComponent } from './guest-booking-preview-dialog.component';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { BookingService } from '@core/services/booking.service';
import { of } from 'rxjs';
import { GuestBookingPreview } from '@core/models/guest-preview.model';

describe('GuestBookingPreviewDialogComponent', () => {
  let component: GuestBookingPreviewDialogComponent;
  let fixture: ComponentFixture<GuestBookingPreviewDialogComponent>;
  let bookingServiceSpy: jasmine.SpyObj<BookingService>;

  const mockPreview: GuestBookingPreview = {
    profilePhotoUrl: 'url',
    firstName: 'John',
    lastInitial: 'D.',
    joinDate: 'Oct 2021',
    emailVerified: true,
    phoneVerified: true,
    identityVerified: true,
    drivingEligibilityStatus: 'APPROVED',
    starRating: 4.5,
    tripCount: 5,
    hostReviews: [],
    requestedStartDateTime: '2023-01-01T10:00:00',
    requestedEndDateTime: '2023-01-03T10:00:00',
    protectionPlan: 'BASIC',
  };

  beforeEach(async () => {
    bookingServiceSpy = jasmine.createSpyObj('BookingService', ['getGuestPreview']);
    bookingServiceSpy.getGuestPreview.and.returnValue(of(mockPreview));

    await TestBed.configureTestingModule({
      imports: [GuestBookingPreviewDialogComponent],
      providers: [
        { provide: BookingService, useValue: bookingServiceSpy },
        { provide: MatDialogRef, useValue: { close: () => {} } },
        { provide: MAT_DIALOG_DATA, useValue: { bookingId: 1 } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GuestBookingPreviewDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load preview on init', () => {
    expect(bookingServiceSpy.getGuestPreview).toHaveBeenCalledWith(1);
    expect(component.guestPreview()).toEqual(mockPreview);
    expect(component.isLoading()).toBeFalse();
  });

  it('should display guest name correctly', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const nameEl = compiled.querySelector('.profile-info h3');
    expect(nameEl?.textContent).toContain('John D.');
  });
});
