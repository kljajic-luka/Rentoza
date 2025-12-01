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
import { Observable, Subject, Subscription, from, of, throwError } from 'rxjs';
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
  DamagePhotoSlot,
  MAX_DAMAGE_PHOTOS,
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

/**
 * Role-aware render decision for the wizard component.
 *
 * This is the SINGLE SOURCE OF TRUTH for what UI to display.
 * Derived reactively from status + role - cannot be manipulated via console.
 *
 * @see Architectural Blueprint: "Strict Logic Matrix"
 */
export type RenderDecision =
  | 'LOADING' // Initial state while fetching status
  | 'NOT_READY' // Check-in window not yet open
  | 'HOST_EDIT' // Host can edit (upload photos, submit)
  | 'HOST_WAITING' // Host waiting for guest to acknowledge
  | 'HOST_REVIEW' // Host reviewing submitted data (read-only)
  | 'GUEST_WAITING' // Guest waiting for host to complete
  | 'GUEST_EDIT' // Guest can acknowledge condition
  | 'HANDSHAKE' // Both parties confirming handshake
  | 'COMPLETE'; // Trip started

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
  // String-keyed map: photoType for required photos, UUID for damage photos
  private readonly _uploadProgress = signal<Map<string, PhotoUploadProgress>>(new Map());
  private readonly _uploadedPhotoIds = signal<number[]>([]);

  // Upload cancellation: track in-flight HTTP requests by slotId
  // Prevents race condition when user re-selects same slot before previous upload completes
  private readonly _activeUploads = new Map<string, Subscription>();

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

  /**
   * Role-aware render decision - the SINGLE SOURCE OF TRUTH for wizard UI.
   *
   * This computed signal implements the "Strict Logic Matrix" from the architecture:
   * - CHECK_IN_OPEN + HOST → HOST_EDIT
   * - CHECK_IN_OPEN + GUEST → GUEST_WAITING
   * - CHECK_IN_HOST_COMPLETE + HOST → HOST_WAITING (with read-only review)
   * - CHECK_IN_HOST_COMPLETE + GUEST → GUEST_EDIT
   * - CHECK_IN_COMPLETE → HANDSHAKE
   * - IN_TRIP → COMPLETE
   *
   * SECURITY: This signal is derived from immutable backend status.
   * Console manipulation of other signals won't affect this decision.
   */
  readonly renderDecision = computed<RenderDecision>(() => {
    const status = this._status();

    // Loading state
    if (!status) {
      return this._isLoading() ? 'LOADING' : 'NOT_READY';
    }

    const isHost = status.host;
    const isGuest = status.guest;
    const bookingStatus = status.status;

    // State machine with role segregation
    switch (bookingStatus) {
      case 'CHECK_IN_OPEN':
      case 'PENDING_HOST_CHECKIN':
        // Check-in open, awaiting host
        return isHost ? 'HOST_EDIT' : 'GUEST_WAITING';

      case 'CHECK_IN_HOST_COMPLETE':
      case 'HOST_SUBMITTED':
        // Host completed, awaiting guest
        if (isHost) {
          // Host can review their submission (read-only) while waiting
          return 'HOST_WAITING';
        }
        return 'GUEST_EDIT';

      case 'CHECK_IN_COMPLETE':
      case 'GUEST_ACKNOWLEDGED':
      case 'HANDSHAKE_PENDING':
        // Both ready for handshake
        return 'HANDSHAKE';

      case 'IN_TRIP':
      case 'ACTIVE':
      case 'IN_PROGRESS':
        // Trip has started
        return 'COMPLETE';

      default:
        // Any other status (PENDING_APPROVAL, COMPLETED, etc.)
        return 'NOT_READY';
    }
  });

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
    // Cancel all in-flight uploads
    this._activeUploads.forEach((sub) => sub.unsubscribe());
    this._activeUploads.clear();
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
            // Mark existing photos as complete (slotId = photoType for required photos)
            const progress = new Map(this._uploadProgress());
            status.vehiclePhotos.forEach((photo) => {
              progress.set(photo.photoType, {
                slotId: photo.photoType, // Required photos use photoType as slotId
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
   * Upload a photo with compression, GPS injection, and upload cancellation.
   *
   * "Snap & Go" Pattern:
   * - Fire-and-forget: returns immediately, upload runs in background
   * - Cancellation: re-selecting same slot cancels previous in-flight upload
   * - GPS injection: current position appended to FormData for backend verification
   * - Progress tracking: updates _uploadProgress signal throughout
   *
   * @param bookingId - Booking ID
   * @param file - Image file to upload
   * @param slotId - Unique slot identifier (photoType for required, UUID for damage)
   * @param photoType - Photo type enum for backend
   */
  uploadPhoto(bookingId: number, file: File, slotId: string, photoType: CheckInPhotoType): void {
    // Cancel any existing upload for this slot (race condition prevention)
    const existingUpload = this._activeUploads.get(slotId);
    if (existingUpload) {
      existingUpload.unsubscribe();
      this._activeUploads.delete(slotId);
    }

    // Update progress: compressing
    this.updateUploadProgress(slotId, photoType, 'compressing', 0);

    // Validate file type
    if (!this.compressionService.isValidImageType(file)) {
      this.updateUploadProgress(
        slotId,
        photoType,
        'error',
        0,
        'Nevažeći format slike. Koristite JPEG, PNG ili HEIC.'
      );
      return;
    }

    // Capture client timestamp and GPS BEFORE upload starts (basement problem fix)
    const clientTimestamp = new Date().toISOString();
    const position = this.geolocationService.position();

    // Fire-and-forget Observable chain: compress → upload
    const subscription = from(this.compressionService.compressImage(file, { targetSizeKB: 500 }))
      .pipe(
        tap(() => this.updateUploadProgress(slotId, photoType, 'uploading', 0)),
        switchMap((compressed) => {
          // Check if online
          if (!this.offlineQueueService.isOnline()) {
            // Queue for later (async, but we don't block)
            this.offlineQueueService.enqueue(
              bookingId,
              photoType,
              compressed.blob,
              clientTimestamp
            );
            throw new Error('Nema internet konekcije. Fotografija je sačuvana za kasnije.');
          }

          return this.uploadWithProgressObservable(
            bookingId,
            compressed.blob,
            slotId,
            photoType,
            clientTimestamp,
            position?.latitude,
            position?.longitude
          );
        }),
        takeUntil(this.destroy$),
        finalize(() => {
          // Clean up from active uploads map
          this._activeUploads.delete(slotId);
        })
      )
      .subscribe({
        next: (result) => {
          this.updateUploadProgress(slotId, photoType, 'complete', 100, undefined, result);
          this._uploadedPhotoIds.update((ids) => [...ids, result.photoId]);
        },
        error: (error) => {
          const errorMessage = this.extractPhotoErrorMessage(error);
          this.updateUploadProgress(slotId, photoType, 'error', 0, errorMessage);
        },
      });

    // Track subscription for potential cancellation
    this._activeUploads.set(slotId, subscription);
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

    // Check if location is required (bypass in development if configured)
    const locationRequired = environment.checkIn?.requireLocation !== false;

    // Get current position (required for guest in production)
    const position = this.geolocationService.position();
    if (locationRequired && !position) {
      this._error.set('Lokacija je obavezna za potvrdu stanja vozila');
      this._isLoading.set(false);
      return throwError(() => new Error('Location required'));
    }

    const payload: GuestConditionAcknowledgmentDTO = {
      bookingId,
      conditionAccepted,
      // Use mock coordinates in development if location not available
      guestLatitude: position?.latitude ?? 44.8176, // Belgrade fallback
      guestLongitude: position?.longitude ?? 20.4633,
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
      const slotId = item.photoType; // For queued items, slotId = photoType
      try {
        this.updateUploadProgress(slotId, item.photoType, 'uploading', 0);

        const result = await this.uploadWithProgress(
          item.bookingId,
          item.file,
          slotId,
          item.photoType,
          item.clientTimestamp
        );

        await this.offlineQueueService.dequeue(item.id);
        this.updateUploadProgress(slotId, item.photoType, 'complete', 100, undefined, result);
        this._uploadedPhotoIds.update((ids) => [...ids, result.photoId]);
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Retry failed';
        this.updateUploadProgress(slotId, item.photoType, 'error', 0, errorMessage);
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
   * Clear upload progress for a specific slot.
   * Used when user wants to remove/replace a photo.
   * @param slotId - Slot identifier (photoType for required, UUID for damage)
   */
  clearPhotoProgress(slotId: string): void {
    // Cancel any in-flight upload for this slot
    const activeUpload = this._activeUploads.get(slotId);
    if (activeUpload) {
      activeUpload.unsubscribe();
      this._activeUploads.delete(slotId);
    }

    const current = new Map(this._uploadProgress());
    current.delete(slotId);
    this._uploadProgress.set(current);

    // Also remove from uploaded photo IDs if present
    // Note: Backend photo is not deleted, but user can upload a replacement
    // which will override on next submission (orphan cleanup deferred to Phase 4)
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

  /**
   * Upload with progress tracking (Promise-based for legacy async support).
   */
  private uploadWithProgress(
    bookingId: number,
    blob: Blob,
    slotId: string,
    photoType: CheckInPhotoType,
    clientTimestamp: string,
    clientLatitude?: number,
    clientLongitude?: number
  ): Promise<CheckInPhotoDTO> {
    return new Promise((resolve, reject) => {
      const formData = new FormData();
      formData.append('file', blob, `${photoType}.jpg`);
      formData.append('photoType', photoType);
      formData.append('clientTimestamp', clientTimestamp);

      // Inject GPS coordinates for backend verification (defense-in-depth)
      if (clientLatitude != null) {
        formData.append('clientLatitude', clientLatitude.toString());
      }
      if (clientLongitude != null) {
        formData.append('clientLongitude', clientLongitude.toString());
      }

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
              this.updateUploadProgress(slotId, photoType, 'uploading', progress);
            } else if (event.type === HttpEventType.Response) {
              this.updateUploadProgress(slotId, photoType, 'validating', 100);
              resolve(event.body as CheckInPhotoDTO);
            }
          },
          error: (error) => {
            reject(error);
          },
        });
    });
  }

  /**
   * Upload with progress tracking (Observable-based for fire-and-forget pattern).
   */
  private uploadWithProgressObservable(
    bookingId: number,
    blob: Blob,
    slotId: string,
    photoType: CheckInPhotoType,
    clientTimestamp: string,
    clientLatitude?: number,
    clientLongitude?: number
  ): Observable<CheckInPhotoDTO> {
    const formData = new FormData();
    formData.append('file', blob, `${photoType}.jpg`);
    formData.append('photoType', photoType);
    formData.append('clientTimestamp', clientTimestamp);

    // Inject GPS coordinates for backend verification
    if (clientLatitude != null) {
      formData.append('clientLatitude', clientLatitude.toString());
    }
    if (clientLongitude != null) {
      formData.append('clientLongitude', clientLongitude.toString());
    }

    const request = new HttpRequest(
      'POST',
      `${this.baseUrl}/${bookingId}/check-in/host/photos`,
      formData,
      {
        reportProgress: true,
        withCredentials: true,
      }
    );

    return this.http.request(request).pipe(
      tap((event: HttpEvent<any>) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          const progress = Math.round((event.loaded / event.total) * 100);
          this.updateUploadProgress(slotId, photoType, 'uploading', progress);
        } else if (event.type === HttpEventType.UploadProgress) {
          // Indeterminate progress
          this.updateUploadProgress(slotId, photoType, 'uploading', 50);
        }
      }),
      filter((event: HttpEvent<any>) => event.type === HttpEventType.Response),
      map((event) => {
        this.updateUploadProgress(slotId, photoType, 'validating', 100);
        // HttpResponse has body property
        return (event as any).body as CheckInPhotoDTO;
      })
    );
  }

  /**
   * Extract user-friendly error message from photo upload errors.
   * Maps backend EXIF validation errors to Serbian messages.
   */
  private extractPhotoErrorMessage(error: any): string {
    // Backend EXIF validation errors
    if (error?.error?.exifValidationStatus) {
      switch (error.error.exifValidationStatus) {
        case 'REJECTED_TOO_OLD':
          return 'Fotografija je prestara. Slikajte novu fotografiju.';
        case 'REJECTED_NO_EXIF':
          return 'Fotografija nema metapodatke. Koristite kameru, ne galeriju.';
        case 'REJECTED_FUTURE_TIMESTAMP':
          return 'Sat na uređaju nije tačan. Proverite podešavanja.';
        case 'REJECTED_NO_GPS':
          return 'Fotografija nema GPS lokaciju. Omogućite lokaciju u podešavanjima kamere.';
        case 'REJECTED_LOCATION_MISMATCH':
          return 'Lokacija fotografije ne odgovara lokaciji vozila.';
      }
    }

    // Backend message (Serbian)
    if (error?.error?.message) return error.error.message;
    if (error?.error?.messagesr) return error.error.messagesr;

    // Generic error
    if (error?.message) return error.message;
    return 'Upload nije uspeo. Pokušajte ponovo.';
  }

  private updateUploadProgress(
    slotId: string,
    photoType: CheckInPhotoType,
    state: PhotoUploadProgress['state'],
    progress: number,
    error?: string,
    result?: CheckInPhotoDTO
  ): void {
    const current = new Map(this._uploadProgress());
    current.set(slotId, { slotId, photoType, state, progress, error, result });
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
    // Note: The wizard template handles role-based display (host vs guest)
    // This method sets the phase based on workflow state, not viewer role

    if (status.handshakeCompletedAt) {
      this._currentPhase.set('COMPLETE');
    } else if (
      status.guestCheckInComplete ||
      (status.hostCheckInComplete && status.guestCompletedAt)
    ) {
      this._currentPhase.set('HANDSHAKE');
    } else if (status.hostCheckInComplete) {
      // Host done - phase is GUEST_PHASE regardless of who is viewing
      // The wizard template determines if viewer should see GuestCheckIn or WaitingScreen
      this._currentPhase.set('GUEST_PHASE');
    } else if (status.status === 'CHECK_IN_OPEN' || status.status === 'PENDING_HOST_CHECKIN') {
      // Check-in open, host hasn't submitted yet - HOST_PHASE
      // The wizard template determines if viewer should see HostCheckIn or WaitingScreen
      this._currentPhase.set('HOST_PHASE');
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
