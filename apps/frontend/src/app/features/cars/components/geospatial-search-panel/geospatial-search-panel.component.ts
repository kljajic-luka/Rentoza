/**
 * Geospatial Search Panel Component
 *
 * A panel component for geospatial car search that can be integrated
 * into the car list page. Provides location selection, radius slider,
 * and map-based search functionality.
 *
 * @example
 * <app-geospatial-search-panel
 *   (searchTriggered)="onGeospatialSearch($event)">
 * </app-geospatial-search-panel>
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */
import {
  Component,
  Output,
  EventEmitter,
  OnInit,
  OnDestroy,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, takeUntil, debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs';

import {
  LocationService,
  GeocodeSuggestion,
  GeospatialSearchFilters,
  CarSearchResponse,
  CarMarker,
  SERBIA_BOUNDS,
  DEFAULT_MAP_CENTER,
} from '@core/services/location.service';
import { LocationPickerComponent, LocationCoordinates } from '@shared/components/location-picker';

/** Geospatial search event data */
export interface GeospatialSearchEvent {
  latitude: number;
  longitude: number;
  radiusKm: number;
  address?: string;
  city?: string;
}

@Component({
  selector: 'app-geospatial-search-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatSliderModule,
    MatExpansionModule,
    MatAutocompleteModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatTooltipModule,
    LocationPickerComponent,
  ],
  templateUrl: './geospatial-search-panel.component.html',
  styleUrls: ['./geospatial-search-panel.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeospatialSearchPanelComponent implements OnInit, OnDestroy {
  private readonly locationService = inject(LocationService);
  private readonly destroy$ = new Subject<void>();
  private readonly searchInputSubject = new Subject<string>();

  // === OUTPUTS ===

  /** Emitted when a geospatial search should be triggered */
  @Output() searchTriggered = new EventEmitter<GeospatialSearchEvent>();

  /** Emitted when search results are received */
  @Output() resultsReceived = new EventEmitter<CarSearchResponse>();

  /** Emitted when markers should be displayed on map */
  @Output() markersChanged = new EventEmitter<CarMarker[]>();

  // === REACTIVE STATE ===

  protected readonly isExpanded = signal(false);
  protected readonly isSearching = signal(false);
  protected readonly isGeolocating = signal(false);
  protected readonly showMap = signal(false);

  protected readonly searchAddress = signal('');
  protected readonly geocodeSuggestions = signal<GeocodeSuggestion[]>([]);

  protected readonly selectedLocation = signal<{
    latitude: number;
    longitude: number;
    address?: string;
    city?: string;
  } | null>(null);

  protected readonly searchRadius = signal(25); // km

  protected readonly hasValidLocation = signal(false);

  // Default center (Belgrade)
  protected readonly defaultCenter = DEFAULT_MAP_CENTER;

  ngOnInit(): void {
    this.setupAddressAutocomplete();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Setup address input autocomplete
   */
  private setupAddressAutocomplete(): void {
    this.searchInputSubject
      .pipe(
        takeUntil(this.destroy$),
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((query) => {
          if (!query || query.length < 3) {
            return of([]);
          }
          return this.locationService.geocodeAddress(query);
        })
      )
      .subscribe({
        next: (suggestions) => {
          this.geocodeSuggestions.set(suggestions);
        },
        error: (error) => {
          console.error('Geocoding error:', error);
          this.geocodeSuggestions.set([]);
        },
      });
  }

  /**
   * Handle address input change
   */
  protected onAddressInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchAddress.set(input.value);
    this.searchInputSubject.next(input.value);
  }

  /**
   * Select a geocode suggestion
   */
  protected selectSuggestion(suggestion: GeocodeSuggestion): void {
    this.selectedLocation.set({
      latitude: suggestion.latitude,
      longitude: suggestion.longitude,
      address: suggestion.formattedAddress || suggestion.address,
      city: suggestion.city,
    });

    this.searchAddress.set(suggestion.formattedAddress || suggestion.address);
    this.geocodeSuggestions.set([]);
    this.hasValidLocation.set(true);
    this.showMap.set(true);
  }

  /**
   * Use device GPS location
   */
  protected async useCurrentLocation(): Promise<void> {
    if (!navigator.geolocation) {
      console.error('Geolocation not supported');
      return;
    }

    this.isGeolocating.set(true);

    try {
      const position = await new Promise<GeolocationPosition>((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 15000,
          maximumAge: 0,
        });
      });

      const { latitude, longitude } = position.coords;

      // Reverse geocode to get address
      this.locationService.reverseGeocode(latitude, longitude).subscribe({
        next: (result) => {
          this.selectedLocation.set({
            latitude,
            longitude,
            address: result.formattedAddress,
            city: result.city,
          });

          this.searchAddress.set(result.formattedAddress);
          this.hasValidLocation.set(true);
          this.showMap.set(true);
        },
        error: () => {
          // Still use location even without address
          this.selectedLocation.set({
            latitude,
            longitude,
            address: 'Trenutna lokacija',
          });
          this.hasValidLocation.set(true);
          this.showMap.set(true);
        },
      });
    } catch (error: any) {
      console.error('Geolocation error:', error);
      alert('Nije moguće odrediti lokaciju. Molimo omogućite GPS.');
    } finally {
      this.isGeolocating.set(false);
    }
  }

  /**
   * Handle map location change
   */
  protected onMapLocationChanged(coords: LocationCoordinates): void {
    this.locationService.reverseGeocode(coords.latitude, coords.longitude).subscribe({
      next: (result) => {
        this.selectedLocation.set({
          latitude: coords.latitude,
          longitude: coords.longitude,
          address: result.formattedAddress,
          city: result.city,
        });
        this.searchAddress.set(result.formattedAddress);
        this.hasValidLocation.set(true);
      },
      error: () => {
        this.selectedLocation.set({
          latitude: coords.latitude,
          longitude: coords.longitude,
          address: 'Izabrana lokacija',
        });
        this.hasValidLocation.set(true);
      },
    });
  }

  /**
   * Handle radius slider change
   */
  protected onRadiusChange(value: number): void {
    this.searchRadius.set(value);
  }

  /**
   * Toggle map visibility
   */
  protected toggleMap(): void {
    this.showMap.update((show) => !show);
  }

  /**
   * Toggle panel expansion
   */
  protected toggleExpanded(): void {
    this.isExpanded.update((expanded) => !expanded);
  }

  /**
   * Clear the search
   */
  protected clearSearch(): void {
    this.searchAddress.set('');
    this.selectedLocation.set(null);
    this.geocodeSuggestions.set([]);
    this.hasValidLocation.set(false);
    this.showMap.set(false);
    this.searchRadius.set(25);
  }

  /**
   * Trigger the geospatial search
   */
  protected triggerSearch(): void {
    const location = this.selectedLocation();
    if (!location) return;

    this.searchTriggered.emit({
      latitude: location.latitude,
      longitude: location.longitude,
      radiusKm: this.searchRadius(),
      address: location.address,
      city: location.city,
    });
  }

  /**
   * Perform search and get results
   */
  protected async performSearch(): Promise<void> {
    const location = this.selectedLocation();
    if (!location) return;

    this.isSearching.set(true);

    const filters: GeospatialSearchFilters = {
      latitude: location.latitude,
      longitude: location.longitude,
      radiusKm: this.searchRadius(),
      page: 0,
      pageSize: 20,
    };

    this.locationService.searchCars(filters).subscribe({
      next: (response) => {
        this.resultsReceived.emit(response);

        // Convert to markers for map display
        const markers: CarMarker[] = response.data.map((car) => ({
          carId: car.id,
          latitude: car.locationGeoPoint.latitude,
          longitude: car.locationGeoPoint.longitude,
          title: `${car.brand} ${car.model}`,
          pricePerDay: car.pricePerDay,
          distanceKm: car.distanceKm,
        }));
        this.markersChanged.emit(markers);

        this.isSearching.set(false);
      },
      error: (error) => {
        console.error('Search error:', error);
        this.isSearching.set(false);
      },
    });
  }

  /**
   * Display function for autocomplete
   */
  protected displayFn(suggestion: GeocodeSuggestion): string {
    return suggestion?.formattedAddress || suggestion?.address || '';
  }
}
