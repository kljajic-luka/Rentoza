import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnDestroy,
  OnInit,
  inject,
} from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import {
  MatDatepicker,
  MatDatepickerInputEvent,
  MatDatepickerModule,
} from '@angular/material/datepicker';
import { MatSelectModule } from '@angular/material/select';
import { MatNativeDateModule } from '@angular/material/core';
import { MatSliderModule } from '@angular/material/slider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import {
  Observable,
  Subscription,
  Subject,
  filter,
  map,
  debounceTime,
  distinctUntilChanged,
  switchMap,
  of,
  take,
} from 'rxjs';

import { Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import {
  LocationService,
  GeocodeSuggestion,
  CarMarker,
  GeospatialSearchFilters,
  CarSearchResult,
  DEFAULT_MAP_CENTER,
} from '@core/services/location.service';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';
import {
  LocationPickerComponent,
  LocationCoordinates,
} from '@shared/components/location-picker/location-picker.component';

@Component({
  selector: 'app-home-page',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatAutocompleteModule,
    MatDatepickerModule,
    MatSelectModule,
    MatNativeDateModule,
    MatSliderModule,
    MatProgressSpinnerModule,
    MatButtonToggleModule,
    MatTooltipModule,
    FlexLayoutModule,
    FavoriteButtonComponent,
    LocationPickerComponent,
  ],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeComponent implements OnInit, OnDestroy {
  private readonly carService = inject(CarService);
  private readonly locationService = inject(LocationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);
  private navigationSubscription?: Subscription;
  private geocodeSubscription?: Subscription;
  private queryParamsSubscription?: Subscription;

  readonly featuredCars$: Observable<Car[]> = this.carService
    .getCars()
    .pipe(map((cars) => cars.slice(0, 3)));

  // Min date for date pickers (today)
  readonly today = new Date();

  // Geocoding autocomplete (replaces static city list)
  private readonly geocodeSearchSubject = new Subject<string>();
  geocodeSuggestions: GeocodeSuggestion[] = [];
  isLoadingGeocode = false;
  isGettingLocation = false; // Geolocation loading state

  // === GEOSPATIAL SEARCH STATE ===
  searchCenter: LocationCoordinates = {
    latitude: DEFAULT_MAP_CENTER.latitude,
    longitude: DEFAULT_MAP_CENTER.longitude,
  };
  searchRadius = 25; // km
  viewMode: 'list' | 'map' = 'list';
  isSearching = false;
  searchResults: CarSearchResult[] = [];
  carMarkers: CarMarker[] = [];
  hasSearched = false;

  // Search form fields with smart defaults
  searchLocation = '';
  selectedGeocodeSuggestion: GeocodeSuggestion | null = null;
  searchStartDate: Date | null = null;
  searchStartTime = '';
  searchEndDate: Date | null = null;
  searchEndTime = '';

  // Validation error messages
  locationError = '';
  startDateError = '';
  endDateError = '';
  dateRangeError = '';
  startTimeError = '';
  endTimeError = '';

  // Time options for dropdowns (30-minute intervals, 00:00 - 23:30)
  readonly timeOptions: string[] = this.generateTimeSlots();

  private generateTimeSlots(): string[] {
    const slots: string[] = [];
    for (let hour = 0; hour < 24; hour++) {
      for (const minute of [0, 30]) {
        const hh = hour.toString().padStart(2, '0');
        const mm = minute.toString().padStart(2, '0');
        slots.push(`${hh}:${mm}`);
      }
    }
    return slots;
  }

  ngOnInit(): void {
    this.setupGeocodeAutocomplete();

    // Restore search state from URL query params (supports page refresh)
    this.restoreSearchStateFromUrl();

    this.navigationSubscription = this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        const navEnd = event;
        // Only reset if navigating to home without query params
        if (this.isHomeUrl(navEnd.urlAfterRedirects) && !navEnd.urlAfterRedirects.includes('?')) {
          this.resetSearchFields();
        }
      });
  }

  ngOnDestroy(): void {
    this.navigationSubscription?.unsubscribe();
    this.geocodeSubscription?.unsubscribe();
    this.queryParamsSubscription?.unsubscribe();
  }

  /**
   * Restore search state from URL query parameters
   * Enables page refresh persistence and shareable search URLs
   */
  private restoreSearchStateFromUrl(): void {
    this.queryParamsSubscription = this.route.queryParams.pipe(take(1)).subscribe((params) => {
      // Check if we have search params to restore
      if (!params['location'] && !params['lat']) {
        this.resetSearchFields();
        return;
      }

      // Restore location
      if (params['location']) {
        this.searchLocation = params['location'];
      }

      // Restore coordinates
      if (params['lat'] && params['lng']) {
        this.searchCenter = {
          latitude: parseFloat(params['lat']),
          longitude: parseFloat(params['lng']),
        };

        // Create a minimal geocode suggestion for the restored location
        this.selectedGeocodeSuggestion = {
          id: 'restored-from-url',
          latitude: this.searchCenter.latitude,
          longitude: this.searchCenter.longitude,
          formattedAddress: this.searchLocation,
          address: this.searchLocation,
          city: params['city'] || this.searchLocation.split(',')[0]?.trim() || 'Nepoznato',
          country: 'Srbija',
          placeType: 'address',
        };
      }

      // Restore dates
      if (params['startDate']) {
        this.searchStartDate = new Date(params['startDate']);
      }
      if (params['endDate']) {
        this.searchEndDate = new Date(params['endDate']);
      }

      // Restore times
      if (params['startTime']) {
        this.searchStartTime = params['startTime'];
      }
      if (params['endTime']) {
        this.searchEndTime = params['endTime'];
      }

      // Restore radius
      if (params['radius']) {
        this.searchRadius = parseInt(params['radius'], 10) || 25;
      }

      // Restore view mode
      if (params['view'] === 'map' || params['view'] === 'list') {
        this.viewMode = params['view'];
      }

      this.cdr.markForCheck();

      // Re-execute search if we have valid params
      if (this.selectedGeocodeSuggestion && this.searchStartDate && this.searchEndDate) {
        this.performGeospatialSearch();
      }
    });
  }

  /**
   * Update URL with current search state (for bookmarking/sharing)
   */
  private updateUrlWithSearchState(): void {
    const queryParams: Record<string, string | number | null> = {};

    // Location params
    if (this.searchLocation) {
      queryParams['location'] = this.searchLocation;
    }
    if (this.searchCenter.latitude !== DEFAULT_MAP_CENTER.latitude) {
      queryParams['lat'] = this.searchCenter.latitude.toFixed(6);
      queryParams['lng'] = this.searchCenter.longitude.toFixed(6);
    }
    if (this.selectedGeocodeSuggestion?.city) {
      queryParams['city'] = this.selectedGeocodeSuggestion.city;
    }

    // Date/time params
    if (this.searchStartDate) {
      queryParams['startDate'] = this.formatDateForUrl(this.searchStartDate);
    }
    if (this.searchEndDate) {
      queryParams['endDate'] = this.formatDateForUrl(this.searchEndDate);
    }
    if (this.searchStartTime) {
      queryParams['startTime'] = this.searchStartTime;
    }
    if (this.searchEndTime) {
      queryParams['endTime'] = this.searchEndTime;
    }

    // Search params
    if (this.searchRadius !== 25) {
      queryParams['radius'] = this.searchRadius;
    }
    if (this.viewMode !== 'list') {
      queryParams['view'] = this.viewMode;
    }

    // Update URL without triggering navigation
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'replace', // Replace existing params
      replaceUrl: true, // Don't add to browser history
    });
  }

  /**
   * Format date for URL (YYYY-MM-DD)
   */
  private formatDateForUrl(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  /**
   * Setup geocoding autocomplete with debouncing
   */
  private setupGeocodeAutocomplete(): void {
    this.geocodeSubscription = this.geocodeSearchSubject
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((query) => {
          if (!query || query.length < 2) {
            return of([]);
          }
          this.isLoadingGeocode = true;
          this.cdr.markForCheck();
          return this.locationService.geocodeAddress(query);
        })
      )
      .subscribe((suggestions) => {
        this.geocodeSuggestions = suggestions;
        this.isLoadingGeocode = false;
        this.cdr.markForCheck();
      });
  }

  /**
   * Handle location input changes for geocoding
   * Note: searchLocation is bound via ngModel, so we just need to trigger geocoding
   */
  onLocationInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = input.value;
    // Clear previous selection when user types manually
    this.selectedGeocodeSuggestion = null;
    // Trigger geocode search
    this.geocodeSearchSubject.next(value);
  }

  /**
   * Handle geocode suggestion selection
   */
  onGeocodeSuggestionSelected(suggestion: GeocodeSuggestion): void {
    this.selectedGeocodeSuggestion = suggestion;
    this.searchLocation = suggestion.formattedAddress;
    this.searchCenter = {
      latitude: suggestion.latitude,
      longitude: suggestion.longitude,
    };
    this.locationError = '';
    this.cdr.markForCheck();
  }

  /**
   * Display function for geocode autocomplete
   * Handles both GeocodeSuggestion objects and plain strings
   */
  displayGeocodeSuggestion(value: GeocodeSuggestion | string | null): string {
    if (!value) {
      return '';
    }
    // If it's already a string, return it directly
    if (typeof value === 'string') {
      return value;
    }
    // If it's a GeocodeSuggestion object, return the formatted address
    return value.formattedAddress || '';
  }

  /**
   * Handle location selection from map click
   */
  onMapLocationSelected(coords: LocationCoordinates): void {
    this.searchCenter = coords;
    // Reverse geocode to get address
    this.locationService.reverseGeocode(coords.latitude, coords.longitude).subscribe((result) => {
      this.searchLocation = result.formattedAddress;
      this.selectedGeocodeSuggestion = {
        id: 'map-selected',
        latitude: coords.latitude,
        longitude: coords.longitude,
        formattedAddress: result.formattedAddress,
        address: result.address,
        city: result.city,
        zipCode: result.zipCode,
        country: result.country,
        placeType: result.placeType as any,
      };
      this.cdr.markForCheck();
    });
  }

  /**
   * Handle radius slider change
   */
  onRadiusChange(radius: number): void {
    this.searchRadius = radius;
    if (this.hasSearched) {
      this.performGeospatialSearch();
    }
  }

  /**
   * Format radius for slider display
   */
  formatRadius(value: number): string {
    return `${value} km`;
  }

  /**
   * Toggle between list and map view
   */
  toggleViewMode(): void {
    this.viewMode = this.viewMode === 'list' ? 'map' : 'list';

    // Update URL to persist view mode
    if (this.hasSearched) {
      this.updateUrlWithSearchState();
    }

    this.cdr.markForCheck();
  }

  /**
   * Use browser geolocation to get current position
   * and set as search center with reverse geocoding
   */
  useCurrentLocation(): void {
    if (!navigator.geolocation) {
      this.locationError = 'Geolokacija nije podržana u vašem pregledaču';
      return;
    }

    this.isGettingLocation = true;
    this.locationError = '';
    this.cdr.markForCheck();

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const coords = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        };

        this.searchCenter = coords;

        // Reverse geocode to get address
        this.locationService.reverseGeocode(coords.latitude, coords.longitude).subscribe({
          next: (result) => {
            this.searchLocation = result.formattedAddress;
            this.selectedGeocodeSuggestion = {
              id: 'current-location',
              latitude: coords.latitude,
              longitude: coords.longitude,
              formattedAddress: result.formattedAddress,
              address: result.address,
              city: result.city,
              zipCode: result.zipCode,
              country: result.country,
              placeType: result.placeType as any,
            };
            this.locationError = '';
            this.isGettingLocation = false;
            this.cdr.markForCheck();
          },
          error: () => {
            // Use coordinates even without address
            this.searchLocation = `${coords.latitude.toFixed(4)}, ${coords.longitude.toFixed(4)}`;
            this.selectedGeocodeSuggestion = {
              id: 'current-location',
              latitude: coords.latitude,
              longitude: coords.longitude,
              formattedAddress: this.searchLocation,
              address: 'Trenutna lokacija',
              city: 'Nepoznato',
              country: 'Srbija',
              placeType: 'address',
            };
            this.isGettingLocation = false;
            this.cdr.markForCheck();
          },
        });
      },
      (error) => {
        this.isGettingLocation = false;
        switch (error.code) {
          case error.PERMISSION_DENIED:
            this.locationError =
              'Pristup lokaciji je odbijen. Omogućite lokaciju u podešavanjima pregledača.';
            break;
          case error.POSITION_UNAVAILABLE:
            this.locationError = 'Lokacija nije dostupna. Proverite GPS i mrežne postavke.';
            break;
          case error.TIMEOUT:
            this.locationError = 'Isteklo vreme za dobijanje lokacije. Pokušajte ponovo.';
            break;
          default:
            this.locationError = 'Greška pri dobijanju lokacije.';
        }
        this.cdr.markForCheck();
      },
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 60000,
      }
    );
  }

  /**
   * Handle car marker selection on map
   */
  onCarMarkerSelected(carId: number): void {
    this.router.navigate(['/cars', carId]);
  }

  get filteredCities(): string[] {
    // Return geocode suggestions formatted for display
    return this.geocodeSuggestions.map((s) => s.formattedAddress);
  }

  get datesSelected(): boolean {
    return !!(this.searchStartDate && this.searchEndDate);
  }

  onCitySelected(city: string): void {
    this.searchLocation = city;
    this.validateField('location');
  }

  onStartDateChange(event: MatDatepickerInputEvent<Date>, endPicker: MatDatepicker<Date>): void {
    this.searchStartDate = event.value ?? null;
    this.searchStartTime = '';
    this.searchEndTime = '';
    this.startTimeError = '';
    this.endTimeError = '';

    if (this.searchEndDate && this.searchStartDate && this.searchEndDate <= this.searchStartDate) {
      this.searchEndDate = null;
    }

    this.validateField('startDate');
    this.cdr.markForCheck();

    if (this.searchStartDate) {
      // Prompt user to complete the range
      setTimeout(() => endPicker.open());
    }
  }

  onEndDateChange(event: MatDatepickerInputEvent<Date>): void {
    this.searchEndDate = event.value ?? null;
    this.searchEndTime = '';
    this.endTimeError = '';
    this.validateField('endDate');
    this.cdr.markForCheck();
  }

  private toCanonical(value: string): string {
    return value
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/đ/gi, 'd')
      .toLowerCase();
  }

  private resetSearchFields(): void {
    this.searchLocation = '';
    this.selectedGeocodeSuggestion = null;
    this.geocodeSuggestions = [];
    this.searchStartDate = null;
    this.searchStartTime = '';
    this.searchEndDate = null;
    this.searchEndTime = '';
    this.searchResults = [];
    this.carMarkers = [];
    this.hasSearched = false;
    this.searchRadius = 25;
    this.viewMode = 'list';
    this.searchCenter = {
      latitude: DEFAULT_MAP_CENTER.latitude,
      longitude: DEFAULT_MAP_CENTER.longitude,
    };
    this.clearErrors();

    // Clear URL params when resetting
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true,
    });

    this.cdr.markForCheck();
  }

  private isHomeUrl(url: string): boolean {
    const path = url.split('?')[0].split('#')[0];
    return path === '/' || path === '' || path === '/home';
  }

  searchCars(): void {
    // Clear previous errors
    this.clearErrors();

    const location = this.searchLocation.trim();

    // Required fields validation
    if (!location) {
      this.locationError = 'Unesite lokaciju';
    }

    if (!this.searchStartDate) {
      this.startDateError = 'Izaberite početni datum';
    }

    if (!this.searchEndDate) {
      this.endDateError = 'Izaberite krajnji datum';
    }

    if (!this.searchStartTime) {
      this.startTimeError = 'Izaberite početno vreme';
    }

    if (!this.searchEndTime) {
      this.endTimeError = 'Izaberite krajnje vreme';
    }

    // Validate geocoded location (must select from suggestions or map)
    if (location && !this.selectedGeocodeSuggestion) {
      this.locationError = 'Odaberite lokaciju iz ponuđenih opcija ili označite na mapi';
    }

    // Stop if any required validation failed
    if (
      this.locationError ||
      this.startDateError ||
      this.endDateError ||
      this.startTimeError ||
      this.endTimeError
    ) {
      return;
    }

    // Validate date range
    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);

    if ((this.searchStartDate as Date) < todayStart) {
      this.startDateError = 'Početni datum mora biti danas ili kasnije';
      return;
    }

    const startDateTime = new Date(this.searchStartDate as Date);
    startDateTime.setHours(
      parseInt(this.searchStartTime.split(':')[0]),
      parseInt(this.searchStartTime.split(':')[1])
    );

    const endDateTime = new Date(this.searchEndDate as Date);
    endDateTime.setHours(
      parseInt(this.searchEndTime.split(':')[0]),
      parseInt(this.searchEndTime.split(':')[1])
    );

    if (endDateTime <= startDateTime) {
      this.dateRangeError = 'Krajnji datum mora biti posle početnog';
      return;
    }

    // Perform geospatial search
    this.performGeospatialSearch();
  }

  /**
   * Perform geospatial search using LocationService
   */
  private performGeospatialSearch(): void {
    this.isSearching = true;
    this.hasSearched = true;
    this.cdr.markForCheck();

    // Get location string from selected suggestion (city name for backend search)
    // Skip 'Nepoznato' city fallback - prefer address extraction in that case
    const city = this.selectedGeocodeSuggestion?.city;
    const locationString =
      city && city !== 'Nepoznato'
        ? city
        : this.searchLocation.split(',')[0]?.trim() || this.searchLocation;

    const filters: GeospatialSearchFilters = {
      location: locationString, // Send city name to backend
      latitude: this.searchCenter.latitude,
      longitude: this.searchCenter.longitude,
      radiusKm: this.searchRadius,
      page: 0,
      pageSize: 50,
    };

    this.locationService.searchCars(filters).subscribe({
      next: (response) => {
        this.searchResults = response.data;
        this.carMarkers = this.createCarMarkers(response.data);
        this.isSearching = false;

        // Persist search state to URL for refresh/sharing
        this.updateUrlWithSearchState();

        this.cdr.markForCheck();
      },
      error: (error) => {
        console.error('Geospatial search failed:', error);
        this.searchResults = [];
        this.carMarkers = [];
        this.isSearching = false;
        this.cdr.markForCheck();
      },
    });
  }

  /**
   * Convert search results to map markers
   */
  private createCarMarkers(cars: CarSearchResult[]): CarMarker[] {
    return cars
      .filter((car) => car.locationGeoPoint)
      .map((car) => ({
        carId: car.id,
        latitude: car.locationGeoPoint.latitude,
        longitude: car.locationGeoPoint.longitude,
        title: `${car.brand} ${car.model}`,
        pricePerDay: car.pricePerDay,
        distanceKm: car.distanceKm,
      }));
  }

  /**
   * Combine date and time into ISO-8601 LocalDateTime string.
   * Format: YYYY-MM-DDTHH:mm:00
   */
  private combineDateTime(date: Date, time: string): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}T${time}:00`;
  }

  /**
   * Validate individual field and show error hint
   */
  validateField(field: 'location' | 'startDate' | 'endDate'): void {
    switch (field) {
      case 'location':
        this.locationError = !this.searchLocation.trim() ? 'Unesite lokaciju' : '';
        break;
      case 'startDate':
        this.startDateError = !this.searchStartDate ? 'Izaberite početni datum' : '';
        break;
      case 'endDate':
        this.endDateError = !this.searchEndDate ? 'Izaberite krajnji datum' : '';
        // Also validate range if both dates are set
        if (this.searchStartDate && this.searchEndDate) {
          const start = new Date(this.searchStartDate);
          const end = new Date(this.searchEndDate);
          this.dateRangeError = end <= start ? 'Krajnji datum mora biti posle početnog' : '';
        }
        break;
    }
  }

  /**
   * Clear all validation errors
   */
  private clearErrors(): void {
    this.locationError = '';
    this.startDateError = '';
    this.endDateError = '';
    this.dateRangeError = '';
    this.startTimeError = '';
    this.endTimeError = '';
  }

  /**
   * Format Date object to YYYY-MM-DD string
   */
  private formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }
}
