import { of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { HandshakeComponent } from './handshake.component';
import { BookingService } from '@core/services/booking.service';
import { CheckInService } from '@core/services/check-in.service';
import { GeolocationService } from '@core/services/geolocation.service';

describe('HandshakeComponent', () => {
  let bookingService: jasmine.SpyObj<BookingService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let checkInService: jasmine.SpyObj<CheckInService>;

  beforeEach(() => {
    bookingService = jasmine.createSpyObj<BookingService>('BookingService', ['getAgreement', 'acceptAgreement', 'resolveCheckInAgreementGate']);
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'allowed', legacyBooking: true, enforcementEnabled: false } as any),
    );
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    checkInService = {
      startPolling: jasmine.createSpy('startPolling'),
      stopPolling: jasmine.createSpy('stopPolling'),
      confirmHandshake: jasmine.createSpy('confirmHandshake').and.returnValue(of({} as any)),
      isLoading: signal(false),
    } as any;

    TestBed.configureTestingModule({
      providers: [
        { provide: BookingService, useValue: bookingService },
        { provide: CheckInService, useValue: checkInService },
        { provide: GeolocationService, useValue: {} },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
  });

  it('keeps agreement gate active on transient fetch failure', () => {
    bookingService.getAgreement.and.returnValue(throwError(() => ({ status: 500, error: { error: 'boom' } })));
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'retry', legacyBooking: false, enforcementEnabled: true } as any),
    );

    const component = TestBed.runInInjectionContext(() => new HandshakeComponent());
    component.bookingId = 42;
    component.status = { host: true, guest: false } as any;
    component.ngOnInit();

    expect(component.agreementLookupState()).toBe('error');
    expect(component.agreementNeeded()).toBeTrue();
  });

  it('treats 404 agreement lookup as legacy booking', () => {
    bookingService.getAgreement.and.returnValue(throwError(() => ({ status: 404 })));
    bookingService.resolveCheckInAgreementGate.and.returnValue(
      of({ state: 'allowed', legacyBooking: true, enforcementEnabled: false } as any),
    );

    const component = TestBed.runInInjectionContext(() => new HandshakeComponent());
    component.bookingId = 42;
    component.status = { host: true, guest: false } as any;
    component.ngOnInit();

    expect(component.agreementLookupState()).toBe('legacy');
    expect(component.agreementNeeded()).toBeFalse();
  });

  it('does not block handshake when enforcement is disabled', () => {
    bookingService.getAgreement.and.returnValue(
      of({ status: 'PENDING', enforcementEnabled: false } as any),
    );

    const component = TestBed.runInInjectionContext(() => new HandshakeComponent());
    component.bookingId = 42;
    component.status = { host: false, handshakeComplete: false } as any;
    component.ngOnInit();

    expect(component.agreementLookupState()).toBe('disabled');
    expect(component.agreementNeeded()).toBeFalse();
    expect(component.canConfirm()).toBeTrue();
  });

  it('shows flat string backend errors when inline agreement acceptance fails', () => {
    bookingService.getAgreement.and.returnValue(of({ status: 'PENDING' } as any));
    bookingService.acceptAgreement.and.returnValue(
      throwError(() => ({ error: 'Ugovor je istekao.' })),
    );

    const component = TestBed.runInInjectionContext(() => new HandshakeComponent());
    component.bookingId = 42;
    component.status = { host: true, guest: false } as any;
    component.ngOnInit();
    component.acceptAgreementInline();

    expect(snackBar.open).toHaveBeenCalledWith('Ugovor je istekao.', 'Zatvori', {
      duration: 5000,
    });
  });

  it('shows flat string backend errors when handshake confirmation fails', () => {
    bookingService.getAgreement.and.returnValue(throwError(() => ({ status: 404 })));
    checkInService.confirmHandshake.and.returnValue(
      throwError(() => ({ error: 'Primopredaja je odbijena.' })),
    );

    const component = TestBed.runInInjectionContext(() => new HandshakeComponent());
    component.bookingId = 42;
    component.status = { host: false, handshakeComplete: false } as any;
    component.ngOnInit();
    component.confirmViaButton();

    expect(snackBar.open).toHaveBeenCalledWith('Primopredaja je odbijena.', 'OK', {
      duration: 5000,
    });
  });
});