import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
  OnInit,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { LoadingSkeletonComponent } from '@shared/components/loading-skeleton/loading-skeleton.component';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { ToastService } from '@core/services/toast.service';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import {
  MatDatepickerModule,
  MatDatepicker,
  MatDatepickerInputEvent,
} from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import {
  BehaviorSubject,
  Observable,
  Subject,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  switchMap,
  takeUntil,
  tap,
  catchError,
  of,
  map,
} from 'rxjs';

import { Car, Feature, TransmissionType } from '@core/models/car.model';
import {
  AvailabilitySearchParams,
  CarSearchCriteria,
  PagedResponse,
  mergeFiltersIntoAvailabilityParams,
  extractFiltersFromAvailabilityParams,
} from '@core/models/car-search.model';
import { CarService } from '@core/services/car.service';
import {
  LocationService,
  GeocodeSuggestion,
  DEFAULT_MAP_CENTER,
} from '@core/services/location.service';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';
import { CarFiltersComponent } from '../../components/car-filters/car-filters.component';
import { TranslateEnumPipe } from '@shared/pipes/translate-enum.pipe';
import { CarCardComponent } from '@shared/components/car-card/car-card.component';
import { EmptyStateComponent } from '@shared/components/empty-state/empty-state.component';
import { environment } from '@environments/environment';
// Phase 2: Time validation utilities
import {
  validateLeadTime,
  validateMinimumDuration,
  DEFAULT_MIN_TRIP_HOURS,
  DEFAULT_ADVANCE_NOTICE_HOURS,
} from '@core/utils/time-validation.util';

@Component({
  selector: 'app-car-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    LoadingSkeletonComponent,
    MatIconModule,
    MatChipsModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatAutocompleteModule,
    MatSnackBarModule,
    MatTooltipModule,
    FlexLayoutModule,
    FavoriteButtonComponent,
    CarFiltersComponent,
    TranslateEnumPipe,
    CarCardComponent,
    EmptyStateComponent,
  ],
  templateUrl: './car-list.component.html',
  styleUrls: ['./car-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarListComponent implements OnInit, OnDestroy {
  private readonly carService = inject(CarService);
  private readonly locationService = inject(LocationService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly destroy$ = new Subject<void>();

  private readonly loggedMissingImageForCarIds = new Set<string>();
  private loggedSample = false;

  // Reference to child filter component for direct method calls
  @ViewChild(CarFiltersComponent) filtersComponent!: CarFiltersComponent;

  readonly today = new Date();
  searchLocation = '';
  searchStartDate: Date | null = null;
  searchStartTime = '';
  searchEndDate: Date | null = null;
  searchEndTime = '';
  locationError = '';
  startDateError = '';
  endDateError = '';
  startTimeError = '';
  endTimeError = '';
  dateRangeError = '';

  // Geospatial location state
  readonly locationInput$ = new Subject<string>();
  geocodeSuggestions: GeocodeSuggestion[] = [];
  selectedGeocodeSuggestion: GeocodeSuggestion | null = null;
  isLoadingGeocoding = false;
  isLoadingCurrentLocation = false;
  searchCenter = {
    latitude: DEFAULT_MAP_CENTER.latitude,
    longitude: DEFAULT_MAP_CENTER.longitude,
  };

  // Search state
  readonly searchCriteria$ = new BehaviorSubject<CarSearchCriteria>({
    page: 0,
    size: 20,
  });

  /**
   * Unified availability search state combining:
   * - Time-based availability (location + date/time range)
   * - Geospatial coordinates (latitude, longitude, radius)
   * - All filter criteria (price, make, transmission, features, etc.)
   * - Pagination and sorting
   *
   * This is the SINGLE SOURCE OF TRUTH for availability mode.
   * Filters are MERGED into this state, not stored separately.
   */
  readonly availabilityParams$ = new BehaviorSubject<AvailabilitySearchParams | null>(null);

  // Flag to track if we're in availability search mode
  readonly isAvailabilityMode$ = new BehaviorSubject<boolean>(false);

  // Loading state for smooth spinner overlay
  readonly isLoading$ = new BehaviorSubject<boolean>(false);

  // REMOVED: resetForm$ - no longer broadcast reset commands to child
  // Parent will call child.resetFilters() directly via ViewChild reference

  /**
   * Search results - conditionally uses availability or standard search.
   *
   * UPGRADED: In availability mode, all filters are now included in availabilityParams$
   * and sent to the backend for server-side filtering. This eliminates client-side
   * filtering in availability mode, improving performance significantly.
   *
   * Data Flow:
   * - Availability Mode: availabilityParams$ → CarService.searchAvailableCars() → server-side filtering
   * - Standard Mode: searchCriteria$ → CarService.searchCars() → server-side filtering
   */
  readonly searchResults$: Observable<PagedResponse<Car>> = combineLatest([
    this.isAvailabilityMode$,
    this.availabilityParams$,
    this.searchCriteria$,
  ]).pipe(
    debounceTime(100),
    distinctUntilChanged((prev, curr) => JSON.stringify(prev) === JSON.stringify(curr)),
    tap(() => this.isLoading$.next(true)),
    switchMap(([isAvailability, availParams, criteria]) => {
      if (isAvailability && availParams) {
        // UPGRADED: Availability search with unified params (includes filters)
        // Server-side filtering - no client-side applyFilters needed
        return this.carService.searchAvailableCars(availParams);
      } else {
        // Standard search mode (server-side filtering)
        // All filtering is applied server-side — no client-side re-filtering
        return this.carService.searchCars(criteria);
      }
    }),
    tap((results) => {
      this.debugLogCarImages(results.content);
      // Update URL with current state (instant sync)
      if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
        this.updateUrlParamsForAvailability(this.availabilityParams$.value);
      } else {
        this.updateUrlParams(this.searchCriteria$.value);
      }
      this.isLoading$.next(false);
    }),
    catchError((error) => {
      this.isLoading$.next(false);

      // Determine error message based on HTTP status
      let errorMessage = 'Došlo je do greške. Pokušajte ponovo.';

      if (error.status === 400) {
        errorMessage = 'Neispravan unos. Proverite datume i vreme.';
      } else if (error.status === 500) {
        errorMessage = 'Došlo je do greške na serveru. Pokušajte ponovo.';
      }

      // Show error message
      this.toast.error(errorMessage);

      // Return empty result set
      return of({
        content: [],
        totalElements: 0,
        totalPages: 0,
        currentPage: 0,
        pageSize: 20,
        hasNext: false,
        hasPrevious: false,
      } as PagedResponse<Car>);
    }),
  );

  // Active filter chips
  readonly activeFilterChips$ = new BehaviorSubject<
    Array<{ label: string; value: string; key: keyof CarSearchCriteria }>
  >([]);

  // Availability filter display
  readonly availabilityFilterDisplay$ = new BehaviorSubject<{
    location: string;
    dateTimeRange: string;
  } | null>(null);

  readonly timeOptions = [
    '06:00',
    '06:30',
    '07:00',
    '07:30',
    '08:00',
    '08:30',
    '09:00',
    '09:30',
    '10:00',
    '10:30',
    '11:00',
    '11:30',
    '12:00',
    '12:30',
    '13:00',
    '13:30',
    '14:00',
    '14:30',
    '15:00',
    '15:30',
    '16:00',
    '16:30',
    '17:00',
    '17:30',
    '18:00',
    '18:30',
    '19:00',
    '19:30',
    '20:00',
    '20:30',
    '21:00',
    '21:30',
    '22:00',
  ];

  get datesSelected(): boolean {
    return !!(this.searchStartDate && this.searchEndDate);
  }

  get hasActiveState(): boolean {
    const criteria = this.searchCriteria$.value;
    const hasFilters =
      !this.isCriteriaDefault(criteria) || (this.activeFilterChips$.value?.length ?? 0) > 0;
    const hasSearch =
      !!this.searchLocation.trim() ||
      !!this.searchStartDate ||
      !!this.searchEndDate ||
      !!this.searchStartTime ||
      !!this.searchEndTime ||
      this.isAvailabilityMode$.value ||
      !!this.availabilityParams$.value;
    return hasFilters || hasSearch;
  }

  private primaryValue(...values: Array<string | undefined | null>): string | undefined {
    for (const v of values) {
      if (v !== undefined && v !== null) {
        const trimmed = String(v).trim();
        if (trimmed.length > 0) {
          return trimmed;
        }
      }
    }
    return undefined;
  }

  private toCanonical(value: string): string {
    return value
      .replace(/đ/gi, 'd')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '');
  }

  private matchesAllTokens(candidate?: string, filter?: string): boolean {
    const target = this.toCanonical(candidate ?? '');
    const tokens = (filter ?? '')
      .split(/\s+/)
      .map((token) => this.toCanonical(token))
      .filter((token) => token.length > 0);

    if (tokens.length === 0) {
      return true;
    }

    return tokens.every((token) => target.includes(token));
  }

  ngOnInit(): void {
    // Initialize filters from URL query params
    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      const isAvailabilitySearch = params.get('availabilitySearch') === 'true';

      // Parse common filter params (used in both modes)
      const parsedFilters: CarSearchCriteria = {
        minPrice: params.get('minPrice') ? Number(params.get('minPrice')) : undefined,
        maxPrice: params.get('maxPrice') ? Number(params.get('maxPrice')) : undefined,
        make: params.get('make') || undefined,
        model: params.get('model') || undefined,
        minYear: params.get('minYear') ? Number(params.get('minYear')) : undefined,
        maxYear: params.get('maxYear') ? Number(params.get('maxYear')) : undefined,
        minSeats: params.get('minSeats') ? Number(params.get('minSeats')) : undefined,
        transmission: (params.get('transmission') as TransmissionType) || undefined,
        features: params.get('features')?.split(',').filter(Boolean) as Feature[] | undefined,
        page: params.get('page') ? Number(params.get('page')) : 0,
        size: params.get('size') ? Number(params.get('size')) : 20,
        sort: params.get('sort') || undefined,
      };

      if (isAvailabilitySearch) {
        // AVAILABILITY SEARCH MODE
        // Parse core availability params
        const location = params.get('location') || '';
        const startTimeISO = params.get('startTime') || '';
        const endTimeISO = params.get('endTime') || '';
        const page = params.get('page') ? Number(params.get('page')) : 0;
        const size = params.get('size') ? Number(params.get('size')) : 20;

        // Parse geospatial params (for page refresh recovery)
        const latitude = params.get('lat') ? Number(params.get('lat')) : undefined;
        const longitude = params.get('lng') ? Number(params.get('lng')) : undefined;
        const radiusKm = params.get('radiusKm') ? Number(params.get('radiusKm')) : 20;

        // Parse ISO strings to extract date and time for UI display
        let searchStartDate: Date | null = null;
        let searchStartTime = '';
        let searchEndDate: Date | null = null;
        let searchEndTime = '';

        if (startTimeISO && startTimeISO.includes('T')) {
          const startDateObj = new Date(startTimeISO);
          if (!isNaN(startDateObj.getTime())) {
            searchStartDate = startDateObj;
            const [, timePart] = startTimeISO.split('T');
            searchStartTime = timePart.substring(0, 5); // Extract HH:mm
          }
        }

        if (endTimeISO && endTimeISO.includes('T')) {
          const endDateObj = new Date(endTimeISO);
          if (!isNaN(endDateObj.getTime())) {
            searchEndDate = endDateObj;
            const [, timePart] = endTimeISO.split('T');
            searchEndTime = timePart.substring(0, 5); // Extract HH:mm
          }
        }

        // Build unified AvailabilitySearchParams (SINGLE SOURCE OF TRUTH)
        const availParams: AvailabilitySearchParams = {
          // Core availability
          location,
          startTime: startTimeISO,
          endTime: endTimeISO,

          // Geospatial (if available from URL)
          latitude,
          longitude,
          radiusKm,

          // Filters from URL
          minPrice: parsedFilters.minPrice,
          maxPrice: parsedFilters.maxPrice,
          make: parsedFilters.make,
          model: parsedFilters.model,
          minYear: parsedFilters.minYear,
          maxYear: parsedFilters.maxYear,
          minSeats: parsedFilters.minSeats,
          transmission: parsedFilters.transmission,
          features: parsedFilters.features ? [...parsedFilters.features] : undefined,
          sort: parsedFilters.sort,

          // Pagination
          page,
          size,
        };

        // Update unified availability state
        this.availabilityParams$.next(availParams);

        // Update UI state for display
        this.searchLocation = location;
        this.searchStartDate = searchStartDate;
        this.searchStartTime = searchStartTime;
        this.searchEndDate = searchEndDate;
        this.searchEndTime = searchEndTime;

        // Restore geospatial state if coordinates were in URL
        if (latitude !== undefined && longitude !== undefined) {
          this.searchCenter = { latitude, longitude };
          // Reconstruct a minimal GeocodeSuggestion for state consistency
          this.selectedGeocodeSuggestion = {
            id: 'restored-from-url',
            latitude,
            longitude,
            formattedAddress: location,
            address: location,
            city: location,
            country: 'Srbija',
            placeType: 'place',
          };
        }

        // Enable availability mode
        this.isAvailabilityMode$.next(true);

        // Update availability filter display (use extracted date/time for display)
        const displayStartDate = searchStartDate ? this.formatDate(searchStartDate) : '';
        const displayEndDate = searchEndDate ? this.formatDate(searchEndDate) : '';
        this.updateAvailabilityFilterDisplay(
          location,
          displayStartDate,
          searchStartTime,
          displayEndDate,
          searchEndTime,
        );

        // Update active filter chips from the unified params
        this.updateActiveFilterChips(extractFiltersFromAvailabilityParams(availParams));

        // Keep searchCriteria$ in sync (for filter component initialCriteria)
        this.searchCriteria$.next(parsedFilters);
      } else {
        // STANDARD SEARCH MODE
        // Disable availability mode
        this.isAvailabilityMode$.next(false);
        this.availabilityParams$.next(null);
        this.availabilityFilterDisplay$.next(null);
        this.searchLocation = '';
        this.searchStartDate = null;
        this.searchStartTime = '';
        this.searchEndDate = null;
        this.searchEndTime = '';

        // Update standard search criteria
        this.searchCriteria$.next(parsedFilters);
        this.updateActiveFilterChips(parsedFilters);
      }
    });

    // Setup geocoding autocomplete
    this.setupGeocodeAutocomplete();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private debugLogCarImages(cars: Car[]): void {
    if (environment.production) {
      return;
    }

    if (!this.loggedSample && cars.length > 0) {
      this.loggedSample = true;
      const sample = cars[0];
    }

    for (const car of cars) {
      const hasImageUrl = typeof car.imageUrl === 'string' && car.imageUrl.trim().length > 0;
      const hasImageUrls = Array.isArray(car.imageUrls) && car.imageUrls.length > 0;

      // Car cards render placeholder when BOTH imageUrls is empty and imageUrl is falsy.
      if (!hasImageUrl && !hasImageUrls && !this.loggedMissingImageForCarIds.has(car.id)) {
        this.loggedMissingImageForCarIds.add(car.id);
        console.warn('[CarList] Car has no images; placeholder expected', {
          carId: car.id,
          imageUrl: car.imageUrl,
          imageUrls: car.imageUrls,
        });
      }

      // If imageUrls exists but imageUrl is missing, some legacy templates still break.
      if (
        !hasImageUrl &&
        hasImageUrls &&
        !this.loggedMissingImageForCarIds.has(`${car.id}:missing-primary`)
      ) {
        this.loggedMissingImageForCarIds.add(`${car.id}:missing-primary`);
        console.warn(
          '[CarList] Missing car.imageUrl but imageUrls present (mapping/backfill issue)',
          {
            carId: car.id,
            imageUrl: car.imageUrl,
            imageUrls: car.imageUrls,
          },
        );
      }
    }
  }

  // ====== Geocoding Autocomplete Methods ======

  private setupGeocodeAutocomplete(): void {
    this.locationInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap((query) => {
          if (!query || query.length < 2) {
            this.geocodeSuggestions = [];
            this.isLoadingGeocoding = false;
            this.cdr.markForCheck();
          } else {
            this.isLoadingGeocoding = true;
            this.cdr.markForCheck();
          }
        }),
        switchMap((query) => {
          if (!query || query.length < 2) {
            return of([]);
          }
          return this.locationService.geocodeAddress(query).pipe(
            catchError((err) => {
              console.error('Geocoding error:', err);
              return of([]);
            }),
          );
        }),
        takeUntil(this.destroy$),
      )
      .subscribe((suggestions) => {
        this.geocodeSuggestions = suggestions;
        this.isLoadingGeocoding = false;
        this.cdr.markForCheck();
      });
  }

  onLocationInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchLocation = value;
    this.selectedGeocodeSuggestion = null;
    this.locationError = '';
    this.locationInput$.next(value);
  }

  onGeocodeSuggestionSelected(suggestion: GeocodeSuggestion): void {
    this.selectedGeocodeSuggestion = suggestion;
    this.searchLocation = suggestion.formattedAddress;
    this.searchCenter = {
      latitude: suggestion.latitude,
      longitude: suggestion.longitude,
    };
    this.geocodeSuggestions = [];
    this.locationError = '';
    this.cdr.markForCheck();
  }

  displayGeocodeSuggestion(suggestion: GeocodeSuggestion | null): string {
    return suggestion?.formattedAddress ?? '';
  }

  useCurrentLocation(): void {
    if (!navigator.geolocation) {
      this.toast.info('Geolokacija nije podržana u vašem pretraživaču.');
      return;
    }

    this.isLoadingCurrentLocation = true;
    this.cdr.markForCheck();

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const lat = position.coords.latitude;
        const lng = position.coords.longitude;

        this.searchCenter = { latitude: lat, longitude: lng };

        this.locationService.reverseGeocode(lat, lng).subscribe({
          next: (result) => {
            this.searchLocation = result.formattedAddress;
            // Construct GeocodeSuggestion from ReverseGeocodeResult + coordinates
            this.selectedGeocodeSuggestion = {
              id: 'current-location',
              latitude: lat,
              longitude: lng,
              formattedAddress: result.formattedAddress,
              address: result.address,
              city: result.city,
              zipCode: result.zipCode,
              country: result.country,
              placeType: result.placeType as any,
            };
            this.locationError = '';
            this.isLoadingCurrentLocation = false;
            this.cdr.markForCheck();
          },
          error: () => {
            // Use coordinates even without address
            this.searchLocation = `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
            this.selectedGeocodeSuggestion = {
              id: 'current-location',
              latitude: lat,
              longitude: lng,
              formattedAddress: `${lat.toFixed(4)}, ${lng.toFixed(4)}`,
              address: 'Trenutna lokacija',
              city: 'Nepoznato',
              country: 'Srbija',
              placeType: 'address',
            };
            this.locationError = '';
            this.isLoadingCurrentLocation = false;
            this.cdr.markForCheck();
          },
        });
      },
      (error) => {
        this.isLoadingCurrentLocation = false;
        let errorMessage = 'Nije moguće odrediti vašu lokaciju';
        switch (error.code) {
          case error.PERMISSION_DENIED:
            errorMessage =
              'Pristup lokaciji je odbijen. Omogućite pristup u podešavanjima pretraživača.';
            break;
          case error.POSITION_UNAVAILABLE:
            errorMessage = 'Informacije o lokaciji nisu dostupne.';
            break;
          case error.TIMEOUT:
            errorMessage = 'Zahtev za lokaciju je istekao.';
            break;
        }
        this.toast.error(errorMessage);
        this.cdr.markForCheck();
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
    );
  }

  // ====== Date/Time Change Handlers ======

  onStartDateChange(event: MatDatepickerInputEvent<Date>, endPicker: MatDatepicker<Date>): void {
    this.searchStartDate = event.value ?? null;
    this.searchStartTime = '09:00'; // Default to 9 AM for better UX
    this.searchEndTime = '09:00'; // Default to 9 AM for better UX
    this.startTimeError = '';
    this.endTimeError = '';

    if (this.searchEndDate && this.searchStartDate && this.searchEndDate <= this.searchStartDate) {
      this.searchEndDate = null;
    }

    this.validateField('startDate');

    if (this.searchStartDate && endPicker?.open) {
      setTimeout(() => endPicker.open());
    }
  }

  onEndDateChange(event: MatDatepickerInputEvent<Date>): void {
    this.searchEndDate = event.value ?? null;
    this.searchEndTime = '09:00'; // Default to 9 AM for better UX
    this.endTimeError = '';
    this.validateField('endDate');
  }

  searchAvailability(): void {
    this.clearSearchErrors();

    const location = this.searchLocation.trim();
    if (!location) {
      this.locationError = 'Unesite lokaciju';
    }

    // Geospatial validation: require selected geocode suggestion
    if (location && !this.selectedGeocodeSuggestion) {
      this.locationError = 'Odaberite lokaciju iz predloga';
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

    if (
      this.locationError ||
      this.startDateError ||
      this.endDateError ||
      this.startTimeError ||
      this.endTimeError
    ) {
      return;
    }

    // Use the selected suggestion's city/address for search
    // Skip 'Nepoznato' city fallback - prefer formattedAddress in that case
    const city = this.selectedGeocodeSuggestion?.city;
    const searchLocationValue =
      city && city !== 'Nepoznato'
        ? city
        : this.selectedGeocodeSuggestion?.formattedAddress || location;

    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);
    if ((this.searchStartDate as Date) < todayStart) {
      this.startDateError = 'Početni datum mora biti danas ili kasnije';
      return;
    }

    const startDateTime = new Date(this.searchStartDate as Date);
    const [startHour, startMinute] = this.searchStartTime.split(':').map((v) => parseInt(v, 10));
    startDateTime.setHours(startHour, startMinute);

    const endDateTime = new Date(this.searchEndDate as Date);
    const [endHour, endMinute] = this.searchEndTime.split(':').map((v) => parseInt(v, 10));
    endDateTime.setHours(endHour, endMinute);

    if (endDateTime <= startDateTime) {
      this.dateRangeError = 'Krajnji datum mora biti posle početnog';
      return;
    }

    // ========================================================================
    // PHASE 2: Lead Time Validation (Frontend Guard)
    // ========================================================================
    // Validate that booking starts at least 1 hour from now.
    // This is a UX improvement - backend has authoritative validation.
    // ========================================================================
    const leadTimeResult = validateLeadTime(startDateTime, DEFAULT_ADVANCE_NOTICE_HOURS);
    if (!leadTimeResult.valid) {
      this.startDateError = leadTimeResult.errorMessage || 'Prerano početno vreme';
      return;
    }

    // ========================================================================
    // PHASE 2: Minimum Duration Validation (Frontend Guard)
    // ========================================================================
    // Validate 24-hour minimum trip duration (system default).
    // Individual cars may have different minimums - enforced at booking time.
    // ========================================================================
    const minDurationResult = validateMinimumDuration(
      startDateTime,
      endDateTime,
      DEFAULT_MIN_TRIP_HOURS,
    );
    if (!minDurationResult.valid) {
      this.dateRangeError = minDurationResult.errorMessage || 'Prekratko trajanje';
      return;
    }

    // Combine date + time into ISO strings (matching home.component.ts)
    const startTimeISO = this.combineDateTime(this.searchStartDate as Date, this.searchStartTime);
    const endTimeISO = this.combineDateTime(this.searchEndDate as Date, this.searchEndTime);
    const pageSize = this.searchCriteria$.value.size ?? 20;

    // UPGRADED: Build unified AvailabilitySearchParams with geospatial coordinates
    // This passes coordinates from geocoding directly to backend for spatial search
    const availParams: AvailabilitySearchParams = {
      // Core availability
      location: searchLocationValue,
      startTime: startTimeISO,
      endTime: endTimeISO,

      // Geospatial coordinates from geocoding (CRITICAL for spatial index search)
      latitude: this.selectedGeocodeSuggestion?.latitude ?? this.searchCenter.latitude,
      longitude: this.selectedGeocodeSuggestion?.longitude ?? this.searchCenter.longitude,
      radiusKm: 20, // Default 20km radius

      // No filters on initial search (user hasn't applied any yet)
      // Filters will be merged in via onFiltersChanged()

      // Pagination
      page: 0,
      size: pageSize,
    };

    // Set unified state
    this.availabilityParams$.next(availParams);
    this.isAvailabilityMode$.next(true);

    // Update display state
    this.availabilityFilterDisplay$.next(null);
    const startDate = this.formatDate(this.searchStartDate as Date);
    const endDate = this.formatDate(this.searchEndDate as Date);
    this.updateAvailabilityFilterDisplay(
      searchLocationValue,
      startDate,
      this.searchStartTime,
      endDate,
      this.searchEndTime,
    );

    // Clear filter chips (new search starts with no filters)
    this.activeFilterChips$.next([]);

    // Keep searchCriteria$ in sync (for filter component)
    this.searchCriteria$.next({ page: 0, size: pageSize });
  }

  /**
   * Handle filter changes from the filter component.
   *
   * UPGRADED: In availability mode, filters are MERGED into availabilityParams$
   * instead of being stored separately in searchCriteria$. This ensures:
   * 1. Server-side filtering via unified DTO
   * 2. URL persistence of all state
   * 3. Single source of truth for availability searches
   */
  onFiltersChanged(criteria: CarSearchCriteria): void {
    if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
      // AVAILABILITY MODE: Merge filters into unified availabilityParams$
      const mergedParams = mergeFiltersIntoAvailabilityParams(
        this.availabilityParams$.value,
        criteria,
      );
      this.availabilityParams$.next(mergedParams);

      // Update filter chips from merged params
      this.updateActiveFilterChips(extractFiltersFromAvailabilityParams(mergedParams));

      // Keep searchCriteria$ in sync for filter component state
      this.searchCriteria$.next({
        ...criteria,
        page: 0,
        size: mergedParams.size,
      });
    } else {
      // STANDARD MODE: Update searchCriteria$ as before
      const updated = { ...criteria, page: 0, size: this.searchCriteria$.value.size };
      this.searchCriteria$.next(updated);
      this.updateActiveFilterChips(updated);
    }
  }

  onPageChange(event: PageEvent): void {
    if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
      // Availability mode - update availability params
      this.availabilityParams$.next({
        ...this.availabilityParams$.value,
        page: event.pageIndex,
        size: event.pageSize,
      });
    } else {
      // Standard mode - update criteria
      const currentCriteria = this.searchCriteria$.value;
      this.searchCriteria$.next({
        ...currentCriteria,
        page: event.pageIndex,
        size: event.pageSize,
      });
    }

    // Scroll to top on page change
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /**
   * Remove a filter chip.
   *
   * UPGRADED: In availability mode, also removes the filter from availabilityParams$.
   */
  removeFilterChip(key: keyof CarSearchCriteria): void {
    const currentCriteria = { ...this.searchCriteria$.value };

    // Special handling for price and year ranges (remove both min/max)
    if (key === 'minPrice') {
      delete currentCriteria.minPrice;
      delete currentCriteria.maxPrice;
    } else if (key === 'minYear') {
      delete currentCriteria.minYear;
      delete currentCriteria.maxYear;
    } else {
      // Remove the specific filter
      delete currentCriteria[key];
    }

    // Reset to page 0
    currentCriteria.page = 0;

    // Update criteria
    this.searchCriteria$.next(currentCriteria);

    // UPGRADED: Also update availabilityParams$ in availability mode
    if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
      const currentAvailParams = this.availabilityParams$.value;
      const updatedAvailParams: AvailabilitySearchParams = {
        ...currentAvailParams,
        page: 0,
      };

      // Remove the filter from availability params
      if (key === 'minPrice') {
        updatedAvailParams.minPrice = undefined;
        updatedAvailParams.maxPrice = undefined;
      } else if (key === 'minYear') {
        updatedAvailParams.minYear = undefined;
        updatedAvailParams.maxYear = undefined;
      } else if (key === 'make') {
        updatedAvailParams.make = undefined;
      } else if (key === 'model') {
        updatedAvailParams.model = undefined;
      } else if (key === 'minSeats') {
        updatedAvailParams.minSeats = undefined;
      } else if (key === 'transmission') {
        updatedAvailParams.transmission = undefined;
      } else if (key === 'features') {
        updatedAvailParams.features = undefined;
      }

      this.availabilityParams$.next(updatedAvailParams);
      this.updateActiveFilterChips(extractFiltersFromAvailabilityParams(updatedAvailParams));
    } else {
      // Update active filter chips for standard mode
      this.updateActiveFilterChips(currentCriteria);
      // Immediately sync URL to reflect removed filter
      this.syncUrlToActiveCriteria(currentCriteria);
    }
  }

  clearAllFilters(): void {
    this.filtersOnlyReset();
  }

  onResetFilters(): void {
    this.filtersOnlyReset();
  }

  /**
   * NEW: Handler for "Obriši sve filtere" button clicks on the main page.
   * This directly calls the child filter component's resetFilters() method.
   * The child will then emit resetTriggered, which calls onResetFilters() above.
   * This establishes one-way flow: UI click → child reset → parent clears.
   */
  onClearAllFiltersClick(): void {
    if (this.filtersComponent) {
      this.filtersComponent.resetFilters(true);
    }
  }

  private updateActiveFilterChips(criteria: CarSearchCriteria): void {
    const chips: Array<{ label: string; value: string; key: keyof CarSearchCriteria }> = [];

    if (criteria.minPrice !== undefined || criteria.maxPrice !== undefined) {
      const min = criteria.minPrice ?? 0;
      const max = criteria.maxPrice ?? 500;
      chips.push({
        label: 'Cena',
        value: `${min} - ${max} RSD`,
        key: 'minPrice',
      });
    }

    if (criteria.make) {
      chips.push({ label: 'Marka', value: criteria.make, key: 'make' });
    }

    if (criteria.model) {
      chips.push({ label: 'Model', value: criteria.model, key: 'model' });
    }

    if (criteria.minYear !== undefined || criteria.maxYear !== undefined) {
      const min = criteria.minYear ?? 2000;
      const max = criteria.maxYear ?? new Date().getFullYear();
      chips.push({
        label: 'Godina',
        value: `${min} - ${max}`,
        key: 'minYear',
      });
    }

    if (criteria.minSeats !== undefined) {
      chips.push({ label: 'Sedišta', value: `${criteria.minSeats}+`, key: 'minSeats' });
    }

    if (criteria.transmission) {
      const transmissionLabel =
        criteria.transmission === TransmissionType.AUTOMATIC ? 'Automatski' : 'Manuelni';
      chips.push({ label: 'Menjač', value: transmissionLabel, key: 'transmission' });
    }

    if (criteria.features && criteria.features.length > 0) {
      chips.push({
        label: 'Oprema',
        value: `${criteria.features.length} opcija`,
        key: 'features',
      });
    }

    this.activeFilterChips$.next(chips);
  }

  /**
   * Updates URL params to match criteria (used during normal search flow)
   */
  private updateUrlParams(criteria: CarSearchCriteria): void {
    const queryParams = this.buildQueryParamsFromCriteria(criteria);

    // Use replaceUrl without merge to completely replace query params
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  /**
   * Builds a query params object from a criteria object.
   * This is the single source of truth for URL param generation.
   */
  private buildQueryParamsFromCriteria(criteria: CarSearchCriteria): any {
    const queryParams: any = {};

    // Only add non-default values to URL
    if (criteria.minPrice !== undefined && criteria.minPrice > 0) {
      queryParams.minPrice = criteria.minPrice;
    }
    if (criteria.maxPrice !== undefined && criteria.maxPrice < 500) {
      // Assuming 500 is the max default
      queryParams.maxPrice = criteria.maxPrice;
    }
    if (criteria.make) queryParams.make = criteria.make;
    if (criteria.model) queryParams.model = criteria.model;
    if (criteria.minYear !== undefined && criteria.minYear > 2000) {
      // Assuming 2000 is the min default
      queryParams.minYear = criteria.minYear;
    }
    if (criteria.maxYear !== undefined && criteria.maxYear < new Date().getFullYear() + 1) {
      // Assuming this is max default
      queryParams.maxYear = criteria.maxYear;
    }
    if (criteria.minSeats !== undefined) queryParams.minSeats = criteria.minSeats;
    if (criteria.transmission) queryParams.transmission = criteria.transmission;
    if (criteria.features && criteria.features.length > 0) {
      queryParams.features = criteria.features.join(',');
    }
    if (criteria.page && criteria.page > 0) queryParams.page = criteria.page;
    if (criteria.size && criteria.size !== 20) queryParams.size = criteria.size;
    if (criteria.sort) queryParams.sort = criteria.sort;

    return queryParams;
  }

  /**
   * Synchronizes URL query params to exactly match active criteria.
   * Used after filter removal to ensure clean URL state (no stale params).
   */
  private syncUrlToActiveCriteria(criteria: CarSearchCriteria): void {
    const queryParams = this.buildQueryParamsFromCriteria(criteria);

    // Navigate with clean params (replaces all, doesn't merge)
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  private isCriteriaDefault(criteria: CarSearchCriteria): boolean {
    return (
      criteria.minPrice === undefined &&
      criteria.maxPrice === undefined &&
      criteria.make === undefined &&
      criteria.model === undefined &&
      criteria.minYear === undefined &&
      criteria.maxYear === undefined &&
      criteria.minSeats === undefined &&
      criteria.transmission === undefined &&
      (!criteria.features || criteria.features.length === 0) &&
      (!criteria.sort || criteria.sort === '' || criteria.sort === undefined) &&
      (!criteria.page || criteria.page === 0) &&
      (!criteria.size || criteria.size === 20)
    );
  }

  /**
   * Reset only filter criteria while preserving availability search state.
   *
   * UPGRADED: In availability mode, clears filter fields from availabilityParams$
   * while preserving location, time range, and geospatial coordinates.
   */
  private filtersOnlyReset(): void {
    const defaultCriteria: CarSearchCriteria = {
      page: 0,
      size: this.searchCriteria$.value.size ?? 20,
    };

    this.searchCriteria$.next(defaultCriteria);
    this.updateActiveFilterChips(defaultCriteria);

    // UPGRADED: Also clear filter fields from availabilityParams$ in availability mode
    if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
      const currentAvailParams = this.availabilityParams$.value;

      // Create new params with filters cleared but core params preserved
      const clearedAvailParams: AvailabilitySearchParams = {
        // Preserve core availability params
        location: currentAvailParams.location,
        startTime: currentAvailParams.startTime,
        endTime: currentAvailParams.endTime,

        // Preserve geospatial params
        latitude: currentAvailParams.latitude,
        longitude: currentAvailParams.longitude,
        radiusKm: currentAvailParams.radiusKm,

        // Clear all filter params
        minPrice: undefined,
        maxPrice: undefined,
        make: undefined,
        model: undefined,
        minYear: undefined,
        maxYear: undefined,
        minSeats: undefined,
        transmission: undefined,
        features: undefined,
        sort: undefined,

        // Reset pagination
        page: 0,
        size: currentAvailParams.size,
      };

      this.availabilityParams$.next(clearedAvailParams);
      // URL will be updated by the search pipeline tap()
    } else {
      this.updateUrlParams(defaultCriteria);
    }
  }

  resetAll(): void {
    this.searchLocation = '';
    this.searchStartDate = null;
    this.searchStartTime = '';
    this.searchEndDate = null;
    this.searchEndTime = '';
    this.clearSearchErrors();

    // Clear geospatial state
    this.selectedGeocodeSuggestion = null;
    this.searchCenter = {
      latitude: DEFAULT_MAP_CENTER.latitude,
      longitude: DEFAULT_MAP_CENTER.longitude,
    };

    const defaultCriteria: CarSearchCriteria = { page: 0, size: 20 };
    this.searchCriteria$.next(defaultCriteria);
    this.activeFilterChips$.next([]);
    this.availabilityParams$.next(null);
    this.isAvailabilityMode$.next(false);
    this.availabilityFilterDisplay$.next(null);

    if (this.filtersComponent) {
      this.filtersComponent.resetFilters(false);
    }

    this.router.navigate(['/cars'], { replaceUrl: true });
  }

  private applyFilters(
    results: PagedResponse<Car>,
    criteria: CarSearchCriteria,
    isAvailability: boolean,
  ): PagedResponse<Car> {
    const hasFilters =
      (criteria.minPrice ?? null) !== null ||
      (criteria.maxPrice ?? null) !== null ||
      !!criteria.make ||
      !!criteria.model ||
      (criteria.minYear ?? null) !== null ||
      (criteria.maxYear ?? null) !== null ||
      (criteria.minSeats ?? null) !== null ||
      !!criteria.transmission ||
      (criteria.features && criteria.features.length > 0);

    if (!hasFilters) {
      return results;
    }

    const filtered = results.content.filter((car) => this.matchesFilters(car, criteria));

    const pageSize = isAvailability
      ? (this.availabilityParams$.value?.size ?? results.pageSize ?? 20)
      : (this.searchCriteria$.value.size ?? results.pageSize ?? 20);
    const requestedPage = isAvailability
      ? (this.availabilityParams$.value?.page ?? 0)
      : (this.searchCriteria$.value.page ?? results.currentPage ?? 0);

    const totalPages = Math.ceil(filtered.length / pageSize) || 0;
    const boundedPageIndex = Math.min(requestedPage, Math.max(totalPages - 1, 0));

    const start = boundedPageIndex * pageSize;
    const end = start + pageSize;
    const pagedContent = filtered.slice(start, end);

    if (isAvailability && this.availabilityParams$.value && boundedPageIndex !== requestedPage) {
      this.availabilityParams$.next({
        ...this.availabilityParams$.value,
        page: boundedPageIndex,
      });
    } else if (!isAvailability && boundedPageIndex !== requestedPage) {
      this.searchCriteria$.next({
        ...this.searchCriteria$.value,
        page: boundedPageIndex,
      });
    }

    return {
      ...results,
      content: pagedContent,
      totalElements: filtered.length,
      totalPages,
      currentPage: boundedPageIndex,
      pageSize,
      hasNext: boundedPageIndex < totalPages - 1,
      hasPrevious: boundedPageIndex > 0,
    };
  }

  private matchesFilters(car: Car, criteria: CarSearchCriteria): boolean {
    const makeCandidates = [car.make, (car as any).brand, car.model];
    const modelCandidates = [car.model, (car as any).brand, car.make];

    if (criteria.minPrice !== undefined && car.pricePerDay < criteria.minPrice) return false;
    if (criteria.maxPrice !== undefined && car.pricePerDay > criteria.maxPrice) return false;

    if (criteria.make) {
      const matchMake = makeCandidates.some((candidate) =>
        this.matchesAllTokens(candidate ?? '', criteria.make),
      );
      if (!matchMake) return false;
    }

    if (criteria.model) {
      const matchModel = modelCandidates.some((candidate) =>
        this.matchesAllTokens(candidate ?? '', criteria.model),
      );
      if (!matchModel) return false;
    }

    if (criteria.minYear !== undefined && car.year < criteria.minYear) return false;
    if (criteria.maxYear !== undefined && car.year > criteria.maxYear) return false;
    if (criteria.minSeats !== undefined && (car.seats ?? 0) < criteria.minSeats) return false;

    if (criteria.transmission) {
      if (car.transmissionType !== criteria.transmission) return false;
    }

    if (criteria.features && criteria.features.length > 0) {
      const carFeatures = car.features ?? [];
      const missing = criteria.features.some((f) => !carFeatures.includes(f));
      if (missing) return false;
    }

    return true;
  }

  trackByCarId(_index: number, car: Car): string {
    return car.id;
  }

  /**
   * Update availability filter display with formatted date/time range
   */
  private updateAvailabilityFilterDisplay(
    location: string,
    startDate: string,
    startTime: string,
    endDate: string,
    endTime: string,
  ): void {
    // Format dates to dd.MM.yyyy HH:mm
    const formattedStart = this.formatDateTime(startDate, startTime);
    const formattedEnd = this.formatDateTime(endDate, endTime);

    this.availabilityFilterDisplay$.next({
      location: location || 'Sve lokacije',
      dateTimeRange: `${formattedStart} → ${formattedEnd}`,
    });
  }

  /**
   * Format date and time to user-friendly display format (dd.MM.yyyy HH:mm)
   */
  private formatDateTime(date: string, time: string): string {
    if (!date || !time) return '';

    try {
      // Parse YYYY-MM-DD
      const [year, month, day] = date.split('-');
      return `${day}.${month}.${year} ${time}`;
    } catch {
      return `${date} ${time}`;
    }
  }

  private formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
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
        if (this.searchStartDate && this.searchEndDate) {
          const start = new Date(this.searchStartDate);
          const end = new Date(this.searchEndDate);
          this.dateRangeError = end <= start ? 'Krajnji datum mora biti posle početnog' : '';
        }
        break;
    }
  }

  private clearSearchErrors(): void {
    this.locationError = '';
    this.startDateError = '';
    this.endDateError = '';
    this.startTimeError = '';
    this.endTimeError = '';
    this.dateRangeError = '';
  }

  /**
   * Update URL params for availability search mode.
   *
   * UPGRADED: Now serializes the unified AvailabilitySearchParams including:
   * - Core availability: location, startTime, endTime
   * - Geospatial: lat, lng, radiusKm
   * - All filter params: minPrice, maxPrice, make, model, year, seats, transmission, features
   * - Pagination: page, size
   *
   * This enables full page refresh recovery with all state intact.
   */
  private updateUrlParamsForAvailability(params: AvailabilitySearchParams): void {
    const queryParams: Record<string, string | number | boolean> = {
      availabilitySearch: 'true',
      location: params.location,
      startTime: params.startTime,
      endTime: params.endTime,
    };

    // Geospatial params (preserve coordinates for page refresh)
    if (params.latitude !== undefined) {
      queryParams['lat'] = params.latitude;
    }
    if (params.longitude !== undefined) {
      queryParams['lng'] = params.longitude;
    }
    if (params.radiusKm !== undefined) {
      queryParams['radiusKm'] = params.radiusKm;
    }

    // Filter params (only non-default values)
    if (params.minPrice !== undefined && params.minPrice > 0) {
      queryParams['minPrice'] = params.minPrice;
    }
    if (params.maxPrice !== undefined) {
      queryParams['maxPrice'] = params.maxPrice;
    }
    if (params.make) {
      queryParams['make'] = params.make;
    }
    if (params.model) {
      queryParams['model'] = params.model;
    }
    if (params.minYear !== undefined) {
      queryParams['minYear'] = params.minYear;
    }
    if (params.maxYear !== undefined) {
      queryParams['maxYear'] = params.maxYear;
    }
    if (params.minSeats !== undefined) {
      queryParams['minSeats'] = params.minSeats;
    }
    if (params.transmission) {
      queryParams['transmission'] = params.transmission;
    }
    if (params.features && params.features.length > 0) {
      queryParams['features'] = params.features.join(',');
    }
    if (params.sort) {
      queryParams['sort'] = params.sort;
    }

    // Pagination (only non-default values)
    if (params.page > 0) {
      queryParams['page'] = params.page;
    }
    if (params.size !== 20) {
      queryParams['size'] = params.size;
    }

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  // ====== Image Error Handling ======

  /**
   * Handle broken car images by falling back to placeholder SVG.
   * Prevents broken image icons from showing in the UI.
   */
  protected handleImageError(event: Event): void {
    const img = event.target as HTMLImageElement;
    if (img && !img.src.includes('placeholder-car.svg')) {
      img.src = '/images/placeholder-car.svg';
      img.alt = 'Slika nije dostupna';
    }
  }
}