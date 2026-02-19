/**
 * Checkout Service
 *
 * Manages the checkout workflow including:
 * - Status fetching and polling
 * - Photo uploads
 * - Guest checkout submission
 * - Host confirmation
 */
import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpEvent, HttpEventType } from '@angular/common/http';
import { Observable, BehaviorSubject, interval, of, throwError } from 'rxjs';
import { map, tap, catchError, takeWhile, switchMap, filter } from 'rxjs/operators';

import { environment } from '@environments/environment';
import { PhotoCompressionService } from './photo-compression.service';
import { GeolocationService } from './geolocation.service';
import {
  CheckOutStatusDTO,
  GuestCheckOutSubmissionDTO,
  HostCheckOutConfirmationDTO,
  CheckoutWizardStep,
  PhotoUploadProgress,
} from '../models/checkout.model';
import { CheckInPhotoDTO, CheckInPhotoType } from '../models/check-in.model';

type CheckoutRenderDecision =
  | 'LOADING'
  | 'IN_TRIP' // Trip active, checkout not started
  | 'GUEST_EDIT' // Guest can upload photos and submit
  | 'GUEST_WAITING' // Guest waiting for host confirmation
  | 'HOST_WAITING' // Host waiting for guest submission
  | 'HOST_CONFIRM' // Host can confirm checkout
  | 'DAMAGE_DISPUTE_GUEST' // Guest must accept/dispute damage claim
  | 'DAMAGE_DISPUTE_HOST' // Host waiting for guest response to damage claim
  | 'COMPLETE' // Checkout done
  | 'NOT_READY'; // Status unknown or error

@Injectable({ providedIn: 'root' })
export class CheckoutService {
  private http = inject(HttpClient);
  private compressionService = inject(PhotoCompressionService);
  private geolocationService = inject(GeolocationService);

  private readonly apiUrl = `${environment.baseApiUrl}/bookings`;

  // ========== STATE ==========

  private _currentStatus = signal<CheckOutStatusDTO | null>(null);
  private _isLoading = signal(false);
  private _error = signal<string | null>(null);
  private _uploadProgress = signal<Map<string, PhotoUploadProgress>>(new Map());

  // Polling
  private pollingSubscription: any = null;

  // ========== PUBLIC SIGNALS ==========

  currentStatus = this._currentStatus.asReadonly();
  isLoading = this._isLoading.asReadonly();
  error = this._error.asReadonly();
  uploadProgress = this._uploadProgress.asReadonly();

  /**
   * Render decision signal for role-aware state machine.
   * Determines which UI view to show based on status and user role.
   */
  renderDecision = computed((): CheckoutRenderDecision => {
    const status = this._currentStatus();
    console.log('[CheckoutService] renderDecision - status:', status);

    if (this._isLoading() && !status) return 'LOADING';
    if (!status) {
      console.log('[CheckoutService] renderDecision -> NOT_READY (no status)');
      return 'NOT_READY';
    }

    const bookingStatus = status.status;
    console.log('[CheckoutService] bookingStatus:', bookingStatus);

    // Determine role
    const isHost = status.isHost;
    const isGuest = status.isGuest;
    console.log('[CheckoutService] isHost:', isHost, 'isGuest:', isGuest);

    // Trip not yet at checkout
    if (bookingStatus === 'IN_TRIP') {
      console.log('[CheckoutService] renderDecision -> IN_TRIP');
      return 'IN_TRIP';
    }

    // Checkout open - guest can submit
    if (bookingStatus === 'CHECKOUT_OPEN') {
      if (isGuest) {
        console.log('[CheckoutService] renderDecision -> GUEST_EDIT');
        return 'GUEST_EDIT';
      }
      if (isHost) {
        console.log('[CheckoutService] renderDecision -> HOST_WAITING');
        return 'HOST_WAITING';
      }
    }

    // Guest completed - waiting for host
    if (bookingStatus === 'CHECKOUT_GUEST_COMPLETE') {
      if (isGuest) {
        console.log('[CheckoutService] renderDecision -> GUEST_WAITING');
        return 'GUEST_WAITING';
      }
      if (isHost) {
        console.log('[CheckoutService] renderDecision -> HOST_CONFIRM');
        return 'HOST_CONFIRM';
      }
    }

    // Host completed or fully completed
    if (bookingStatus === 'CHECKOUT_HOST_COMPLETE' || bookingStatus === 'COMPLETED') {
      console.log('[CheckoutService] renderDecision -> COMPLETE');
      return 'COMPLETE';
    }

    // P1 FIX: Handle CHECKOUT_DAMAGE_DISPUTE status
    if (bookingStatus === 'CHECKOUT_DAMAGE_DISPUTE') {
      if (isGuest) {
        console.log('[CheckoutService] renderDecision -> DAMAGE_DISPUTE_GUEST');
        return 'DAMAGE_DISPUTE_GUEST';
      }
      if (isHost) {
        console.log('[CheckoutService] renderDecision -> DAMAGE_DISPUTE_HOST');
        return 'DAMAGE_DISPUTE_HOST';
      }
    }

    console.log('[CheckoutService] renderDecision -> NOT_READY (no matching condition)');
    console.log(
      '[CheckoutService] Unhandled status:',
      bookingStatus,
      'isHost:',
      isHost,
      'isGuest:',
      isGuest,
    );
    return 'NOT_READY';
  });

  // ========== STATUS METHODS ==========

  /**
   * Load checkout status for a booking.
   */
  loadStatus(bookingId: number): Observable<CheckOutStatusDTO> {
    this._isLoading.set(true);
    this._error.set(null);

    return this.http.get<CheckOutStatusDTO>(`${this.apiUrl}/${bookingId}/checkout/status`).pipe(
      tap((status) => {
        this._currentStatus.set(status);
        this._isLoading.set(false);
      }),
      catchError((err) => {
        this._isLoading.set(false);
        this._error.set(this.extractErrorMessage(err));
        return throwError(() => err);
      }),
    );
  }

  /**
   * Start polling for status updates.
   */
  startPolling(bookingId: number, intervalMs: number = 10000): void {
    this.stopPolling();

    this.pollingSubscription = interval(intervalMs)
      .pipe(
        takeWhile(() => {
          const status = this._currentStatus();
          // Stop polling when checkout is complete
          return !status || status.status !== 'COMPLETED';
        }),
        switchMap(() => this.loadStatus(bookingId).pipe(catchError(() => of(null)))),
      )
      .subscribe();
  }

  /**
   * Stop polling for status updates.
   */
  stopPolling(): void {
    if (this.pollingSubscription) {
      this.pollingSubscription.unsubscribe();
      this.pollingSubscription = null;
    }
  }

  // ========== CHECKOUT INITIATION ==========

  /**
   * Initiate checkout process.
   */
  initiateCheckout(bookingId: number, earlyReturn: boolean = false): Observable<CheckOutStatusDTO> {
    this._isLoading.set(true);

    return this.http
      .post<CheckOutStatusDTO>(
        `${this.apiUrl}/${bookingId}/checkout/initiate?earlyReturn=${earlyReturn}`,
        {},
      )
      .pipe(
        tap((status) => {
          this._currentStatus.set(status);
          this._isLoading.set(false);
        }),
        catchError((err) => {
          this._isLoading.set(false);
          this._error.set(this.extractErrorMessage(err));
          return throwError(() => err);
        }),
      );
  }

  // ========== PHOTO UPLOAD ==========

  /**
   * Upload a checkout photo with compression and progress tracking.
   */
  uploadPhoto(bookingId: number, file: File, slotId: string, photoType: CheckInPhotoType): void {
    // Create client-side preview URL for immediate display
    const previewUrl = URL.createObjectURL(file);

    // Initialize progress with preview
    this.updateProgress(slotId, {
      slotId,
      photoType,
      state: 'compressing',
      progress: 0,
      previewUrl,
    });

    // Compress the photo
    this.compressionService.compressImage(file, { targetSizeKB: 500 }).then(
      (compressed: { blob: Blob }) => {
        this.updateProgress(slotId, { state: 'uploading', progress: 10 });
        this.performUpload(bookingId, compressed.blob, slotId, photoType);
      },
      (err: Error) => {
        console.error('[CheckoutService] Compression failed:', err);
        this.updateProgress(slotId, {
          state: 'error',
          progress: 0,
          error: 'Kompresija nije uspela',
        });
      },
    );
  }

  private performUpload(
    bookingId: number,
    blob: Blob,
    slotId: string,
    photoType: CheckInPhotoType,
  ): void {
    const formData = new FormData();
    formData.append('file', blob, `${photoType}_${Date.now()}.jpg`);
    formData.append('photoType', photoType);
    formData.append('clientTimestamp', new Date().toISOString());

    // Add GPS if available
    const position = this.geolocationService.position();
    if (position) {
      formData.append('clientLatitude', position.latitude.toString());
      formData.append('clientLongitude', position.longitude.toString());
    }

    this.http
      .post<CheckInPhotoDTO>(`${this.apiUrl}/${bookingId}/checkout/guest/photos`, formData, {
        reportProgress: true,
        observe: 'events',
      })
      .subscribe({
        next: (event: HttpEvent<CheckInPhotoDTO>) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            const progress = Math.round((90 * event.loaded) / event.total) + 10;
            this.updateProgress(slotId, { state: 'uploading', progress });
          } else if (event.type === HttpEventType.Response) {
            this.updateProgress(slotId, {
              state: 'complete',
              progress: 100,
              result: event.body || undefined,
            });
          }
        },
        error: (err) => {
          console.error('[CheckoutService] Upload failed:', err);
          this.updateProgress(slotId, {
            state: 'error',
            progress: 0,
            error: this.extractErrorMessage(err),
          });
        },
      });
  }

  /**
   * Update upload progress for a slot.
   */
  private updateProgress(slotId: string, update: Partial<PhotoUploadProgress>): void {
    this._uploadProgress.update((map) => {
      const newMap = new Map(map);
      const existing = newMap.get(slotId) || {
        slotId,
        photoType: update.photoType || ('CHECKOUT_CUSTOM' as CheckInPhotoType),
        state: 'uploading' as const,
        progress: 0,
      };
      newMap.set(slotId, { ...existing, ...update });
      return newMap;
    });
  }

  /**
   * Clear photo progress for a slot.
   */
  clearPhotoProgress(slotId: string): void {
    this._uploadProgress.update((map) => {
      const newMap = new Map(map);
      newMap.delete(slotId);
      return newMap;
    });
  }

  // ========== GUEST WORKFLOW ==========

  /**
   * Submit guest checkout with end readings.
   */
  submitGuestCheckout(
    bookingId: number,
    endOdometer: number,
    endFuelLevel: number,
    comment?: string,
  ): Observable<CheckOutStatusDTO> {
    this._isLoading.set(true);

    const dto: GuestCheckOutSubmissionDTO = {
      bookingId,
      endOdometerReading: endOdometer,
      endFuelLevelPercent: endFuelLevel,
      conditionComment: comment,
    };

    // Add GPS if available
    const position = this.geolocationService.position();
    if (position) {
      dto.guestLatitude = position.latitude;
      dto.guestLongitude = position.longitude;
    }

    return this.http
      .post<CheckOutStatusDTO>(`${this.apiUrl}/${bookingId}/checkout/guest/complete`, dto)
      .pipe(
        tap((status) => {
          this._currentStatus.set(status);
          this._isLoading.set(false);
        }),
        catchError((err) => {
          this._isLoading.set(false);
          this._error.set(this.extractErrorMessage(err));
          return throwError(() => err);
        }),
      );
  }

  // ========== HOST WORKFLOW ==========

  /**
   * Upload a host checkout confirmation photo.
   */
  uploadHostPhoto(
    bookingId: number,
    file: File,
    slotId: string,
    photoType: CheckInPhotoType,
  ): void {
    // Create client-side preview URL for immediate display (same as guest upload)
    const previewUrl = URL.createObjectURL(file);

    this.updateProgress(slotId, {
      slotId,
      photoType,
      state: 'compressing',
      progress: 0,
      previewUrl,
    });

    this.compressionService.compressImage(file, { targetSizeKB: 500 }).then(
      (compressed: { blob: Blob }) => {
        this.updateProgress(slotId, { state: 'uploading', progress: 10 });
        this.performHostUpload(bookingId, compressed.blob, slotId, photoType);
      },
      (err: Error) => {
        this.updateProgress(slotId, {
          state: 'error',
          progress: 0,
          error: 'Kompresija nije uspela',
        });
      },
    );
  }

  private performHostUpload(
    bookingId: number,
    blob: Blob,
    slotId: string,
    photoType: CheckInPhotoType,
  ): void {
    const formData = new FormData();
    formData.append('file', blob, `${photoType}_${Date.now()}.jpg`);
    formData.append('photoType', photoType);
    formData.append('clientTimestamp', new Date().toISOString());

    const position = this.geolocationService.position();
    if (position) {
      formData.append('clientLatitude', position.latitude.toString());
      formData.append('clientLongitude', position.longitude.toString());
    }

    this.http
      .post<CheckInPhotoDTO>(`${this.apiUrl}/${bookingId}/checkout/host/photos`, formData, {
        reportProgress: true,
        observe: 'events',
      })
      .subscribe({
        next: (event: HttpEvent<CheckInPhotoDTO>) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            const progress = Math.round((90 * event.loaded) / event.total) + 10;
            this.updateProgress(slotId, { state: 'uploading', progress });
          } else if (event.type === HttpEventType.Response) {
            this.updateProgress(slotId, {
              state: 'complete',
              progress: 100,
              result: event.body || undefined,
            });
          }
        },
        error: (err) => {
          this.updateProgress(slotId, {
            state: 'error',
            progress: 0,
            error: this.extractErrorMessage(err),
          });
        },
      });
  }

  /**
   * Host confirms checkout (with or without damage report).
   */
  confirmHostCheckout(
    bookingId: number,
    conditionAccepted: boolean,
    damageReport?: {
      description: string;
      estimatedCostRsd: number;
      photoIds?: number[];
    },
    notes?: string,
  ): Observable<CheckOutStatusDTO> {
    this._isLoading.set(true);

    const dto: HostCheckOutConfirmationDTO = {
      bookingId,
      conditionAccepted,
      newDamageReported: !!damageReport,
      damageDescription: damageReport?.description,
      estimatedDamageCostRsd: damageReport?.estimatedCostRsd,
      damagePhotoIds: damageReport?.photoIds,
      notes,
    };

    const position = this.geolocationService.position();
    if (position) {
      dto.hostLatitude = position.latitude;
      dto.hostLongitude = position.longitude;
    }

    return this.http
      .post<CheckOutStatusDTO>(`${this.apiUrl}/${bookingId}/checkout/host/confirm`, dto)
      .pipe(
        tap((status) => {
          this._currentStatus.set(status);
          this._isLoading.set(false);
        }),
        catchError((err) => {
          this._isLoading.set(false);
          this._error.set(this.extractErrorMessage(err));
          return throwError(() => err);
        }),
      );
  }

  /**
   * Guest accepts a checkout damage claim.
   * Deposit will be captured for damage charges and checkout completes.
   */
  acceptDamageClaim(bookingId: number): Observable<any> {
    this._isLoading.set(true);
    return this.http
      .post<any>(`${this.apiUrl}/${bookingId}/checkout/damage/accept`, {})
      .pipe(
        tap(() => {
          this._isLoading.set(false);
          // Reload status after acceptance
          this.loadStatus(bookingId).subscribe();
        }),
        catchError((err) => {
          this._isLoading.set(false);
          this._error.set(this.extractErrorMessage(err));
          return throwError(() => err);
        }),
      );
  }

  /**
   * Guest disputes a checkout damage claim.
   * Escalates to admin for resolution. Deposit remains held.
   */
  disputeDamageClaim(bookingId: number, reason: string): Observable<any> {
    this._isLoading.set(true);
    return this.http
      .post<any>(`${this.apiUrl}/${bookingId}/checkout/damage/dispute`, { reason })
      .pipe(
        tap(() => {
          this._isLoading.set(false);
          // Reload status after dispute
          this.loadStatus(bookingId).subscribe();
        }),
        catchError((err) => {
          this._isLoading.set(false);
          this._error.set(this.extractErrorMessage(err));
          return throwError(() => err);
        }),
      );
  }

  // ========== HELPERS ==========

  private extractErrorMessage(error: any): string {
    if (error?.error?.message) return error.error.message;
    if (error?.error?.messagesr) return error.error.messagesr;
    if (error?.message) return error.message;
    return 'Došlo je do greške. Molimo pokušajte ponovo.';
  }

  /**
   * Reset service state.
   */
  reset(): void {
    this.stopPolling();
    this._currentStatus.set(null);
    this._isLoading.set(false);
    this._error.set(null);
    this._uploadProgress.set(new Map());
  }
}
