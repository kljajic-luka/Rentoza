/**
 * Photo Guidance Service
 *
 * Provides guided photo capture instructions and sequence validation.
 * Communicates with the backend PhotoGuidanceController.
 *
 * @since Enterprise Upgrade Phase 2
 */

import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, catchError, tap, map } from 'rxjs';
import { environment } from '@environments/environment';
import { CheckInPhotoType } from '@core/models/check-in.model';
import {
  PhotoGuidanceDTO,
  PhotoSequenceValidationDTO,
  GuestCheckInPhotoSubmissionDTO,
  GuestCheckInPhotoResponseDTO,
  PhotoCaptureStatus,
  REQUIRED_GUEST_CHECKIN_TYPES,
  REQUIRED_HOST_CHECKOUT_TYPES,
} from '@core/models/photo-guidance.model';

@Injectable({ providedIn: 'root' })
export class PhotoGuidanceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.baseApiUrl}/checkin/photo-guidance`;

  // ========== REACTIVE STATE ==========

  /** Current photo sequence being captured */
  private readonly _captureSequence = signal<PhotoGuidanceDTO[]>([]);

  /** Current photo index in the sequence */
  private readonly _currentIndex = signal(0);

  /** Status of each photo in the capture process */
  private readonly _captureStatuses = signal<Map<CheckInPhotoType, PhotoCaptureStatus>>(new Map());

  /** Whether guided capture is active */
  private readonly _isCapturing = signal(false);

  /** Error message if any */
  private readonly _error = signal<string | null>(null);

  // Public readonly signals
  readonly captureSequence = this._captureSequence.asReadonly();
  readonly currentIndex = this._currentIndex.asReadonly();
  readonly captureStatuses = this._captureStatuses.asReadonly();
  readonly isCapturing = this._isCapturing.asReadonly();
  readonly error = this._error.asReadonly();

  // Computed signals
  readonly currentGuidance = computed(() => {
    const sequence = this._captureSequence();
    const index = this._currentIndex();
    return sequence[index] ?? null;
  });

  readonly progress = computed(() => {
    const statuses = this._captureStatuses();
    const required = REQUIRED_GUEST_CHECKIN_TYPES.length;
    const completed = Array.from(statuses.values()).filter(
      (s) => s.status === 'uploaded' || s.status === 'captured'
    ).length;
    return Math.round((completed / required) * 100);
  });

  readonly isComplete = computed(() => {
    const statuses = this._captureStatuses();
    return REQUIRED_GUEST_CHECKIN_TYPES.every((type) => {
      const status = statuses.get(type);
      return status?.status === 'uploaded';
    });
  });

  readonly canProceed = computed(() => {
    const current = this.currentGuidance();
    if (!current) return false;
    const status = this._captureStatuses().get(current.photoType);
    return status?.status === 'captured' || status?.status === 'uploaded';
  });

  // ========== API METHODS ==========

  /**
   * Get guidance for a specific photo type.
   */
  getGuidance(photoType: CheckInPhotoType): Observable<PhotoGuidanceDTO> {
    return this.http.get<PhotoGuidanceDTO>(`${this.baseUrl}/${photoType}`).pipe(
      catchError((err) => {
        console.error('Failed to fetch photo guidance', err);
        this._error.set('Neuspešno učitavanje uputstava');
        throw err;
      })
    );
  }

  /**
   * Get all guest check-in guidance in sequence order.
   */
  getGuestCheckInSequence(): Observable<PhotoGuidanceDTO[]> {
    return this.http.get<PhotoGuidanceDTO[]>(`${this.baseUrl}/guest-sequence`).pipe(
      map((sequence) => this.ensureSilhouetteUrls(sequence)),
      tap((sequence) => {
        this._captureSequence.set(sequence);
        this.initializeCaptureStatuses(sequence);
      }),
      catchError((err) => {
        console.error('Failed to fetch guest check-in sequence', err);
        // Fallback to local guidance if backend unavailable
        console.warn('[PhotoGuidance] Using local fallback guidance');
        const fallback = this.getLocalFallbackGuidance();
        this._captureSequence.set(fallback);
        this.initializeCaptureStatuses(fallback);
        return of(fallback);
      })
    );
  }

  /**
   * Get all host check-in guidance in sequence order.
   */
  getHostCheckInSequence(): Observable<PhotoGuidanceDTO[]> {
    return this.http.get<PhotoGuidanceDTO[]>(`${this.baseUrl}/host-sequence`).pipe(
      map((sequence) => this.ensureSilhouetteUrls(sequence)),
      tap((sequence) => {
        this._captureSequence.set(sequence);
        this.initializeCaptureStatuses(sequence);
      }),
      catchError((err) => {
        console.error('Failed to fetch host check-in sequence', err);
        // Fallback to local guidance if backend unavailable
        console.warn('[PhotoGuidance] Using local fallback host guidance');
        const fallback = this.getLocalFallbackHostGuidance();
        this._captureSequence.set(fallback);
        this.initializeCaptureStatuses(fallback);
        return of(fallback);
      })
    );
  }

  /**
   * Get all checkout guidance in sequence order.
   */
  getCheckoutSequence(): Observable<PhotoGuidanceDTO[]> {
    return this.http.get<PhotoGuidanceDTO[]>(`${this.baseUrl}/checkout-sequence`).pipe(
      map((sequence) => this.ensureSilhouetteUrls(sequence)),
      catchError((err) => {
        console.error('Failed to fetch checkout sequence', err);
        console.warn('[PhotoGuidance] Using local fallback checkout guidance');
        return of(this.getLocalFallbackCheckoutGuidance());
      })
    );
  }

  /**
   * Validate that submitted photo types meet requirements.
   */
  validateSequence(
    sequenceType: 'guest-checkin' | 'host-checkout',
    submittedTypes: CheckInPhotoType[]
  ): Observable<PhotoSequenceValidationDTO> {
    return this.http.post<PhotoSequenceValidationDTO>(
      `${this.baseUrl}/validate?sequenceType=${sequenceType}`,
      submittedTypes
    );
  }

  // ========== CAPTURE FLOW METHODS ==========

  /**
   * Start guided photo capture for guest check-in.
   */
  startGuestCheckInCapture(): Observable<PhotoGuidanceDTO[]> {
    this._isCapturing.set(true);
    this._currentIndex.set(0);
    this._error.set(null);

    return this.getGuestCheckInSequence().pipe(
      tap(() => {
        console.log('[PhotoGuidance] Started guest check-in capture');
      })
    );
  }

  /**
   * Start guided photo capture for host check-in.
   */
  startHostCheckInCapture(): Observable<PhotoGuidanceDTO[]> {
    this._isCapturing.set(true);
    this._currentIndex.set(0);
    this._error.set(null);

    return this.getHostCheckInSequence().pipe(
      tap((sequence) => {
        console.log('[PhotoGuidance] Started host check-in capture, sequence:', sequence);
        console.log('[PhotoGuidance] First item silhouetteUrl:', sequence[0]?.silhouetteUrl);
      })
    );
  }

  /**
   * Start guided photo capture for checkout.
   */
  startCheckoutCapture(): Observable<PhotoGuidanceDTO[]> {
    this._isCapturing.set(true);
    this._currentIndex.set(0);
    this._error.set(null);

    return this.getCheckoutSequence().pipe(
      tap((sequence) => {
        this._captureSequence.set(sequence);
        this.initializeCaptureStatuses(sequence);
        console.log('[PhotoGuidance] Started checkout capture');
      })
    );
  }

  /**
   * Record that a photo has been captured locally.
   */
  recordCapture(photoType: CheckInPhotoType, blob: Blob, previewUrl: string): void {
    const statuses = new Map(this._captureStatuses());
    statuses.set(photoType, {
      photoType,
      status: 'captured',
      blob,
      localPreviewUrl: previewUrl,
    });
    this._captureStatuses.set(statuses);

    console.log(`[PhotoGuidance] Photo captured: ${photoType}`);
  }

  /**
   * Record that a photo upload has started.
   */
  recordUploadStarted(photoType: CheckInPhotoType): void {
    const statuses = new Map(this._captureStatuses());
    const current = statuses.get(photoType);
    if (current) {
      statuses.set(photoType, { ...current, status: 'uploading' });
      this._captureStatuses.set(statuses);
    }
  }

  /**
   * Record that a photo upload completed successfully.
   */
  recordUploadSuccess(photoType: CheckInPhotoType, photoId: number): void {
    const statuses = new Map(this._captureStatuses());
    const current = statuses.get(photoType);
    if (current) {
      statuses.set(photoType, {
        ...current,
        status: 'uploaded',
        uploadedPhotoId: photoId,
      });
      this._captureStatuses.set(statuses);
    }

    console.log(`[PhotoGuidance] Photo uploaded: ${photoType}, id=${photoId}`);
  }

  /**
   * Record that a photo was rejected.
   */
  recordRejection(photoType: CheckInPhotoType, reason: string): void {
    const statuses = new Map(this._captureStatuses());
    statuses.set(photoType, {
      photoType,
      status: 'rejected',
      rejectionReason: reason,
    });
    this._captureStatuses.set(statuses);

    console.warn(`[PhotoGuidance] Photo rejected: ${photoType}, reason=${reason}`);
  }

  /**
   * Move to the next photo in the sequence.
   */
  nextPhoto(): void {
    const current = this._currentIndex();
    const sequence = this._captureSequence();

    if (current < sequence.length - 1) {
      this._currentIndex.set(current + 1);
      console.log(`[PhotoGuidance] Moving to photo ${current + 2}/${sequence.length}`);
    }
  }

  /**
   * Move to the previous photo in the sequence.
   */
  previousPhoto(): void {
    const current = this._currentIndex();
    if (current > 0) {
      this._currentIndex.set(current - 1);
    }
  }

  /**
   * Jump to a specific photo in the sequence.
   */
  goToPhoto(index: number): void {
    const sequence = this._captureSequence();
    if (index >= 0 && index < sequence.length) {
      this._currentIndex.set(index);
    }
  }

  /**
   * Retake the current photo.
   */
  retakePhoto(): void {
    const current = this.currentGuidance();
    if (current) {
      const statuses = new Map(this._captureStatuses());
      statuses.set(current.photoType, {
        photoType: current.photoType,
        status: 'pending',
      });
      this._captureStatuses.set(statuses);
    }
  }

  /**
   * End the capture session.
   */
  endCapture(): void {
    this._isCapturing.set(false);
    console.log('[PhotoGuidance] Capture session ended');
  }

  /**
   * Reset the capture state.
   */
  reset(): void {
    this._captureSequence.set([]);
    this._currentIndex.set(0);
    this._captureStatuses.set(new Map());
    this._isCapturing.set(false);
    this._error.set(null);
  }

  /**
   * Alias for reset() - resets the entire capture session.
   * Called when user cancels the capture flow.
   */
  resetCapture(): void {
    this.reset();
    console.log('[PhotoGuidance] Capture session reset');
  }

  /**
   * Get all captured photos as blobs for submission.
   */
  getCapturedPhotos(): Map<CheckInPhotoType, Blob> {
    const result = new Map<CheckInPhotoType, Blob>();
    const statuses = this._captureStatuses();

    statuses.forEach((status, photoType) => {
      if (status.blob && (status.status === 'captured' || status.status === 'uploaded')) {
        result.set(photoType, status.blob);
      }
    });

    return result;
  }

  /**
   * Get photo types that are still pending capture.
   */
  getPendingTypes(): CheckInPhotoType[] {
    const statuses = this._captureStatuses();
    return REQUIRED_GUEST_CHECKIN_TYPES.filter((type) => {
      const status = statuses.get(type);
      return !status || status.status === 'pending' || status.status === 'rejected';
    });
  }

  // ========== HELPER METHODS ==========

  private initializeCaptureStatuses(sequence: PhotoGuidanceDTO[]): void {
    const statuses = new Map<CheckInPhotoType, PhotoCaptureStatus>();

    sequence.forEach((guidance) => {
      statuses.set(guidance.photoType, {
        photoType: guidance.photoType,
        status: 'pending',
      });
    });

    this._captureStatuses.set(statuses);
  }

  /**
   * Get display name for a photo type based on current language.
   */
  getPhotoTypeName(photoType: CheckInPhotoType, language: 'sr' | 'en' = 'sr'): string {
    const names: Record<CheckInPhotoType, { sr: string; en: string }> = {
      GUEST_EXTERIOR_FRONT: { sr: 'Prednja strana', en: 'Front' },
      GUEST_EXTERIOR_REAR: { sr: 'Zadnja strana', en: 'Rear' },
      GUEST_EXTERIOR_LEFT: { sr: 'Leva strana', en: 'Left Side' },
      GUEST_EXTERIOR_RIGHT: { sr: 'Desna strana', en: 'Right Side' },
      GUEST_INTERIOR_DASHBOARD: { sr: 'Kontrolna tabla', en: 'Dashboard' },
      GUEST_INTERIOR_REAR: { sr: 'Zadnja sedišta', en: 'Rear Seats' },
      GUEST_ODOMETER: { sr: 'Kilometraža', en: 'Odometer' },
      GUEST_FUEL_GAUGE: { sr: 'Gorivo', en: 'Fuel' },
      HOST_EXTERIOR_FRONT: { sr: 'Prednja strana', en: 'Front' },
      HOST_EXTERIOR_REAR: { sr: 'Zadnja strana', en: 'Rear' },
      HOST_EXTERIOR_LEFT: { sr: 'Leva strana', en: 'Left Side' },
      HOST_EXTERIOR_RIGHT: { sr: 'Desna strana', en: 'Right Side' },
      HOST_INTERIOR_DASHBOARD: { sr: 'Kontrolna tabla', en: 'Dashboard' },
      HOST_INTERIOR_REAR: { sr: 'Zadnja sedišta', en: 'Rear Seats' },
      HOST_ODOMETER: { sr: 'Kilometraža', en: 'Odometer' },
      HOST_FUEL_GAUGE: { sr: 'Gorivo', en: 'Fuel' },
      HOST_DAMAGE_PREEXISTING: { sr: 'Postojeće oštećenje', en: 'Pre-existing Damage' },
      HOST_CUSTOM: { sr: 'Dodatna fotografija', en: 'Additional Photo' },
      GUEST_DAMAGE_NOTED: { sr: 'Primećeno oštećenje', en: 'Noted Damage' },
      GUEST_HOTSPOT: { sr: 'Označeno mesto', en: 'Hotspot' },
      GUEST_CUSTOM: { sr: 'Dodatna fotografija', en: 'Additional Photo' },
      CHECKOUT_EXTERIOR_FRONT: { sr: 'Prednja strana', en: 'Front' },
      CHECKOUT_EXTERIOR_REAR: { sr: 'Zadnja strana', en: 'Rear' },
      CHECKOUT_EXTERIOR_LEFT: { sr: 'Leva strana', en: 'Left Side' },
      CHECKOUT_EXTERIOR_RIGHT: { sr: 'Desna strana', en: 'Right Side' },
      CHECKOUT_INTERIOR_DASHBOARD: { sr: 'Kontrolna tabla', en: 'Dashboard' },
      CHECKOUT_INTERIOR_REAR: { sr: 'Zadnja sedišta', en: 'Rear Seats' },
      CHECKOUT_ODOMETER: { sr: 'Kilometraža', en: 'Odometer' },
      CHECKOUT_FUEL_GAUGE: { sr: 'Gorivo', en: 'Fuel' },
      CHECKOUT_DAMAGE_NEW: { sr: 'Novo oštećenje', en: 'New Damage' },
      CHECKOUT_CUSTOM: { sr: 'Dodatna fotografija', en: 'Additional Photo' },
      HOST_CHECKOUT_CONFIRMATION: { sr: 'Potvrda povratka', en: 'Return Confirmation' },
      HOST_CHECKOUT_DAMAGE_EVIDENCE: { sr: 'Dokaz oštećenja', en: 'Damage Evidence' },
      HOST_CHECKOUT_EXTERIOR_FRONT: { sr: 'Prednja strana', en: 'Front' },
      HOST_CHECKOUT_EXTERIOR_REAR: { sr: 'Zadnja strana', en: 'Rear' },
      HOST_CHECKOUT_EXTERIOR_LEFT: { sr: 'Leva strana', en: 'Left Side' },
      HOST_CHECKOUT_EXTERIOR_RIGHT: { sr: 'Desna strana', en: 'Right Side' },
      HOST_CHECKOUT_INTERIOR_DASHBOARD: { sr: 'Kontrolna tabla', en: 'Dashboard' },
      HOST_CHECKOUT_INTERIOR_REAR: { sr: 'Zadnja sedišta', en: 'Rear Seats' },
      HOST_CHECKOUT_ODOMETER: { sr: 'Kilometraža', en: 'Odometer' },
      HOST_CHECKOUT_FUEL_GAUGE: { sr: 'Gorivo', en: 'Fuel' },
      HOST_CHECKOUT_CUSTOM: { sr: 'Dodatna fotografija', en: 'Additional Photo' },
    };

    return names[photoType]?.[language] ?? photoType;
  }

  // ========== SILHOUETTE URL HELPERS ==========

  /**
   * Ensure all guidance items have valid silhouette URLs.
   * If backend didn't provide one, or provided invalid one, generate locally.
   */
  private ensureSilhouetteUrls(sequence: PhotoGuidanceDTO[]): PhotoGuidanceDTO[] {
    return sequence.map((guidance) => {
      // Always regenerate silhouette URL to ensure correct path
      // Backend may return wrong filename or missing path
      const localUrl = this.generateSilhouetteUrl(guidance.photoType);

      // Use local URL if:
      // 1. Backend didn't provide any
      // 2. Backend URL doesn't start with / (invalid)
      // 3. Backend URL contains 'host_' (non-existent files)
      const useLocalUrl =
        !guidance.silhouetteUrl ||
        !guidance.silhouetteUrl.startsWith('/') ||
        guidance.silhouetteUrl.includes('host_');

      return {
        ...guidance,
        silhouetteUrl: useLocalUrl ? localUrl : guidance.silhouetteUrl,
      };
    });
  }

  /**
   * Generate silhouette URL for a photo type.
   * Maps to actual SVG files in /assets/silhouettes/
   */
  private generateSilhouetteUrl(photoType: CheckInPhotoType): string {
    const basePath = '/assets/silhouettes';

    // Map photo types to their silhouette files
    const silhouetteMap: Partial<Record<CheckInPhotoType, string>> = {
      GUEST_EXTERIOR_FRONT: 'car-guest_exterior_front.svg',
      GUEST_EXTERIOR_LEFT: 'car-guest_exterior_left.svg',
      GUEST_EXTERIOR_RIGHT: 'car-guest_exterior_right.svg',
      GUEST_EXTERIOR_REAR: 'car-guest_exterior_rear.svg',
      GUEST_INTERIOR_DASHBOARD: 'car-guest_interior_dashboard.svg',
      GUEST_INTERIOR_REAR: 'car-guest_interior_rear.svg',
      GUEST_ODOMETER: 'car-guest_odometer.svg',
      GUEST_FUEL_GAUGE: 'car-guest_fuel_gauge.svg',
      // Host check-in uses same silhouettes as guest
      HOST_EXTERIOR_FRONT: 'car-guest_exterior_front.svg',
      HOST_EXTERIOR_LEFT: 'car-guest_exterior_left.svg',
      HOST_EXTERIOR_RIGHT: 'car-guest_exterior_right.svg',
      HOST_EXTERIOR_REAR: 'car-guest_exterior_rear.svg',
      HOST_INTERIOR_DASHBOARD: 'car-guest_interior_dashboard.svg',
      HOST_INTERIOR_REAR: 'car-guest_interior_rear.svg',
      HOST_ODOMETER: 'car-guest_odometer.svg',
      HOST_FUEL_GAUGE: 'car-guest_fuel_gauge.svg',
      // Host checkout uses same silhouettes
      HOST_CHECKOUT_EXTERIOR_FRONT: 'car-guest_exterior_front.svg',
      HOST_CHECKOUT_EXTERIOR_LEFT: 'car-guest_exterior_left.svg',
      HOST_CHECKOUT_EXTERIOR_RIGHT: 'car-guest_exterior_right.svg',
      HOST_CHECKOUT_EXTERIOR_REAR: 'car-guest_exterior_rear.svg',
      HOST_CHECKOUT_INTERIOR_DASHBOARD: 'car-guest_interior_dashboard.svg',
      HOST_CHECKOUT_INTERIOR_REAR: 'car-guest_interior_rear.svg',
      HOST_CHECKOUT_ODOMETER: 'car-guest_odometer.svg',
      HOST_CHECKOUT_FUEL_GAUGE: 'car-guest_fuel_gauge.svg',
    };

    const filename = silhouetteMap[photoType];
    return filename ? `${basePath}/${filename}` : '';
  }

  /**
   * Local fallback guidance when backend is unavailable.
   */
  private getLocalFallbackGuidance(): PhotoGuidanceDTO[] {
    const basePath = '/assets/silhouettes';

    return [
      {
        photoType: 'GUEST_EXTERIOR_FRONT',
        displayName: 'Prednja strana',
        displayNameEn: 'Front Exterior',
        sequenceOrder: 1,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr:
          'Fotografišite prednju stranu vozila sa 5-8 metara udaljenosti. Registarska tablica mora biti jasno vidljiva.',
        instructionsEn:
          'Photograph the front of the vehicle from 5-8 meters away. License plate must be clearly visible.',
        silhouetteUrl: `${basePath}/car-guest_exterior_front.svg`,
        expectedAngle: 'FRONT_FACING',
        estimatedDuration: 30,
        commonMistakesSr: ['Tablica nije vidljiva', 'Preblizu vozilu', 'Loše osvetljenje'],
        commonMistakesEn: ['License plate not visible', 'Too close', 'Poor lighting'],
        tipsSr: ['Stanite na ravnoj površini', 'Izbegavajte jake senke'],
        tipsEn: ['Stand on flat surface', 'Avoid harsh shadows'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: [
          'Tablica jasno vidljiva',
          'Prednja svetla vidljiva',
          'Celo vozilo u kadru',
        ],
        visibilityChecklistEn: [
          'License plate visible',
          'Headlights visible',
          'Entire vehicle in frame',
        ],
      },
      {
        photoType: 'GUEST_EXTERIOR_LEFT',
        displayName: 'Leva strana',
        displayNameEn: 'Left Side',
        sequenceOrder: 2,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr:
          'Fotografišite levu stranu vozila u punom profilu. Stanite paralelno sa vozilom na 5-8 metara.',
        instructionsEn: 'Photograph the full left side profile of the vehicle.',
        silhouetteUrl: `${basePath}/car-guest_exterior_left.svg`,
        expectedAngle: 'LEFT_PROFILE',
        estimatedDuration: 30,
        commonMistakesSr: ['Ugao nije paralelan', 'Deo vozila je odsečen'],
        commonMistakesEn: ['Angle is not parallel', 'Part of vehicle is cut off'],
        tipsSr: ['Stanite paralelno'],
        tipsEn: ['Stand parallel'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: [
          'Sva 4 točka vidljiva',
          'Retrovizor vidljiv',
          'Bočni deo u celosti',
        ],
        visibilityChecklistEn: [
          'All 4 wheels visible',
          'Side mirror visible',
          'Complete side profile',
        ],
      },
      {
        photoType: 'GUEST_EXTERIOR_RIGHT',
        displayName: 'Desna strana',
        displayNameEn: 'Right Side',
        sequenceOrder: 3,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr: 'Fotografišite desnu stranu vozila u punom profilu.',
        instructionsEn: 'Photograph the full right side profile.',
        silhouetteUrl: `${basePath}/car-guest_exterior_right.svg`,
        expectedAngle: 'RIGHT_PROFILE',
        estimatedDuration: 30,
        commonMistakesSr: ['Ugao nije paralelan', 'Deo vozila je odsečen'],
        commonMistakesEn: ['Angle is not parallel', 'Part cut off'],
        tipsSr: ['Stanite paralelno'],
        tipsEn: ['Stand parallel'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Sva 4 točka vidljiva', 'Bočni deo u celosti'],
        visibilityChecklistEn: ['All 4 wheels visible', 'Complete side profile'],
      },
      {
        photoType: 'GUEST_EXTERIOR_REAR',
        displayName: 'Zadnja strana',
        displayNameEn: 'Rear Exterior',
        sequenceOrder: 4,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr:
          'Fotografišite zadnju stranu vozila sa 5-8 metara udaljenosti. Registarska tablica mora biti jasno vidljiva.',
        instructionsEn: 'Photograph the rear of the vehicle from 5-8 meters away.',
        silhouetteUrl: `${basePath}/car-guest_exterior_rear.svg`,
        expectedAngle: 'REAR_FACING',
        estimatedDuration: 30,
        commonMistakesSr: ['Tablica nije vidljiva', 'Prtljažnik je otvoren'],
        commonMistakesEn: ['License plate not visible', 'Trunk is open'],
        tipsSr: ['Proverite da je prtljažnik zatvoren'],
        tipsEn: ['Make sure trunk is closed'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Tablica jasno vidljiva', 'Zadnja svetla vidljiva'],
        visibilityChecklistEn: ['License plate visible', 'Tail lights visible'],
      },
      {
        photoType: 'GUEST_INTERIOR_DASHBOARD',
        displayName: 'Kontrolna tabla',
        displayNameEn: 'Dashboard',
        sequenceOrder: 5,
        totalInCategory: 2,
        category: 'interior',
        instructionsSr:
          'Fotografišite kontrolnu tablu iz pozicije vozača. Volan, instrument tabla i centralna konzola moraju biti vidljivi.',
        instructionsEn: "Photograph the dashboard from the driver's position.",
        silhouetteUrl: `${basePath}/car-guest_interior_dashboard.svg`,
        expectedAngle: 'DASHBOARD',
        estimatedDuration: 20,
        commonMistakesSr: ['Kilometraža nije vidljiva', 'Previše tamno'],
        commonMistakesEn: ['Odometer not visible', 'Too dark'],
        tipsSr: ['Otvorite vrata za bolje osvetljenje'],
        tipsEn: ['Open doors for better lighting'],
        required: true,
        visibilityChecklistSr: ['Volan vidljiv', 'Instrument tabla vidljiva'],
        visibilityChecklistEn: ['Steering wheel visible', 'Instrument panel visible'],
      },
      {
        photoType: 'GUEST_INTERIOR_REAR',
        displayName: 'Zadnja sedišta',
        displayNameEn: 'Rear Seats',
        sequenceOrder: 6,
        totalInCategory: 2,
        category: 'interior',
        instructionsSr: 'Fotografišite zadnja sedišta iz prednjeg dela vozila.',
        instructionsEn: 'Photograph the rear seats from the front.',
        silhouetteUrl: `${basePath}/car-guest_interior_rear.svg`,
        expectedAngle: 'REAR_SEATS',
        estimatedDuration: 20,
        commonMistakesSr: ['Deo sedišta nije vidljiv', 'Previše tamno'],
        commonMistakesEn: ['Part of seats not visible', 'Too dark'],
        tipsSr: ['Uklonite lične predmete'],
        tipsEn: ['Remove personal items'],
        required: true,
        visibilityChecklistSr: ['Oba zadnja sedišta vidljiva', 'Pod vidljiv'],
        visibilityChecklistEn: ['Both rear seats visible', 'Floor visible'],
      },
      {
        photoType: 'GUEST_ODOMETER',
        displayName: 'Kilometraža',
        displayNameEn: 'Odometer',
        sequenceOrder: 7,
        totalInCategory: 2,
        category: 'reading',
        instructionsSr:
          'Fotografišite kilometražu izbliza. Brojke moraju biti jasno čitljive. Motor mora biti upaljen.',
        instructionsEn: 'Photograph the odometer close-up. Numbers must be clearly readable.',
        silhouetteUrl: `${basePath}/car-guest_odometer.svg`,
        expectedAngle: 'ODOMETER_CLOSEUP',
        estimatedDuration: 15,
        commonMistakesSr: ['Brojke nisu čitljive', 'Odraz na staklu', 'Motor ugašen'],
        commonMistakesEn: ['Numbers not readable', 'Reflection on glass', 'Engine off'],
        tipsSr: ['Upalite motor', 'Fokusirajte na brojke'],
        tipsEn: ['Turn on engine', 'Focus on numbers'],
        required: true,
        visibilityChecklistSr: ['Sve cifre jasno vidljive', 'Bez odraza'],
        visibilityChecklistEn: ['All digits clearly visible', 'No reflections'],
      },
      {
        photoType: 'GUEST_FUEL_GAUGE',
        displayName: 'Pokazivač goriva',
        displayNameEn: 'Fuel Gauge',
        sequenceOrder: 8,
        totalInCategory: 2,
        category: 'reading',
        instructionsSr:
          'Fotografišite pokazivač goriva izbliza. Nivo goriva mora biti jasno vidljiv.',
        instructionsEn: 'Photograph the fuel gauge close-up. Fuel level must be clearly visible.',
        silhouetteUrl: `${basePath}/car-guest_fuel_gauge.svg`,
        expectedAngle: 'FUEL_GAUGE_CLOSEUP',
        estimatedDuration: 15,
        commonMistakesSr: ['Nivo nije vidljiv', 'Odraz na staklu'],
        commonMistakesEn: ['Level not visible', 'Reflection on glass'],
        tipsSr: ['Izbegavajte odsjaj'],
        tipsEn: ['Avoid glare'],
        required: true,
        visibilityChecklistSr: ['Kazaljka jasno vidljiva', 'Skala čitljiva'],
        visibilityChecklistEn: ['Needle clearly visible', 'Scale readable'],
      },
    ];
  }

  /**
   * Local fallback host check-in guidance when backend is unavailable.
   */
  private getLocalFallbackHostGuidance(): PhotoGuidanceDTO[] {
    const basePath = '/assets/silhouettes';

    return [
      {
        photoType: 'HOST_EXTERIOR_FRONT',
        displayName: 'Prednja strana',
        displayNameEn: 'Front Exterior',
        sequenceOrder: 1,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr:
          'Fotografišite prednju stranu vozila sa 5-8 metara udaljenosti. Registarska tablica mora biti jasno vidljiva.',
        instructionsEn:
          'Photograph the front of the vehicle from 5-8 meters away. License plate must be clearly visible.',
        silhouetteUrl: `${basePath}/car-guest_exterior_front.svg`,
        expectedAngle: 'FRONT_FACING',
        estimatedDuration: 30,
        commonMistakesSr: ['Tablica nije vidljiva', 'Preblizu vozilu', 'Loše osvetljenje'],
        commonMistakesEn: ['License plate not visible', 'Too close', 'Poor lighting'],
        tipsSr: ['Stanite na ravnoj površini', 'Izbegavajte jake senke'],
        tipsEn: ['Stand on flat surface', 'Avoid harsh shadows'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: [
          'Tablica jasno vidljiva',
          'Prednja svetla vidljiva',
          'Celo vozilo u kadru',
        ],
        visibilityChecklistEn: [
          'License plate visible',
          'Headlights visible',
          'Entire vehicle in frame',
        ],
      },
      {
        photoType: 'HOST_EXTERIOR_LEFT',
        displayName: 'Leva strana',
        displayNameEn: 'Left Side',
        sequenceOrder: 2,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr:
          'Fotografišite levu stranu vozila u punom profilu. Stanite paralelno sa vozilom na 5-8 metara.',
        instructionsEn: 'Photograph the full left side profile of the vehicle.',
        silhouetteUrl: `${basePath}/car-guest_exterior_left.svg`,
        expectedAngle: 'LEFT_PROFILE',
        estimatedDuration: 30,
        commonMistakesSr: ['Ugao nije paralelan', 'Deo vozila je odsečen'],
        commonMistakesEn: ['Angle is not parallel', 'Part of vehicle is cut off'],
        tipsSr: ['Stanite paralelno'],
        tipsEn: ['Stand parallel'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: [
          'Sva 4 točka vidljiva',
          'Retrovizor vidljiv',
          'Bočni deo u celosti',
        ],
        visibilityChecklistEn: [
          'All 4 wheels visible',
          'Side mirror visible',
          'Complete side profile',
        ],
      },
      {
        photoType: 'HOST_EXTERIOR_RIGHT',
        displayName: 'Desna strana',
        displayNameEn: 'Right Side',
        sequenceOrder: 3,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr: 'Fotografišite desnu stranu vozila u punom profilu.',
        instructionsEn: 'Photograph the full right side profile.',
        silhouetteUrl: `${basePath}/car-guest_exterior_right.svg`,
        expectedAngle: 'RIGHT_PROFILE',
        estimatedDuration: 30,
        commonMistakesSr: ['Ugao nije paralelan', 'Deo vozila je odsečen'],
        commonMistakesEn: ['Angle is not parallel', 'Part cut off'],
        tipsSr: ['Stanite paralelno'],
        tipsEn: ['Stand parallel'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Sva 4 točka vidljiva', 'Bočni deo u celosti'],
        visibilityChecklistEn: ['All 4 wheels visible', 'Complete side profile'],
      },
      {
        photoType: 'HOST_EXTERIOR_REAR',
        displayName: 'Zadnja strana',
        displayNameEn: 'Rear Exterior',
        sequenceOrder: 4,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr:
          'Fotografišite zadnju stranu vozila sa 5-8 metara udaljenosti. Registarska tablica mora biti jasno vidljiva.',
        instructionsEn: 'Photograph the rear of the vehicle from 5-8 meters away.',
        silhouetteUrl: `${basePath}/car-guest_exterior_rear.svg`,
        expectedAngle: 'REAR_FACING',
        estimatedDuration: 30,
        commonMistakesSr: ['Tablica nije vidljiva', 'Prtljažnik je otvoren'],
        commonMistakesEn: ['License plate not visible', 'Trunk is open'],
        tipsSr: ['Proverite da je prtljažnik zatvoren'],
        tipsEn: ['Make sure trunk is closed'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Tablica jasno vidljiva', 'Zadnja svetla vidljiva'],
        visibilityChecklistEn: ['License plate visible', 'Tail lights visible'],
      },
      {
        photoType: 'HOST_INTERIOR_DASHBOARD',
        displayName: 'Kontrolna tabla',
        displayNameEn: 'Dashboard',
        sequenceOrder: 5,
        totalInCategory: 2,
        category: 'interior',
        instructionsSr:
          'Fotografišite kontrolnu tablu iz pozicije vozača. Volan, instrument tabla i centralna konzola moraju biti vidljivi.',
        instructionsEn: "Photograph the dashboard from the driver's position.",
        silhouetteUrl: `${basePath}/car-guest_interior_dashboard.svg`,
        expectedAngle: 'DASHBOARD',
        estimatedDuration: 20,
        commonMistakesSr: ['Kilometraža nije vidljiva', 'Previše tamno'],
        commonMistakesEn: ['Odometer not visible', 'Too dark'],
        tipsSr: ['Otvorite vrata za bolje osvetljenje'],
        tipsEn: ['Open doors for better lighting'],
        required: true,
        visibilityChecklistSr: ['Volan vidljiv', 'Instrument tabla vidljiva'],
        visibilityChecklistEn: ['Steering wheel visible', 'Instrument panel visible'],
      },
      {
        photoType: 'HOST_INTERIOR_REAR',
        displayName: 'Zadnja sedišta',
        displayNameEn: 'Rear Seats',
        sequenceOrder: 6,
        totalInCategory: 2,
        category: 'interior',
        instructionsSr: 'Fotografišite zadnja sedišta iz prednjeg dela vozila.',
        instructionsEn: 'Photograph the rear seats from the front.',
        silhouetteUrl: `${basePath}/car-guest_interior_rear.svg`,
        expectedAngle: 'REAR_SEATS',
        estimatedDuration: 20,
        commonMistakesSr: ['Sedišta nisu u potpunosti vidljiva'],
        commonMistakesEn: ['Seats not fully visible'],
        tipsSr: ['Fotografišite iz vozačke pozicije'],
        tipsEn: ['Photograph from driver position'],
        required: true,
        visibilityChecklistSr: ['Sva sedišta vidljiva', 'Pod vidljiv'],
        visibilityChecklistEn: ['All seats visible', 'Floor visible'],
      },
      {
        photoType: 'HOST_ODOMETER',
        displayName: 'Kilometraža',
        displayNameEn: 'Odometer',
        sequenceOrder: 7,
        totalInCategory: 2,
        category: 'reading',
        instructionsSr: 'Fotografišite kilometražu vozila. Brojevi moraju biti jasno čitljivi.',
        instructionsEn: 'Photograph the vehicle odometer. Numbers must be clearly readable.',
        silhouetteUrl: `${basePath}/car-guest_odometer.svg`,
        expectedAngle: 'ODOMETER_CLOSEUP',
        estimatedDuration: 15,
        commonMistakesSr: ['Brojevi nisu čitljivi', 'Odraz na staklu'],
        commonMistakesEn: ['Numbers not readable', 'Reflection on glass'],
        tipsSr: ['Fokusirajte kameru na brojeve', 'Izbegavajte odsjaj'],
        tipsEn: ['Focus camera on numbers', 'Avoid glare'],
        required: true,
        visibilityChecklistSr: ['Brojevi jasno vidljivi', 'Jedinice mere vidljive (km)'],
        visibilityChecklistEn: ['Numbers clearly visible', 'Unit of measure visible (km)'],
      },
      {
        photoType: 'HOST_FUEL_GAUGE',
        displayName: 'Nivo goriva',
        displayNameEn: 'Fuel Gauge',
        sequenceOrder: 8,
        totalInCategory: 2,
        category: 'reading',
        instructionsSr:
          'Fotografišite pokazivač nivoa goriva. Kazaljka i skala moraju biti jasno vidljivi.',
        instructionsEn: 'Photograph the fuel gauge. Needle and scale must be clearly visible.',
        silhouetteUrl: `${basePath}/car-guest_fuel_gauge.svg`,
        expectedAngle: 'FUEL_GAUGE_CLOSEUP',
        estimatedDuration: 15,
        commonMistakesSr: ['Nivo nije vidljiv', 'Odraz na staklu'],
        commonMistakesEn: ['Level not visible', 'Reflection on glass'],
        tipsSr: ['Izbegavajte odsjaj'],
        tipsEn: ['Avoid glare'],
        required: true,
        visibilityChecklistSr: ['Kazaljka jasno vidljiva', 'Skala čitljiva'],
        visibilityChecklistEn: ['Needle clearly visible', 'Scale readable'],
      },
    ];
  }

  /**
   * Local fallback checkout guidance when backend is unavailable.
   */
  private getLocalFallbackCheckoutGuidance(): PhotoGuidanceDTO[] {
    const basePath = '/assets/silhouettes';

    return [
      {
        photoType: 'HOST_CHECKOUT_EXTERIOR_FRONT',
        displayName: 'Prednja strana',
        displayNameEn: 'Front Exterior',
        sequenceOrder: 1,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr: 'Povratna fotografija: Fotografišite prednju stranu vozila.',
        instructionsEn: 'Return photo: Photograph the front of the vehicle.',
        silhouetteUrl: `${basePath}/car-guest_exterior_front.svg`,
        expectedAngle: 'FRONT_FACING',
        estimatedDuration: 30,
        commonMistakesSr: ['Tablica nije vidljiva'],
        commonMistakesEn: ['License plate not visible'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Tablica jasno vidljiva'],
        visibilityChecklistEn: ['License plate visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_EXTERIOR_LEFT',
        displayName: 'Leva strana',
        displayNameEn: 'Left Side',
        sequenceOrder: 2,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr: 'Povratna fotografija: Fotografišite levu stranu vozila.',
        instructionsEn: 'Return photo: Photograph the left side.',
        silhouetteUrl: `${basePath}/car-guest_exterior_left.svg`,
        expectedAngle: 'LEFT_PROFILE',
        estimatedDuration: 30,
        commonMistakesSr: ['Ugao nije paralelan'],
        commonMistakesEn: ['Angle is not parallel'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Sva 4 točka vidljiva'],
        visibilityChecklistEn: ['All 4 wheels visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_EXTERIOR_RIGHT',
        displayName: 'Desna strana',
        displayNameEn: 'Right Side',
        sequenceOrder: 3,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr: 'Povratna fotografija: Fotografišite desnu stranu vozila.',
        instructionsEn: 'Return photo: Photograph the right side.',
        silhouetteUrl: `${basePath}/car-guest_exterior_right.svg`,
        expectedAngle: 'RIGHT_PROFILE',
        estimatedDuration: 30,
        commonMistakesSr: ['Ugao nije paralelan'],
        commonMistakesEn: ['Angle is not parallel'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Sva 4 točka vidljiva'],
        visibilityChecklistEn: ['All 4 wheels visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_EXTERIOR_REAR',
        displayName: 'Zadnja strana',
        displayNameEn: 'Rear Exterior',
        sequenceOrder: 4,
        totalInCategory: 4,
        category: 'exterior',
        instructionsSr: 'Povratna fotografija: Fotografišite zadnju stranu vozila.',
        instructionsEn: 'Return photo: Photograph the rear.',
        silhouetteUrl: `${basePath}/car-guest_exterior_rear.svg`,
        expectedAngle: 'REAR_FACING',
        estimatedDuration: 30,
        commonMistakesSr: ['Tablica nije vidljiva'],
        commonMistakesEn: ['License plate not visible'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        minDistanceMeters: 5,
        maxDistanceMeters: 8,
        visibilityChecklistSr: ['Tablica jasno vidljiva'],
        visibilityChecklistEn: ['License plate visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_INTERIOR_DASHBOARD',
        displayName: 'Kontrolna tabla',
        displayNameEn: 'Dashboard',
        sequenceOrder: 5,
        totalInCategory: 2,
        category: 'interior',
        instructionsSr: 'Povratna fotografija: Fotografišite kontrolnu tablu.',
        instructionsEn: 'Return photo: Photograph the dashboard.',
        silhouetteUrl: `${basePath}/car-guest_interior_dashboard.svg`,
        expectedAngle: 'DASHBOARD',
        estimatedDuration: 20,
        commonMistakesSr: ['Previše tamno'],
        commonMistakesEn: ['Too dark'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        visibilityChecklistSr: ['Volan vidljiv'],
        visibilityChecklistEn: ['Steering wheel visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_INTERIOR_REAR',
        displayName: 'Zadnja sedišta',
        displayNameEn: 'Rear Seats',
        sequenceOrder: 6,
        totalInCategory: 2,
        category: 'interior',
        instructionsSr: 'Povratna fotografija: Fotografišite zadnja sedišta.',
        instructionsEn: 'Return photo: Photograph the rear seats.',
        silhouetteUrl: `${basePath}/car-guest_interior_rear.svg`,
        expectedAngle: 'REAR_SEATS',
        estimatedDuration: 20,
        commonMistakesSr: ['Previše tamno'],
        commonMistakesEn: ['Too dark'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        visibilityChecklistSr: ['Oba zadnja sedišta vidljiva'],
        visibilityChecklistEn: ['Both rear seats visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_ODOMETER',
        displayName: 'Kilometraža',
        displayNameEn: 'Odometer',
        sequenceOrder: 7,
        totalInCategory: 2,
        category: 'reading',
        instructionsSr: 'Povratna fotografija: Fotografišite kilometražu izbliza.',
        instructionsEn: 'Return photo: Photograph the odometer close-up.',
        silhouetteUrl: `${basePath}/car-guest_odometer.svg`,
        expectedAngle: 'ODOMETER_CLOSEUP',
        estimatedDuration: 15,
        commonMistakesSr: ['Brojke nisu čitljive'],
        commonMistakesEn: ['Numbers not readable'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        visibilityChecklistSr: ['Sve cifre jasno vidljive'],
        visibilityChecklistEn: ['All digits clearly visible'],
      },
      {
        photoType: 'HOST_CHECKOUT_FUEL_GAUGE',
        displayName: 'Pokazivač goriva',
        displayNameEn: 'Fuel Gauge',
        sequenceOrder: 8,
        totalInCategory: 2,
        category: 'reading',
        instructionsSr: 'Povratna fotografija: Fotografišite pokazivač goriva.',
        instructionsEn: 'Return photo: Photograph the fuel gauge.',
        silhouetteUrl: `${basePath}/car-guest_fuel_gauge.svg`,
        expectedAngle: 'FUEL_GAUGE_CLOSEUP',
        estimatedDuration: 15,
        commonMistakesSr: ['Nivo nije vidljiv'],
        commonMistakesEn: ['Level not visible'],
        tipsSr: ['Uporedite sa fotografijom od prijema'],
        tipsEn: ['Compare with check-in photo'],
        required: true,
        visibilityChecklistSr: ['Kazaljka jasno vidljiva'],
        visibilityChecklistEn: ['Needle clearly visible'],
      },
    ];
  }
}
