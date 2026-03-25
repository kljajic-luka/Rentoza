import {
  OfflineQueueService,
  isGuestConditionAcknowledgmentPayload,
  isHandshakeConfirmationPayload,
  isHostCheckInSubmissionPayload,
  isQueuedFormPayloadForType,
  isQueuedFormSubmission,
} from './offline-queue.service';
import { TestBed } from '@angular/core/testing';

import { CheckInPhotoType } from '@core/models/check-in.model';
import { LoggerService } from './logger.service';

describe('offline queue validators', () => {
  it('accepts a valid host check-in payload', () => {
    expect(
      isHostCheckInSubmissionPayload({
        bookingId: 42,
        odometerReading: 123456,
        fuelLevelPercent: 75,
        photoIds: [1, 2, 3],
        hostLatitude: 44.8176,
        hostLongitude: 20.4633,
      }),
    ).toBeTrue();
  });

  it('rejects a malformed guest acknowledgment payload', () => {
    expect(
      isGuestConditionAcknowledgmentPayload({
        bookingId: 42,
        conditionAccepted: true,
        guestLatitude: '44.8',
        guestLongitude: 20.4633,
      }),
    ).toBeFalse();
  });

  it('accepts handshake payloads without mock-location flag from the web client', () => {
    expect(
      isHandshakeConfirmationPayload({
        bookingId: 42,
        confirmed: true,
        latitude: 44.8176,
        longitude: 20.4633,
        horizontalAccuracy: 12,
        platform: 'WEB',
        deviceFingerprint: 'device-123',
      }),
    ).toBeTrue();
  });

  it('validates queued form payloads against their declared type', () => {
    expect(
      isQueuedFormPayloadForType('HANDSHAKE', {
        bookingId: 42,
        confirmed: true,
        platform: 'WEB',
      }),
    ).toBeTrue();

    expect(
      isQueuedFormPayloadForType('HOST_CHECK_IN', {
        bookingId: 42,
        confirmed: true,
      }),
    ).toBeFalse();
  });

  it('rejects queued form submissions with mismatched payload shape', () => {
    expect(
      isQueuedFormSubmission({
        id: 'abc',
        bookingId: 42,
        type: 'HOST_CHECK_IN',
        payload: {
          bookingId: 42,
          confirmed: true,
        },
        retryCount: 0,
        createdAt: new Date().toISOString(),
      }),
    ).toBeFalse();
  });
});

describe('OfflineQueueService photo validation', () => {
  let service: OfflineQueueService;

  beforeAll(() => {
    spyOn<any>(OfflineQueueService.prototype, 'initDatabase').and.returnValue(Promise.resolve());
    spyOn<any>(OfflineQueueService.prototype, 'setupNetworkListeners').and.stub();
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        OfflineQueueService,
        {
          provide: LoggerService,
          useValue: jasmine.createSpyObj<LoggerService>('LoggerService', ['log', 'warn', 'error']),
        },
      ],
    });

    service = TestBed.inject(OfflineQueueService);
    spyOn<any>(service, 'saveToDb').and.returnValue(Promise.resolve());
  });

  afterEach(() => {
    service.ngOnDestroy();
  });

  it('rejects unsupported MIME types before enqueue', async () => {
    await expectAsync(
      service.enqueue(
        42,
        'HOST_EXTERIOR_FRONT',
        new Blob(['not-an-image'], { type: 'text/plain' }),
        new Date().toISOString(),
      ),
    ).toBeRejectedWithError(/Nepodržan format fotografije/);
  });

  it('rejects invalid photo types before enqueue', async () => {
    await expectAsync(
      service.enqueue(
        42,
        'NOT_A_REAL_PHOTO_TYPE' as CheckInPhotoType,
        new Blob(['image'], { type: 'image/jpeg' }),
        new Date().toISOString(),
      ),
    ).toBeRejectedWithError(/Nevažeći tip fotografije/);
  });

  it('rejects malformed persisted uploads during restore validation', () => {
    const malformedUpload = {
      id: 'upload-1',
      bookingId: 42,
      photoType: 'NOT_A_REAL_PHOTO_TYPE',
      file: new Blob(['image'], { type: 'image/jpeg' }),
      clientTimestamp: new Date().toISOString(),
      retryCount: 0,
      createdAt: new Date().toISOString(),
    };

    expect((service as any).isQueuedUpload(malformedUpload)).toBeFalse();
  });
});
