import {
  isGuestConditionAcknowledgmentPayload,
  isHandshakeConfirmationPayload,
  isHostCheckInSubmissionPayload,
  isQueuedFormPayloadForType,
  isQueuedFormSubmission,
} from './offline-queue.service';

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
