/**
 * Location Step Component
 *
 * Step component for the Add Car Wizard that handles geospatial location input.
 * Provides address autocomplete, map-based selection, and GPS geolocation.
 *
 * FEATURES:
 * - Address autocomplete with Mapbox Geocoding (via backend proxy)
 * - Interactive map for precise location selection
 * - GPS geolocation for current device location
 * - Serbia bounds validation
 * - Location accuracy indicators
 *
 * @example
 * <app-location-step
 *   [initialLocation]="savedLocation"
 *   (locationSelected)="onLocationSet($event)">
 * </app-location-step>
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnInit,
  OnDestroy,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, takeUntil, debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs';

import {
  LocationService,
  GeoPointDTO,
  GeocodeSuggestion,
  SERBIA_BOUNDS,
  DEFAULT_MAP_CENTER,
} from '@core/services/location.service';
import { LocationPickerComponent, LocationCoordinates } from '@shared/components/location-picker';

/** Location accuracy levels for display */
export type LocationAccuracy = 'PRECISE' | 'APPROXIMATE' | 'UNKNOWN';

/** Location data emitted to parent wizard */
export interface LocationStepData {
  latitude: number;
  longitude: number;
  address: string;
  city: string;
  zipCode?: string;
  accuracyMeters?: number;
}

@Component({
  selector: 'app-location-step',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatAutocompleteModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    LocationPickerComponent,
  ],
  templateUrl: './location-step.component.html',
  styleUrls: ['./location-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationStepComponent implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly locationService = inject(LocationService);
  private readonly destroy$ = new Subject<void>();

  // === INPUTS ===

  /** Initial location data (for editing existing cars) */
  @Input() initialLocation: LocationStepData | null = null;

  // === OUTPUTS ===

  /** Emitted when a valid location is selected */
  @Output() locationSelected = new EventEmitter<LocationStepData>();

  /** Emitted when location validity changes */
  @Output() validityChanged = new EventEmitter<boolean>();

  // === REACTIVE STATE ===

  protected readonly selectedLocation = signal<GeoPointDTO | null>(null);
  protected readonly geocodeSuggestions = signal<GeocodeSuggestion[]>([]);
  protected readonly isGeocoding = signal(false);
  protected readonly isGeolocating = signal(false);
  protected readonly showMap = signal(false);
  protected readonly locationError = signal<string | null>(null);

  // Default map center (Belgrade) for when no location is selected
  protected readonly mapDefaultCenter = {
    latitude: DEFAULT_MAP_CENTER.latitude,
    longitude: DEFAULT_MAP_CENTER.longitude,
  };

  protected readonly locationAccuracy = computed<LocationAccuracy>(() => {
    const loc = this.selectedLocation();
    if (!loc?.accuracyMeters) return 'UNKNOWN';
    if (loc.accuracyMeters < 50) return 'PRECISE';
    if (loc.accuracyMeters < 500) return 'APPROXIMATE';
    return 'UNKNOWN';
  });

  protected readonly isWithinSerbia = computed(() => {
    const loc = this.selectedLocation();
    if (!loc) return true;
    return this.locationService.isWithinSerbiaBounds(loc.latitude, loc.longitude);
  });

  protected readonly isLocationValid = computed(() => {
    const loc = this.selectedLocation();
    return loc !== null && this.isWithinSerbia() && this.locationForm.valid;
  });

  // === FORM ===

  protected readonly locationForm: FormGroup = this.fb.group({
    address: ['', [Validators.required, Validators.minLength(5)]],
    city: ['', [Validators.required]],
    zipCode: [''],
  });

  ngOnInit(): void {
    this.setupAddressAutocomplete();

    // Initialize from saved location if provided
    if (this.initialLocation) {
      this.loadInitialLocation();
    }

    // Emit validity changes
    this.locationForm.statusChanges.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.validityChanged.emit(this.isLocationValid());
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Load initial location data
   */
  private loadInitialLocation(): void {
    if (!this.initialLocation) return;

    this.selectedLocation.set({
      latitude: this.initialLocation.latitude,
      longitude: this.initialLocation.longitude,
      address: this.initialLocation.address,
      city: this.initialLocation.city,
      zipCode: this.initialLocation.zipCode,
      accuracyMeters: this.initialLocation.accuracyMeters,
    });

    this.locationForm.patchValue({
      address: this.initialLocation.address,
      city: this.initialLocation.city,
      zipCode: this.initialLocation.zipCode ?? '',
    });

    this.showMap.set(true);
  }

  /**
   * Setup address autocomplete with debounced geocoding
   */
  private setupAddressAutocomplete(): void {
    this.locationForm
      .get('address')
      ?.valueChanges.pipe(
        takeUntil(this.destroy$),
        debounceTime(300),
        distinctUntilChanged(),
        filter((address) => address && address.length >= 3),
        switchMap((address) => {
          this.isGeocoding.set(true);
          return this.locationService.geocodeAddress(address);
        })
      )
      .subscribe({
        next: (suggestions) => {
          this.geocodeSuggestions.set(suggestions);
          this.isGeocoding.set(false);
        },
        error: (error) => {
          console.error('Geocoding error:', error);
          this.geocodeSuggestions.set([]);
          this.isGeocoding.set(false);
        },
      });
  }

  /**
   * Select a location from geocode suggestions
   */
  protected selectSuggestion(suggestion: GeocodeSuggestion): void {
    this.selectedLocation.set({
      latitude: suggestion.latitude,
      longitude: suggestion.longitude,
      address: suggestion.address,
      city: suggestion.city,
      zipCode: suggestion.zipCode,
      accuracyMeters: suggestion.accuracyMeters,
    });

    this.locationForm.patchValue({
      address: suggestion.formattedAddress || suggestion.address,
      city: suggestion.city,
      zipCode: suggestion.zipCode ?? '',
    });

    this.geocodeSuggestions.set([]);
    this.showMap.set(true);

    this.emitLocation();
  }

  /**
   * Use device GPS to get current location
   */
  protected async useCurrentLocation(): Promise<void> {
    if (!navigator.geolocation) {
      this.locationError.set('Geolokacija nije podržana u vašem pregledaču');
      return;
    }

    this.isGeolocating.set(true);
    this.locationError.set(null);

    try {
      const position = await new Promise<GeolocationPosition>((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 15000,
          maximumAge: 0,
        });
      });

      const { latitude, longitude, accuracy } = position.coords;

      // Reverse geocode to get address
      this.locationService.reverseGeocode(latitude, longitude).subscribe({
        next: (result) => {
          // Use formattedAddress (mapped from backend 'address' by LocationService)
          const addressValue =
            result.formattedAddress ||
            result.address ||
            `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;

          this.selectedLocation.set({
            latitude,
            longitude,
            address: addressValue,
            city: result.city,
            zipCode: result.zipCode,
            accuracyMeters: accuracy,
          });

          this.locationForm.patchValue({
            address: addressValue,
            city: result.city || '',
            zipCode: result.zipCode ?? '',
          });

          this.showMap.set(true);
          this.locationError.set(null);
          this.emitLocation();
        },
        error: (error) => {
          console.error('Reverse geocoding failed:', error);
          // Still set coordinates even without address
          this.selectedLocation.set({
            latitude,
            longitude,
            address: 'Nepoznata adresa',
            city: '',
            accuracyMeters: accuracy,
          });
          this.showMap.set(true);
          this.emitLocation();
        },
      });
    } catch (error: any) {
      console.error('Geolocation error:', error);
      if (error.code === 1) {
        this.locationError.set(
          'Pristup lokaciji je odbijen. Molimo omogućite lokaciju u podešavanjima pregledača.'
        );
      } else if (error.code === 2) {
        this.locationError.set('Lokacija nije dostupna. Proverite GPS i mrežne postavke.');
      } else {
        this.locationError.set('Isteklo vreme za dobijanje lokacije. Pokušajte ponovo.');
      }
    } finally {
      this.isGeolocating.set(false);
    }
  }

  /**
   * Toggle map visibility
   * When showing map without a selected location, use default Serbia center
   */
  protected toggleMap(): void {
    const willShowMap = !this.showMap();

    // If showing map and no location selected, set default center for Serbia
    if (willShowMap && !this.selectedLocation()) {
      this.selectedLocation.set({
        latitude: this.mapDefaultCenter.latitude,
        longitude: this.mapDefaultCenter.longitude,
        address: '',
        city: 'Beograd',
      });
    }

    this.showMap.set(willShowMap);
  }

  /**
   * Handle map click/drag to update location
   */
  protected onMapLocationChanged(coords: LocationCoordinates): void {
    // Reverse geocode to get address for new coordinates
    this.locationService.reverseGeocode(coords.latitude, coords.longitude).subscribe({
      next: (result) => {
        // Use address field (backend returns 'address', not 'formattedAddress')
        const addressValue =
          result.address || `${coords.latitude.toFixed(6)}, ${coords.longitude.toFixed(6)}`;

        this.selectedLocation.set({
          latitude: coords.latitude,
          longitude: coords.longitude,
          address: addressValue,
          city: result.city,
          zipCode: result.zipCode,
          accuracyMeters: undefined, // Manual selection doesn't have accuracy
        });

        this.locationForm.patchValue({
          address: addressValue,
          city: result.city || '',
          zipCode: result.zipCode ?? '',
        });

        this.emitLocation();
      },
      error: (error) => {
        console.error('Reverse geocoding failed:', error);
        // Keep coordinates but mark address as unknown
        const current = this.selectedLocation();
        this.selectedLocation.set({
          latitude: coords.latitude,
          longitude: coords.longitude,
          address: current?.address || 'Unknown address',
          city: current?.city || '',
        });
      },
    });
  }

  /**
   * Emit the current location to parent
   */
  private emitLocation(): void {
    const loc = this.selectedLocation();
    if (!loc) return;

    this.locationSelected.emit({
      latitude: loc.latitude,
      longitude: loc.longitude,
      address: loc.address || '',
      city: loc.city || '',
      zipCode: loc.zipCode,
      accuracyMeters: loc.accuracyMeters,
    });

    this.validityChanged.emit(this.isLocationValid());
  }

  /**
   * Get the current location data for form submission
   */
  public getLocationData(): LocationStepData | null {
    const loc = this.selectedLocation();
    if (!loc || !this.isLocationValid()) return null;

    return {
      latitude: loc.latitude,
      longitude: loc.longitude,
      address: loc.address || '',
      city: loc.city || '',
      zipCode: loc.zipCode,
      accuracyMeters: loc.accuracyMeters,
    };
  }

  /**
   * Check if the location is valid for form submission
   */
  public isValid(): boolean {
    return this.isLocationValid();
  }

  /**
   * Display format for suggestion dropdown
   */
  protected displayFn(suggestion: GeocodeSuggestion): string {
    return suggestion?.formattedAddress || suggestion?.address || '';
  }
}