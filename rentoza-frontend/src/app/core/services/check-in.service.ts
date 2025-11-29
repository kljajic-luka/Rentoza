/**
 * Check-In Service
 *
 * Core orchestrator for the check-in handshake workflow.
 * Implements the state machine and coordinates with backend API.
 *
 * ## State Machine
 * NOT_READY → CHECK_IN_OPEN → HOST_SUBMITTED → GUEST_ACKNOWLEDGED → TRIP_ACTIVE
 *                  ↓                                    ↓
 *            NO_SHOW_HOST                         NO_SHOW_GUEST
 *
 * ## Architecture
 * - Signal-based state for reactive UI (OnPush compatible)
 * - Coordinates PhotoCompressionService, GeolocationService, OfflineQueueService
 * - Implements clientTimestamp injection for basement problem fix
 */

import { Injectable, signal, computed, inject, OnDestroy } from '@angular/core';
import { HttpClient, HttpEvent, HttpEventType, HttpRequest } from '@angular/common/http';
import { Observable, Subject, from, of, throwError } from 'rxjs';
import { map, catchError, switchMap, tap, takeUntil, finalize, filter } from 'rxjs/operators';

import { environment } from '@environments/environment';
import {
  CheckInStatusDTO,
  CheckInPhotoDTO,
  HostCheckInSubmissionDTO,
  GuestConditionAcknowledgmentDTO,
  HandshakeConfirmationDTO,
  CheckInPhotoType,
  PhotoUploadProgress,
  CheckInState,
  WizardStep,
  REQUIRED_HOST_PHOTOS,
  QueuedUpload,
} from '@core/models/check-in.model';
import { PhotoCompressionService } from './photo-compression.service';
import { GeolocationService } from './geolocation.service';
import { OfflineQueueService } from './offline-queue.service';

/**
 * Check-in phase for the wizard component
 */
export type CheckInPhase =
  | 'LOADING'
  | 'NONE'
  | 'HOST_PHASE'
  | 'GUEST_PHASE'
  | 'HANDSHAKE'
  | 'COMPLETE';

@Injectable({ providedIn: 'root' })
export class CheckInService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly compressionService = inject(PhotoCompressionService);
  private readonly geolocationService = inject(GeolocationService);
  private readonly offlineQueueService = inject(OfflineQueueService);

  private readonly baseUrl = `${environment.baseApiUrl}/bookings`;
  private readonly destroy$ = new Subject<void>();
  private pollingInterval: any = null;

  // ========== REACTIVE STATE ==========

  private readonly _status = signal<CheckInStatusDTO | null>(null);
  private readonly _isLoading = signal(false);
  private readonly _error = signal<string | null>(null);
  private readonly _currentStep = signal<WizardStep>('loading');
  private readonly _currentPhase = signal<CheckInPhase>('LOADING');
  private readonly _uploadProgress = signal<Map<CheckInPhotoType, PhotoUploadProgress>>(new Map());
  private readonly _uploadedPhotoIds = signal<number[]>([]);

  // Public readonly signals
  readonly status = this._status.asReadonly();
  readonly isLoading = this._isLoading.asReadonly();
  readonly error = this._error.asReadonly();
  readonly currentStep = this._currentStep.asReadonly();
  readonly currentPhase = this._currentPhase.asReadonly();
  readonly uploadProgress = this._uploadProgress.asReadonly();
  readonly uploadedPhotoIds = this._uploadedPhotoIds.asReadonly();

  // Alias for wizard component compatibility
  readonly currentStatus = this._status.asReadonly();

  // Computed signals
  readonly checkInState = computed<CheckInState>(() => {
    const s = this._status();
    if (!s) return 'NOT_READY';

    // Map backend status to frontend state
    switch (s.status) {
      case 'CHECK_IN_OPEN':
        return 'CHECK_IN_OPEN';
      case 'HOST_SUBMITTED':
        return 'HOST_SUBMITTED';
      case 'GUEST_ACKNOWLEDGED':
        return 'GUEST_ACKNOWLEDGED';
      case 'HANDSHAKE_PENDING':
        return 'HANDSHAKE_PENDING';
      case 'ACTIVE':
      case 'IN_PROGRESS':
        return 'TRIP_ACTIVE';
      case 'NO_SHOW':
        return s.host ? 'NO_SHOW_HOST' : 'NO_SHOW_GUEST';
      default:
        return 'NOT_READY';
    }
  });

  readonly isHost = computed(() => this._status()?.host ?? false);
  readonly isGuest = computed(() => this._status()?.guest ?? false);

  readonly requiredPhotosComplete = computed(() => {
    const progress = this._uploadProgress();
    return REQUIRED_HOST_PHOTOS.every((type) => {
      const p = progress.get(type);
      return p?.state === 'complete';
    });
  });

  readonly uploadedPhotosCount = computed(() => {
    const progress = this._uploadProgress();
    let count = 0;
    progress.forEach((p) => {
      if (p.state === 'complete') count++;
    });
    return count;
  });

  readonly canSubmitHostCheckIn = computed(() => {
    return this.requiredPhotosComplete() && !this._isLoading();
  });

  readonly minutesUntilNoShow = computed(() => {
    return this._status()?.minutesUntilNoShow ?? null;
  });

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.stopPolling();
    // Clean up blob URLs
    this.compressionService.revokeAllObjectUrls();
  }

  /**
   * Stop status polling.
   */
  stopPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
    }
  }

  /**
   * Start periodic polling for status updates.
   */
  startPolling(bookingId: number, intervalMs: number = 30000): void {
    this.stopPolling();
    this.pollingInterval = setInterval(() => {
      this.loadStatus(bookingId).subscribe();
    }, intervalMs);
  }

  // ========== API METHODS ==========

  /**
   * Load check-in status for a booking.
   */
  loadStatus(bookingId: number): Observable<CheckInStatusDTO> {
    this._isLoading.set(true);
    this._error.set(null);
    this._currentPhase.set('LOADING');

    return this.http
      .get<CheckInStatusDTO>(`${this.baseUrl}/${bookingId}/check-in/status`, {
        withCredentials: true,
      })
      .pipe(
        tap((status) => {
          this._status.set(status);
          this.updateStepFromStatus(status);
          this.updatePhaseFromStatus(status);
          // Populate uploaded photos from status
          if (status.vehiclePhotos) {
            const ids = status.vehiclePhotos.map((p) => p.photoId);
            this._uploadedPhotoIds.set(ids);
            // Mark existing photos as complete
            const progress = new Map(this._uploadProgress());
            status.vehiclePhotos.forEach((photo) => {
              progress.set(photo.photoType, {
                photoType: photo.photoType,
                state: 'complete',
                progress: 100,
                result: photo,
              });
            });
            this._uploadProgress.set(progress);
          }
        }),
        catchError((error) => {
          this._error.set(this.extractErrorMessage(error));
          this._currentPhase.set('NONE');
          return throwError(() => error);
        }),
        finalize(() => this._isLoading.set(false)),
        takeUntil(this.destroy$)
      );
  }

  /**
   * Upload a photo with compression and clientTimestamp.
   *
   * Flow:
   * 1. Validate file type
   * 2. Compress image to <500KB
   * 3. Upload with progress tracking
   * 4. Queue for retry if offline
   */
  async uploadPhoto(
    bookingId: number,
    file: File,
    photoType: CheckInPhotoType
  ): Promise<CheckInPhotoDTO> {
    // Update progress: compressing
    this.updateUploadProgress(photoType, 'compressing', 0);

    try {
      // Validate file type
      if (!this.compressionService.isValidImageType(file)) {
        throw new Error('Nevažeći format slike. Koristite JPEG, PNG ili HEIC.');
      }

      // Compress image
      const compressed = await this.compressionService.compressImage(file, {
        targetSizeKB: 500,
      });

      // Capture client timestamp BEFORE upload (basement problem fix)
      const clientTimestamp = new Date().toISOString();

      // Update progress: uploading
      this.updateUploadProgress(photoType, 'uploading', 0);

      // Check if online
      if (!this.offlineQueueService.isOnline()) {
        // Queue for later
        await this.offlineQueueService.enqueue(
          bookingId,
          photoType,
          compressed.blob,
          clientTimestamp
        );
        throw new Error('Nema internet konekcije. Fotografija je sačuvana za kasnije.');
      }

      // Upload with progress tracking
      const result = await this.uploadWithProgress(
        bookingId,
        compressed.blob,
        photoType,
        clientTimestamp
      );

      // Update progress: complete
      this.updateUploadProgress(photoType, 'complete', 100, undefined, result);
      this._uploadedPhotoIds.update((ids) => [...ids, result.photoId]);

      return result;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Upload nije uspeo';
      this.updateUploadProgress(photoType, 'error', 0, errorMessage);
      throw error;
    }
  }

  /**
   * Complete host check-in submission.
   */
  submitHostCheckIn(
    bookingId: number,
    odometerReading: number,
    fuelLevelPercent: number,
    lockboxCode?: string
  ): Observable<CheckInStatusDTO> {
    this._isLoading.set(true);
    this._error.set(null);

    // Get current position
    const position = this.geolocationService.position();

    const payload: HostCheckInSubmissionDTO = {
      bookingId,
      odometerReading,
      fuelLevelPercent,
      photoIds: this._uploadedPhotoIds(),
      lockboxCode,
      hostLatitude: position?.latitude,
      hostLongitude: position?.longitude,
      carLatitude: position?.latitude, // Host position = car position
      carLongitude: position?.longitude,
    };

    return this.http
      .post<CheckInStatusDTO>(`${this.baseUrl}/${bookingId}/check-in/host/complete`, payload, {
        withCredentials: true,
      })
      .pipe(
        tap((status) => {
          this._status.set(status);
          this.updateStepFromStatus(status);
        }),
        catchError((error) => {
          this._error.set(this.extractErrorMessage(error));
          return throwError(() => error);
        }),
        finalize(() => this._isLoading.set(false)),
        takeUntil(this.destroy$)
      );
  }

  /**
   * Guest acknowledges vehicle condition.
   */
  acknowledgeCondition(
    bookingId: number,
    conditionAccepted: boolean,
    conditionComment?: string,
    hotspots?: Array<{ location: string; description: string; photoId?: number }>
  ): Observable<CheckInStatusDTO> {
    this._isLoading.set(true);
    this._error.set(null);

    // Get current position (required for guest)
    const position = this.geolocationService.position();
    if (!position) {
      this._error.set('Lokacija je obavezna za potvrdu stanja vozila');
      this._isLoading.set(false);
      return throwError(() => new Error('Location required'));
    }

    const payload: GuestConditionAcknowledgmentDTO = {
      bookingId,
      conditionAccepted,
      guestLatitude: position.latitude,
      guestLongitude: position.longitude,
      conditionComment,
      hotspots: hotspots as any,
    };

    return this.http
      .post<CheckInStatusDTO>(
        `${this.baseUrl}/${bookingId}/check-in/guest/condition-ack`,
        payload,
        { withCredentials: true }
      )
      .pipe(
        tap((status) => {
          this._status.set(status);
          this.updateStepFromStatus(status);
        }),
        catchError((error) => {
          this._error.set(this.extractErrorMessage(error));
          return throwError(() => error);
        }),
        finalize(() => this._isLoading.set(false)),
        takeUntil(this.destroy$)
      );
  }

  /**
   * Confirm handshake (both host and guest call this).
   */
  confirmHandshake(
    bookingId: number,
    hostVerifiedPhysicalId?: boolean
  ): Observable<CheckInStatusDTO> {
    this._isLoading.set(true);
    this._error.set(null);

    const position = this.geolocationService.position();

    const payload: HandshakeConfirmationDTO = {
      bookingId,
      confirmed: true,
      hostVerifiedPhysicalId,
      latitude: position?.latitude,
      longitude: position?.longitude,
      deviceFingerprint: this.generateDeviceFingerprint(),
    };

    return this.http
      .post<CheckInStatusDTO>(`${this.baseUrl}/${bookingId}/check-in/handshake`, payload, {
        withCredentials: true,
      })
      .pipe(
        tap((status) => {
          this._status.set(status);
          this.updateStepFromStatus(status);
        }),
        catchError((error) => {
          this._error.set(this.extractErrorMessage(error));
          return throwError(() => error);
        }),
        finalize(() => this._isLoading.set(false)),
        takeUntil(this.destroy$)
      );
  }

  /**
   * Reveal lockbox code (guest only, after acknowledgment).
   */
  revealLockboxCode(bookingId: number): Observable<{ lockboxCode: string; revealedAt: string }> {
    const position = this.geolocationService.position();

    let url = `${this.baseUrl}/${bookingId}/check-in/lockbox-code`;
    if (position) {
      url += `?latitude=${position.latitude}&longitude=${position.longitude}`;
    }

    return this.http.get<{ lockboxCode: string; revealedAt: string }>(url, {
      withCredentials: true,
    });
  }

  /**
   * Retry all queued uploads for a booking.
   */
  async retryQueuedUploads(bookingId: number): Promise<void> {
    const items = this.offlineQueueService.getItemsForBooking(bookingId);

    for (const item of items) {
      try {
        this.updateUploadProgress(item.photoType, 'uploading', 0);

        const result = await this.uploadWithProgress(
          item.bookingId,
          item.file,
          item.photoType,
          item.clientTimestamp
        );

        await this.offlineQueueService.dequeue(item.id);
        this.updateUploadProgress(item.photoType, 'complete', 100, undefined, result);
        this._uploadedPhotoIds.update((ids) => [...ids, result.photoId]);
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Retry failed';
        this.updateUploadProgress(item.photoType, 'error', 0, errorMessage);
      }
    }
  }

  /**
   * Set current wizard step manually.
   */
  setStep(step: WizardStep): void {
    this._currentStep.set(step);
  }

  /**
   * Clear upload progress for a specific photo type.
   * Used when user wants to remove/replace a photo.
   */
  clearPhotoProgress(photoType: CheckInPhotoType): void {
    const current = new Map(this._uploadProgress());
    current.delete(photoType);
    this._uploadProgress.set(current);

    // Also remove from uploaded photo IDs if present
    // Note: Backend photo is not deleted, but user can upload a replacement
    // which will override on next submission
  }

  /**
   * Reset service state.
   */
  reset(): void {
    this._status.set(null);
    this._isLoading.set(false);
    this._error.set(null);
    this._currentStep.set('loading');
    this._uploadProgress.set(new Map());
    this._uploadedPhotoIds.set([]);
    this.geolocationService.reset();
    this.compressionService.revokeAllObjectUrls();
  }

  // ========== PRIVATE METHODS ==========

  private uploadWithProgress(
    bookingId: number,
    blob: Blob,
    photoType: CheckInPhotoType,
    clientTimestamp: string
  ): Promise<CheckInPhotoDTO> {
    return new Promise((resolve, reject) => {
      const formData = new FormData();
      formData.append('file', blob, `${photoType}.jpg`);
      formData.append('photoType', photoType);
      formData.append('clientTimestamp', clientTimestamp);

      const request = new HttpRequest(
        'POST',
        `${this.baseUrl}/${bookingId}/check-in/host/photos`,
        formData,
        {
          reportProgress: true,
          withCredentials: true,
        }
      );

      this.http
        .request(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (event: HttpEvent<any>) => {
            if (event.type === HttpEventType.UploadProgress && event.total) {
              const progress = Math.round((event.loaded / event.total) * 100);
              this.updateUploadProgress(photoType, 'uploading', progress);
            } else if (event.type === HttpEventType.Response) {
              this.updateUploadProgress(photoType, 'validating', 100);
              resolve(event.body as CheckInPhotoDTO);
            }
          },
          error: (error) => {
            reject(error);
          },
        });
    });
  }

  private updateUploadProgress(
    photoType: CheckInPhotoType,
    state: PhotoUploadProgress['state'],
    progress: number,
    error?: string,
    result?: CheckInPhotoDTO
  ): void {
    const current = new Map(this._uploadProgress());
    current.set(photoType, { photoType, state, progress, error, result });
    this._uploadProgress.set(current);
  }

  private updateStepFromStatus(status: CheckInStatusDTO): void {
    if (status.handshakeCompletedAt) {
      this._currentStep.set('complete');
    } else if (status.guestCheckInComplete) {
      this._currentStep.set('handshake');
    } else if (status.hostCheckInComplete && status.guest) {
      this._currentStep.set('condition-ack');
    } else if (status.hostCheckInComplete && status.host) {
      this._currentStep.set('review');
    } else if (status.host) {
      this._currentStep.set('photo-upload');
    } else {
      this._currentStep.set('loading');
    }
  }

  private updatePhaseFromStatus(status: CheckInStatusDTO): void {
    // Determine the current phase based on status
    if (status.handshakeCompletedAt) {
      this._currentPhase.set('COMPLETE');
    } else if (
      status.guestCheckInComplete ||
      (status.hostCheckInComplete && status.guestCompletedAt)
    ) {
      this._currentPhase.set('HANDSHAKE');
    } else if (status.hostCheckInComplete) {
      // Host done, now guest's turn
      this._currentPhase.set(status.guest ? 'GUEST_PHASE' : 'HOST_PHASE');
    } else if (status.status === 'CHECK_IN_OPEN' || status.status === 'PENDING_HOST_CHECKIN') {
      this._currentPhase.set(status.host ? 'HOST_PHASE' : 'GUEST_PHASE');
    } else {
      this._currentPhase.set('NONE');
    }
  }

  private extractErrorMessage(error: any): string {
    if (error?.error?.message) return error.error.message;
    if (error?.error?.messagesr) return error.error.messagesr;
    if (error?.message) return error.message;
    return 'Došlo je do greške. Molimo pokušajte ponovo.';
  }

  private generateDeviceFingerprint(): string {
    // Simple fingerprint based on browser characteristics
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.textBaseline = 'top';
      ctx.font = '14px Arial';
      ctx.fillText('fingerprint', 2, 2);
    }

    const data = [
      navigator.userAgent,
      navigator.language,
      screen.width,
      screen.height,
      new Date().getTimezoneOffset(),
      canvas.toDataURL(),
    ].join('|');

    // Simple hash
    let hash = 0;
    for (let i = 0; i < data.length; i++) {
      const char = data.charCodeAt(i);
      hash = (hash << 5) - hash + char;
      hash = hash & hash;
    }

    return Math.abs(hash).toString(16);
  }
}
