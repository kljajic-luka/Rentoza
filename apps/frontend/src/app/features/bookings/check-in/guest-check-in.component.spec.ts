import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';

import { GuestCheckInComponent } from './guest-check-in.component';
import { CheckInService } from '../../../core/services/check-in.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import { PhotoGuidanceService } from '../../../core/services/photo-guidance.service';
import {
  CaptureState,
  CheckInPersistenceService,
} from '../../../core/services/check-in-persistence.service';
import { CheckInStatusDTO, CheckInPhotoDTO } from '../../../core/models/check-in.model';
import { GuestCheckInPhotoSubmissionDTO } from '../../../core/models/photo-guidance.model';

describe('GuestCheckInComponent', () => {
  let fixture: ComponentFixture<GuestCheckInComponent>;
  let component: GuestCheckInComponent;
  let checkInService: jasmine.SpyObj<CheckInService> & {
    isLoading: ReturnType<typeof signal<boolean>>;
  };
  let persistenceService: jasmine.SpyObj<CheckInPersistenceService> & { isReady: boolean };
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const buildStatus = (overrides: Partial<CheckInStatusDTO> = {}): CheckInStatusDTO => ({
    bookingId: 42,
    checkInSessionId: 'session-active',
    status: 'CHECK_IN_HOST_COMPLETE',
    hostCheckInComplete: true,
    guestCheckInComplete: false,
    guestAcknowledged: false,
    guestConditionAcknowledged: false,
    handshakeReady: false,
    hostCheckedIn: true,
    handshakeComplete: false,
    hostConfirmedHandshake: false,
    guestConfirmedHandshake: false,
    guestPhotoVerificationEnabled: true,
    guestPhotosRequired: true,
    guestConfirmedPhotoCount: 0,
    guestPhotosConfirmedComplete: false,
    missingGuestPhotoTypes: ['GUEST_EXTERIOR_FRONT'],
    checkInOpenedAt: null,
    hostCompletedAt: null,
    guestCompletedAt: null,
    handshakeCompletedAt: null,
    bookingStartTime: null,
    vehiclePhotos: [],
    odometerReading: 12345,
    fuelLevelPercent: 65,
    lockboxAvailable: false,
    geofenceValid: true,
    geofenceDistanceMeters: null,
    tripStartScheduled: new Date().toISOString(),
    noShowDeadline: null,
    minutesUntilNoShow: null,
    canStartTrip: false,
    host: false,
    guest: true,
    car: {
      id: 7,
      brand: 'Fiat',
      model: 'Punto',
      year: 2021,
      imageUrl: null,
    },
    ...overrides,
  });

  const buildSubmission = (): GuestCheckInPhotoSubmissionDTO => ({
    photos: [
      {
        photoType: 'GUEST_EXTERIOR_FRONT',
        base64Data: 'ZmFrZQ==',
        filename: 'front.jpg',
        mimeType: 'image/jpeg',
        capturedAt: new Date().toISOString(),
      },
      {
        photoType: 'GUEST_EXTERIOR_REAR',
        base64Data: 'ZmFrZTI=',
        filename: 'rear.jpg',
        mimeType: 'image/jpeg',
        capturedAt: new Date().toISOString(),
      },
    ],
    clientCapturedAt: new Date().toISOString(),
    deviceInfo: 'test-agent',
  });

  const buildCaptureState = (): CaptureState => ({
    id: 'capture-42-guest-checkin',
    bookingId: 42,
    mode: 'guest-checkin',
    currentIndex: 7,
    currentPhase: 'guidance',
    capturedPhotos: Array.from({ length: 8 }, (_, index) => ({
      photoType:
        [
          'GUEST_EXTERIOR_FRONT',
          'GUEST_EXTERIOR_REAR',
          'GUEST_EXTERIOR_LEFT',
          'GUEST_EXTERIOR_RIGHT',
          'GUEST_INTERIOR_DASHBOARD',
          'GUEST_INTERIOR_REAR',
          'GUEST_ODOMETER',
          'GUEST_FUEL_GAUGE',
        ][index] as any,
      base64Data: 'ZmFrZQ==',
      mimeType: 'image/jpeg',
      capturedAt: new Date().toISOString(),
      verified: true,
    })),
    savedAt: new Date().toISOString(),
    version: 1,
    tabId: 'tab-1',
  });

  const confirmedPhotos: CheckInPhotoDTO[] = [
    {
      photoId: 1,
      photoType: 'GUEST_EXTERIOR_FRONT',
      url: 'https://signed.example/front.jpg',
      uploadedAt: new Date().toISOString(),
      exifValidationStatus: 'VALID',
      exifValidationMessage: null,
      width: null,
      height: null,
      mimeType: 'image/jpeg',
      exifTimestamp: null,
      exifLatitude: null,
      exifLongitude: null,
      deviceModel: null,
      accepted: true,
    },
  ];

  beforeEach(async () => {
    checkInService = jasmine.createSpyObj<CheckInService>(
      'CheckInService',
      ['uploadGuestPhotos', 'getGuestPhotos', 'acknowledgeCondition', 'revealLockboxCode'],
      { isLoading: signal(false) },
    ) as any;
    checkInService.getGuestPhotos.and.returnValue(of([]));
    checkInService.acknowledgeCondition.and.returnValue(of({} as any));
    checkInService.revealLockboxCode.and.returnValue(
      of({ lockboxCode: '1234', revealedAt: new Date().toISOString() }),
    );

    persistenceService = jasmine.createSpyObj<CheckInPersistenceService>(
      'CheckInPersistenceService',
      [
        'waitForReady',
        'checkForSavedSession',
        'acquireLock',
        'releaseLock',
        'loadFormState',
        'saveFormState',
        'deleteCaptureState',
        'loadCaptureState',
        'clearBookingData',
      ],
      { isReady: true },
    ) as any;
    persistenceService.waitForReady.and.resolveTo();
    persistenceService.checkForSavedSession.and.resolveTo({ exists: false });
    persistenceService.acquireLock.and.resolveTo(true);
    persistenceService.loadFormState.and.resolveTo(null);
    persistenceService.saveFormState.and.resolveTo();
    persistenceService.deleteCaptureState.and.resolveTo();
    persistenceService.loadCaptureState.and.resolveTo(null);
    persistenceService.clearBookingData.and.resolveTo();

    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as any);

    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open', 'dismiss']);

    await TestBed.configureTestingModule({
      imports: [GuestCheckInComponent],
      providers: [
        provideNoopAnimations(),
        { provide: CheckInService, useValue: checkInService },
        { provide: GeolocationService, useValue: { hasPosition: () => true } },
        {
          provide: PhotoGuidanceService,
          useValue: {
            progress: signal(0),
            resetCapture: jasmine.createSpy('resetCapture'),
          },
        },
        { provide: CheckInPersistenceService, useValue: persistenceService },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
  });

  async function createComponent(status: CheckInStatusDTO): Promise<void> {
    fixture = TestBed.createComponent(GuestCheckInComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('bookingId', 42);
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('hides the skip CTA when the server marks guest photos as required', async () => {
    await createComponent(buildStatus({ guestPhotosRequired: true }));

    expect(fixture.nativeElement.querySelector('.skip-btn')).toBeNull();
  });

  it('keeps the capture CTA hidden while upload is in progress', async () => {
    const upload$ = new Subject<any>();
    checkInService.uploadGuestPhotos.and.returnValue(upload$.asObservable());

    await createComponent(buildStatus());
    component.onGuestPhotosComplete(buildSubmission());
    fixture.detectChanges();

    expect(component.guestPhotoWorkflowState()).toBe('uploading');
    expect(fixture.nativeElement.querySelector('.uploading-state-card')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.start-capture-btn')).toBeNull();
  });

  it('moves upload failure into a retryable state instead of completed', async () => {
    checkInService.uploadGuestPhotos.and.returnValue(
      throwError(() => ({ error: { message: 'upload failed' } })),
    );

    await createComponent(buildStatus());
    component.onGuestPhotosComplete(buildSubmission());
    fixture.detectChanges();

    expect(component.guestPhotoWorkflowState()).toBe('upload_failed_retryable');
    expect(component.guestPhotosComplete()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Pokušaj ponovo');
  });

  it('reconciles refresh against confirmed backend photos and does not show capture again', async () => {
    checkInService.getGuestPhotos.and.returnValue(of(confirmedPhotos));
    persistenceService.checkForSavedSession.and.resolveTo({ exists: true, photoCount: 8 });

    await createComponent(
      buildStatus({
        guestConfirmedPhotoCount: 8,
        guestPhotosConfirmedComplete: true,
        missingGuestPhotoTypes: [],
      }),
    );

    expect(component.guestPhotoWorkflowState()).toBe('uploaded_confirmed');
    expect(dialog.open).not.toHaveBeenCalled();
    expect(persistenceService.deleteCaptureState).toHaveBeenCalledWith(42, 'guest-checkin');
    expect(fixture.nativeElement.querySelector('.start-capture-btn')).toBeNull();
  });

  it('offers resume flow when a local draft exists without backend confirmation', async () => {
    const captureState = buildCaptureState();
    persistenceService.loadCaptureState.and.resolveTo(captureState);
    persistenceService.checkForSavedSession.and.resolveTo({ exists: false });

    await createComponent(buildStatus({ guestConfirmedPhotoCount: 0, guestPhotosConfirmedComplete: false }));

    expect(component.guestPhotoWorkflowState()).toBe('local_draft_resumable');
    expect(component.restoredCaptureState()).toEqual(captureState);
    expect(component.guestPhotoPrimaryActionLabel()).toBe('Pošalji sačuvane fotografije');
  });
});
