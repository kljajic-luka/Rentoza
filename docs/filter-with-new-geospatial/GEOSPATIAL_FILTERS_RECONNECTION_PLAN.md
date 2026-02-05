# Geospatial Car-List Filters Reconnection Plan

**Status**: Strategic Planning Phase  
**Date**: 2025-12-06  
**Scope**: Re-integrate car-filters with geospatial availability search  
**Owner**: Frontend & Backend Architecture Teams  

---

## Executive Summary

The car-list component's migration to geospatial location search (Nominatim-backed) introduces a breaking change in how availability searches are initiated and filtered. The current filter components (`car-filters.component.ts`, `car-filters-dialog.component.ts`) were designed to filter results from **static availability searches** (location + time range), but now operate in a **hybrid dual-mode environment**:

1. **Availability Mode** (NEW): Location search via geospatial coordinates + time range → available cars → filtered
2. **Standard Mode** (LEGACY): General car search with 6+ filter dimensions (price, make, model, year, seats, features)

**Critical Issue**: Filters only apply to results from the active mode. When switching between modes or using filters on availability results, the state becomes inconsistent.

---

## Problem Analysis

### Current Architecture (Pre-Geospatial)

```
User Input (location city string)
    ↓
searchAvailability() validation
    ↓
CarService.searchAvailableCars(location: string, startTime, endTime)
    ↓
Backend: AvailabilityService.searchAvailableCars()
    ├─ Find cars by location (string matching: "Beograd")
    ├─ Filter unavailable cars (booking conflicts, blocked dates)
    └─ Return PagedResponse<Car>
    ↓
Frontend: Apply client-side filters (price, make, features, etc.)
    ↓
Display filtered results
```

### New Architecture (Geospatial)

```
User Input (geocoded suggestion with lat/lng)
    ↓
onGeocodeSuggestionSelected(GeocodeSuggestion)
    ├─ Update: searchCenter (latitude, longitude)
    ├─ Update: selectedGeocodeSuggestion.city
    └─ searchAvailability() uses selectedGeocodeSuggestion.city
    ↓
searchAvailability() validation (now includes geospatial data)
    ↓
CarService.searchAvailableCars(location: string, ...)
    ├─ ISSUE: Backend still receives STRING location ("Beograd")
    ├─ But frontend has geospatial coordinates available
    └─ Coordinate data NOT sent to backend
    ↓
Backend: AvailabilityService.searchAvailableCars()
    ├─ Receives only location string
    ├─ Cannot leverage GeoPoint for radius-based filtering
    └─ Returns all available cars in city (large result set)
    ↓
Frontend: Apply client-side filters (now receives MORE cars than before)
    ├─ ISSUE: Large result set = poor pagination UX
    ├─ ISSUE: Filters don't reduce visibility into "why" a car was excluded
    └─ ISSUE: No backend-side filtering by features/transmission/seats
    ↓
Display filtered results (with client-side filtering lag)
```

### Root Causes

1. **Geospatial Coordinates Lost in Transit**
   - Frontend has `selectedGeocodeSuggestion.latitude/longitude`
   - Backend `AvailabilityService.searchAvailableCars()` receives only `location: String`
   - Coordinates are NOT passed to backend in URL or request body

2. **Filter Application Timing Mismatch**
   - Client-side filters applied AFTER backend returns full city results
   - Should be applied server-side for performance + consistency
   - Backend `AvailabilityService` has NO filter support (price, make, features)

3. **Two Search Modes Competing for State**
   - `searchCriteria$` (standard search filters)
   - `availabilityParams$` (availability search params)
   - Filters stored in `searchCriteria$` but not merged into `availabilityParams$` in URL
   - Page refresh loses filter state when in availability mode

4. **Missing Geospatial Query Path**
   - Backend has `findNearby(lat, lng, radiusKm)` but frontend never calls it
   - Only uses legacy `findByLocation()` string matching
   - No radius parameter passed from frontend

---

## Solution Architecture

### Phase 1: Frontend State Management (car-list.component.ts)

**Objective**: Unify filter state across both search modes

#### 1.1 Extend availabilityParams to Include Filters

```typescript
// Current structure (lines 144-152)
readonly availabilityParams$ = new BehaviorSubject<{
  location: string;
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  page: number;
  size: number;
} | null>(null);

// NEW: Extend to include geospatial + filter data
readonly availabilityParams$ = new BehaviorSubject<{
  // Location (geospatial)
  location: string;
  latitude?: number;
  longitude?: number;
  radiusKm?: number; // Default: 25 km or user-selected
  
  // Time range
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  
  // Filters (merged from searchCriteria$ when in availability mode)
  minPrice?: number;
  maxPrice?: number;
  make?: string;
  model?: string;
  minYear?: number;
  maxYear?: number;
  minSeats?: number;
  transmission?: string;
  features?: string[];
  sort?: string;
  
  // Pagination
  page: number;
  size: number;
} | null>(null);
```

#### 1.2 Add GeospatialCoordinates to selectedGeocodeSuggestion

```typescript
selectedGeocodeSuggestion: GeocodeSuggestion | null = null;

// GeocodeSuggestion already contains:
// - latitude: number
// - longitude: number
// - city: string
// - formattedAddress: string
// (No changes needed - already has geospatial data)
```

#### 1.3 Modify searchAvailability() to Include Coordinates & Filters

**Current Logic (lines 461-525)**:
```typescript
searchAvailability(): void {
  // ... validation ...
  
  const params = {
    location: normalizedLocation,
    startDate: '', 
    startTime: startTimeISO,
    endDate: '',
    endTime: endTimeISO,
    page: 0,
    size: pageSize,
  };
  
  this.availabilityParams$.next(params);
  this.isAvailabilityMode$.next(true);
}
```

**NEW Logic**:
```typescript
searchAvailability(): void {
  // ... validation ...
  
  if (!this.selectedGeocodeSuggestion) {
    this.locationError = 'Odaberite lokaciju iz ponuđenih opcija ili označite na mapi';
    return;
  }
  
  // Build params with geospatial + filters
  const params = {
    // Geospatial
    location: this.selectedGeocodeSuggestion.city,
    latitude: this.selectedGeocodeSuggestion.latitude,
    longitude: this.selectedGeocodeSuggestion.longitude,
    radiusKm: 25, // OR fetch from component property
    
    // Time
    startDate: '',
    startTime: startTimeISO,
    endDate: '',
    endTime: endTimeISO,
    
    // Filters (merge from searchCriteria$ active filters)
    ...(this.shouldIncludeActiveFilters() ? this.getActiveFiltersForAvailability() : {}),
    
    // Pagination
    page: 0,
    size: pageSize,
  };
  
  this.availabilityParams$.next(params);
  this.isAvailabilityMode$.next(true);
}

// Helper: Extract active filters from searchCriteria$
private getActiveFiltersForAvailability(): Partial<typeof availabilityParams> {
  const criteria = this.searchCriteria$.value;
  const filters: any = {};
  
  if (criteria.minPrice !== undefined) filters.minPrice = criteria.minPrice;
  if (criteria.maxPrice !== undefined) filters.maxPrice = criteria.maxPrice;
  if (criteria.make) filters.make = criteria.make;
  if (criteria.model) filters.model = criteria.model;
  if (criteria.minYear !== undefined) filters.minYear = criteria.minYear;
  if (criteria.maxYear !== undefined) filters.maxYear = criteria.maxYear;
  if (criteria.minSeats !== undefined) filters.minSeats = criteria.minSeats;
  if (criteria.transmission) filters.transmission = criteria.transmission;
  if (criteria.features?.length) filters.features = criteria.features;
  if (criteria.sort) filters.sort = criteria.sort;
  
  return filters;
}

// Helper: Check if any non-default filters are active
private shouldIncludeActiveFilters(): boolean {
  return (this.activeFilterChips$.value?.length ?? 0) > 0;
}
```

#### 1.4 Update URL Persistence for Availability Mode

**Current URL (lines 960-999)**:
```
/cars?availabilitySearch=true&location=Beograd&startTime=2025-12-02T09:00&endTime=2025-12-02T18:00&page=0&size=20
```

**NEW URL** (with geospatial + filters):
```
/cars?availabilitySearch=true
      &location=Beograd
      &lat=44.816666
      &lng=20.458889
      &radiusKm=25
      &startTime=2025-12-02T09:00
      &endTime=2025-12-02T18:00
      &minPrice=50&maxPrice=300
      &make=BMW
      &transmission=AUTOMATIC
      &features=AIR_CONDITIONING,BLUETOOTH
      &sort=PRICE_ASC
      &page=0&size=20
```

**Implementation**:
```typescript
private updateUrlParamsForAvailability(params: AvailabilitySearchParams): void {
  const queryParams: Record<string, any> = {
    availabilitySearch: 'true',
    location: params.location,
  };
  
  // Add geospatial coordinates
  if (params.latitude !== undefined && params.longitude !== undefined) {
    queryParams.lat = params.latitude.toFixed(6);
    queryParams.lng = params.longitude.toFixed(6);
  }
  if (params.radiusKm !== undefined) {
    queryParams.radiusKm = params.radiusKm;
  }
  
  // Add time range
  queryParams.startTime = params.startTime;
  queryParams.endTime = params.endTime;
  
  // Add filters (only non-default values)
  if (params.minPrice !== undefined) queryParams.minPrice = params.minPrice;
  if (params.maxPrice !== undefined) queryParams.maxPrice = params.maxPrice;
  if (params.make) queryParams.make = params.make;
  if (params.model) queryParams.model = params.model;
  if (params.minYear !== undefined) queryParams.minYear = params.minYear;
  if (params.maxYear !== undefined) queryParams.maxYear = params.maxYear;
  if (params.minSeats !== undefined) queryParams.minSeats = params.minSeats;
  if (params.transmission) queryParams.transmission = params.transmission;
  if (params.features?.length) queryParams.features = params.features.join(',');
  if (params.sort) queryParams.sort = params.sort;
  
  // Pagination
  if (params.page > 0) queryParams.page = params.page;
  if (params.size !== 20) queryParams.size = params.size;
  
  this.router.navigate([], {
    relativeTo: this.route,
    queryParams,
    replaceUrl: true,
  });
}
```

#### 1.5 Update ngOnInit() to Restore Full Availability State

**Current Logic (lines 328-423)**:
- Restores location, time range from URL
- Does NOT restore coordinates or filters
- Creates minimal GeocodeSuggestion from URL params

**NEW Logic**:
```typescript
ngOnInit(): void {
  this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
    const isAvailabilitySearch = params.get('availabilitySearch') === 'true';
    
    if (isAvailabilitySearch) {
      // Parse geospatial + filters
      const location = params.get('location') || '';
      const lat = params.get('lat') ? parseFloat(params.get('lat')!) : undefined;
      const lng = params.get('lng') ? parseFloat(params.get('lng')!) : undefined;
      const radiusKm = params.get('radiusKm') ? parseInt(params.get('radiusKm')!) : 25;
      
      // Restore selectedGeocodeSuggestion with coordinates
      if (lat !== undefined && lng !== undefined) {
        this.selectedGeocodeSuggestion = {
          id: 'restored-from-url',
          latitude: lat,
          longitude: lng,
          formattedAddress: location,
          address: location,
          city: params.get('city') || location.split(',')[0]?.trim() || 'Nepoznato',
          country: 'Srbija',
          placeType: 'address',
        };
        this.searchCenter = { latitude: lat, longitude: lng };
      }
      
      this.searchLocation = location;
      
      // Parse filters from URL
      const filterCriteria: CarSearchCriteria = {
        minPrice: params.get('minPrice') ? Number(params.get('minPrice')) : undefined,
        maxPrice: params.get('maxPrice') ? Number(params.get('maxPrice')) : undefined,
        make: params.get('make') || undefined,
        model: params.get('model') || undefined,
        minYear: params.get('minYear') ? Number(params.get('minYear')) : undefined,
        maxYear: params.get('maxYear') ? Number(params.get('maxYear')) : undefined,
        minSeats: params.get('minSeats') ? Number(params.get('minSeats')) : undefined,
        transmission: params.get('transmission') as TransmissionType | undefined,
        features: params.get('features')?.split(',').filter(Boolean) as Feature[] | undefined,
        sort: params.get('sort') || undefined,
      };
      
      this.searchCriteria$.next(filterCriteria);
      this.updateActiveFilterChips(filterCriteria);
      
      // Restore availability params with ALL data
      const availParams: AvailabilitySearchParams = {
        location,
        latitude: lat,
        longitude: lng,
        radiusKm,
        startDate: '',
        startTime: params.get('startTime') || '',
        endDate: '',
        endTime: params.get('endTime') || '',
        page: params.get('page') ? Number(params.get('page')) : 0,
        size: params.get('size') ? Number(params.get('size')) : 20,
        // Filters merged into availabilityParams
        ...filterCriteria,
      };
      
      this.availabilityParams$.next(availParams);
      this.isAvailabilityMode$.next(true);
    }
  });
}
```

---

### Phase 2: Frontend Service Layer (CarService)

**Objective**: Pass geospatial + filter data to backend

#### 2.1 Extend searchAvailableCars() Signature

**Current Signature (lines 87-123)**:
```typescript
searchAvailableCars(
  location: string,
  startDateTime: string,
  startTimeOfDay: string,
  endDateTime: string,
  endTimeOfDay: string,
  page: number = 0,
  size: number = 20,
  sort?: string
): Observable<PagedResponse<Car>>
```

**NEW Signature**:
```typescript
searchAvailableCars(
  location: string,
  startDateTime: string,
  startTimeOfDay: string,
  endDateTime: string,
  endTimeOfDay: string,
  page: number = 0,
  size: number = 20,
  sort?: string,
  // NEW: Geospatial parameters
  latitude?: number,
  longitude?: number,
  radiusKm?: number,
  // NEW: Filter parameters
  minPrice?: number,
  maxPrice?: number,
  make?: string,
  model?: string,
  minYear?: number,
  maxYear?: number,
  minSeats?: number,
  transmission?: string,
  features?: string[],
): Observable<PagedResponse<Car>>
```

**BETTER**: Use a DTO object instead of 20+ parameters

```typescript
interface AvailabilitySearchParams {
  location: string;
  startDateTime: string;
  startTimeOfDay: string;
  endDateTime: string;
  endTimeOfDay: string;
  latitude?: number;
  longitude?: number;
  radiusKm?: number;
  minPrice?: number;
  maxPrice?: number;
  make?: string;
  model?: string;
  minYear?: number;
  maxYear?: number;
  minSeats?: number;
  transmission?: string;
  features?: string[];
  page?: number;
  size?: number;
  sort?: string;
}

searchAvailableCars(params: AvailabilitySearchParams): Observable<PagedResponse<Car>>
```

#### 2.2 Build HTTP Params with Geospatial + Filters

**Current Implementation (lines 101-122)**:
```typescript
let params = new HttpParams()
  .set('location', location)
  .set('startTime', startTime)
  .set('endTime', endTime)
  .set('page', page.toString())
  .set('size', size.toString());

if (sort) {
  params = params.set('sort', sort);
}

return this.http.get<any>(`${this.baseUrl}/availability-search`, { params }).pipe(
  // ... mapping
);
```

**NEW Implementation**:
```typescript
searchAvailableCars(params: AvailabilitySearchParams): Observable<PagedResponse<Car>> {
  const startTime = this.toISOTimestamp(params.startDateTime, params.startTimeOfDay);
  const endTime = this.toISOTimestamp(params.endDateTime, params.endTimeOfDay);

  let httpParams = new HttpParams()
    .set('location', params.location)
    .set('startTime', startTime)
    .set('endTime', endTime)
    .set('page', (params.page ?? 0).toString())
    .set('size', (params.size ?? 20).toString());

  // Add geospatial parameters
  if (params.latitude !== undefined && params.longitude !== undefined) {
    httpParams = httpParams
      .set('latitude', params.latitude.toString())
      .set('longitude', params.longitude.toString());
  }
  if (params.radiusKm !== undefined) {
    httpParams = httpParams.set('radiusKm', params.radiusKm.toString());
  }

  // Add filter parameters (only if provided)
  if (params.minPrice !== undefined) {
    httpParams = httpParams.set('minPrice', params.minPrice.toString());
  }
  if (params.maxPrice !== undefined) {
    httpParams = httpParams.set('maxPrice', params.maxPrice.toString());
  }
  if (params.make) {
    httpParams = httpParams.set('make', params.make);
  }
  if (params.model) {
    httpParams = httpParams.set('model', params.model);
  }
  if (params.minYear !== undefined) {
    httpParams = httpParams.set('minYear', params.minYear.toString());
  }
  if (params.maxYear !== undefined) {
    httpParams = httpParams.set('maxYear', params.maxYear.toString());
  }
  if (params.minSeats !== undefined) {
    httpParams = httpParams.set('minSeats', params.minSeats.toString());
  }
  if (params.transmission) {
    httpParams = httpParams.set('transmission', params.transmission);
  }
  if (params.features?.length) {
    httpParams = httpParams.set('features', params.features.join(','));
  }
  if (params.sort) {
    httpParams = httpParams.set('sort', params.sort);
  }

  return this.http.get<any>(`${this.baseUrl}/availability-search`, { params: httpParams }).pipe(
    map((response) => ({
      content: response.content.map((car: any) => this.mapBackendCarToFrontend(car)),
      totalElements: response.totalElements,
      totalPages: response.totalPages,
      currentPage: response.currentPage,
      pageSize: response.pageSize,
      hasNext: response.hasNext,
      hasPrevious: response.hasPrevious,
    }))
  );
}
```

#### 2.3 Update car-list.component.ts searchResults$ Pipeline

**Current (lines 164-230)**:
```typescript
readonly searchResults$: Observable<PagedResponse<Car>> = combineLatest([
  this.isAvailabilityMode$,
  this.availabilityParams$,
  this.searchCriteria$
]).pipe(
  debounceTime(100),
  distinctUntilChanged((prev, curr) => JSON.stringify(prev) === JSON.stringify(curr)),
  tap(() => this.isLoading$.next(true)),
  switchMap(([isAvailability, availParams, criteria]) => {
    if (isAvailability && availParams) {
      return this.carService
        .searchAvailableCars(
          availParams.location,
          availParams.startDate,
          availParams.startTime,
          availParams.endDate,
          availParams.endTime,
          availParams.page,
          availParams.size
        )
        .pipe(map((results) => this.applyFilters(results, criteria, true)));
    } else {
      return this.carService.searchCars(criteria).pipe(map((results) => this.applyFilters(results, criteria, false)));
    }
  }),
  // ... rest
);
```

**NEW (with geospatial + filters)**:
```typescript
readonly searchResults$: Observable<PagedResponse<Car>> = combineLatest([
  this.isAvailabilityMode$,
  this.availabilityParams$,
  this.searchCriteria$
]).pipe(
  debounceTime(100),
  distinctUntilChanged((prev, curr) => JSON.stringify(prev) === JSON.stringify(curr)),
  tap(() => this.isLoading$.next(true)),
  switchMap(([isAvailability, availParams, criteria]) => {
    if (isAvailability && availParams) {
      // Convert availabilityParams to CarService DTO format
      const searchParams: AvailabilitySearchParams = {
        location: availParams.location,
        startDateTime: availParams.startDate || '',
        startTimeOfDay: availParams.startTime,
        endDateTime: availParams.endDate || '',
        endTimeOfDay: availParams.endTime,
        latitude: availParams.latitude,
        longitude: availParams.longitude,
        radiusKm: availParams.radiusKm,
        minPrice: availParams.minPrice,
        maxPrice: availParams.maxPrice,
        make: availParams.make,
        model: availParams.model,
        minYear: availParams.minYear,
        maxYear: availParams.maxYear,
        minSeats: availParams.minSeats,
        transmission: availParams.transmission,
        features: availParams.features,
        page: availParams.page,
        size: availParams.size,
        sort: availParams.sort,
      };
      
      // NO client-side filtering needed - backend handles it all
      return this.carService.searchAvailableCars(searchParams);
    } else {
      return this.carService.searchCars(criteria).pipe(
        map((results) => this.applyFilters(results, criteria, false))
      );
    }
  }),
  // ... rest
);
```

---

### Phase 3: Car Filters Components Integration

**Objective**: Connect filters to both search modes seamlessly

#### 3.1 Update car-filters.component.ts

**Current Behavior**:
- Emits `filtersChanged` event when "Prikaži rezultate" button clicked
- Parent (car-list) receives CarSearchCriteria and updates searchCriteria$

**NEW Behavior**:
- When in **availability mode**: Merge filters into availabilityParams$ (triggers backend search with filters)
- When in **standard mode**: Update searchCriteria$ as before (triggers client-side filtering)

**Implementation in car-list.component.ts**:
```typescript
onFiltersChanged(criteria: CarSearchCriteria): void {
  if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
    // AVAILABILITY MODE: Merge filters into availabilityParams
    const updated: AvailabilitySearchParams = {
      ...this.availabilityParams$.value,
      
      // Update filters
      minPrice: criteria.minPrice,
      maxPrice: criteria.maxPrice,
      make: criteria.make,
      model: criteria.model,
      minYear: criteria.minYear,
      maxYear: criteria.maxYear,
      minSeats: criteria.minSeats,
      transmission: criteria.transmission,
      features: criteria.features,
      sort: criteria.sort,
      
      // Reset to page 0 when filters change
      page: 0,
    };
    
    this.availabilityParams$.next(updated);
  } else {
    // STANDARD MODE: Update searchCriteria as before
    const updated = { ...criteria, page: 0, size: this.searchCriteria$.value.size };
    this.searchCriteria$.next(updated);
  }
  
  this.updateActiveFilterChips(criteria);
}
```

#### 3.2 Update car-filters.component.html (No Changes Needed)

Current template design already works with both modes:
- Filter dialog opens the same way
- "Prikaži rezultate" button emits filtersChanged event
- Parent component handles routing logic

#### 3.3 Reset Filters in Availability Mode

**Current onResetFilters() (line 588-590)**:
```typescript
onResetFilters(): void {
  this.filtersOnlyReset();
}

private filtersOnlyReset(): void {
  const defaultCriteria: CarSearchCriteria = {
    page: 0,
    size: this.searchCriteria$.value.size ?? 20,
  };
  this.searchCriteria$.next(defaultCriteria);
  this.updateActiveFilterChips(defaultCriteria);
  if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
    this.updateUrlParamsForAvailability(this.availabilityParams$.value);
  } else {
    this.updateUrlParams(defaultCriteria);
  }
}
```

**NEW onResetFilters()**:
```typescript
onResetFilters(): void {
  if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
    // AVAILABILITY MODE: Remove filters but keep location + time
    const updated: AvailabilitySearchParams = {
      ...this.availabilityParams$.value,
      
      // Clear all filters
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
      
      // Reset to page 0
      page: 0,
    };
    
    this.availabilityParams$.next(updated);
  } else {
    // STANDARD MODE: Reset all criteria
    const defaultCriteria: CarSearchCriteria = {
      page: 0,
      size: this.searchCriteria$.value.size ?? 20,
    };
    this.searchCriteria$.next(defaultCriteria);
  }
  
  this.updateActiveFilterChips({});
}
```

---

### Phase 4: Backend Service Layer Updates

**Objective**: Support geospatial + filter parameters in availability search

#### 4.1 Update AvailabilitySearchRequestDTO

**Current Structure**:
```java
@Data
@Validated
public class AvailabilitySearchRequestDTO {
  @NotBlank
  private String location;
  
  @NotNull
  private LocalDateTime startTime;
  
  @NotNull
  private LocalDateTime endTime;
  
  private Integer page = 0;
  private Integer size = 20;
  private String sort;
  
  public void validate() {
    // Duration validation, etc.
  }
}
```

**NEW Structure**:
```java
@Data
@Validated
public class AvailabilitySearchRequestDTO {
  // Location (required)
  @NotBlank
  private String location;
  
  // Geospatial (optional - if provided, use radius search instead of location string)
  private Double latitude;
  private Double longitude;
  private Integer radiusKm = 25; // Default radius if geospatial params provided
  
  // Time range (required)
  @NotNull
  private LocalDateTime startTime;
  
  @NotNull
  private LocalDateTime endTime;
  
  // Filters (all optional)
  private BigDecimal minPrice;
  private BigDecimal maxPrice;
  private String make;
  private String model;
  private Integer minYear;
  private Integer maxYear;
  private Integer minSeats;
  private String transmission; // AUTOMATIC, MANUAL
  private List<String> features; // AIR_CONDITIONING, BLUETOOTH, etc.
  
  // Pagination & Sorting
  private Integer page = 0;
  private Integer size = 20;
  private String sort;
  
  public void validate() {
    // Duration validation: 1 hour minimum, 90 days maximum
    if (startTime.isAfter(endTime)) {
      throw new IllegalArgumentException("startTime must be before endTime");
    }
    Duration duration = Duration.between(startTime, endTime);
    if (duration.toMinutes() < 60) {
      throw new IllegalArgumentException("Rental period must be at least 1 hour");
    }
    if (duration.toDays() > 90) {
      throw new IllegalArgumentException("Rental period cannot exceed 90 days");
    }
    
    // Geospatial validation
    if ((latitude == null && longitude != null) || (latitude != null && longitude == null)) {
      throw new IllegalArgumentException("Both latitude and longitude must be provided together");
    }
    if (latitude != null && (latitude < -90 || latitude > 90)) {
      throw new IllegalArgumentException("Invalid latitude");
    }
    if (longitude != null && (longitude < -180 || longitude > 180)) {
      throw new IllegalArgumentException("Invalid longitude");
    }
    if (radiusKm != null && (radiusKm < 1 || radiusKm > 500)) {
      throw new IllegalArgumentException("Radius must be between 1 and 500 km");
    }
  }
}
```

#### 4.2 Update AvailabilityService.searchAvailableCars()

**Current Logic (simplified)**:
```java
public Page<Car> searchAvailableCars(AvailabilitySearchRequestDTO request) {
  // 1. Find all cars in location
  List<Car> carsByLocation = carRepository.findByLocationIgnoreCaseAndAvailableTrue(request.getLocation());
  
  // 2. Filter out unavailable cars (booking conflicts)
  List<Car> availableCars = carsByLocation.stream()
    .filter(car -> !isOverlappingBooking(car.getId(), request.getStartTime(), request.getEndTime()))
    .collect(Collectors.toList());
  
  // 3. Apply pagination
  return new PageImpl<>(availableCars, pageable, availableCars.size());
}
```

**NEW Logic with Geospatial + Filters**:
```java
public Page<Car> searchAvailableCars(AvailabilitySearchRequestDTO request, Pageable pageable) {
  // 1. Find cars by location or geospatial radius
  List<Car> candidates;
  if (request.getLatitude() != null && request.getLongitude() != null) {
    // GEOSPATIAL SEARCH: Find cars within radius
    candidates = carRepository.findNearby(
      request.getLatitude(),
      request.getLongitude(),
      request.getRadiusKm(),
      Sort.by(Sort.Direction.DESC, "createdAt") // Or sort by distance if available
    );
  } else {
    // LEGACY: String-based location search
    candidates = carRepository.findByLocationIgnoreCaseAndAvailableTrue(request.getLocation());
  }
  
  // 2. Filter out unavailable cars (booking conflicts & blocked dates)
  List<Car> availableCars = candidates.stream()
    .filter(car -> !isOverlappingBooking(car.getId(), request.getStartTime(), request.getEndTime()))
    .filter(car -> !hasBlockedDate(car.getId(), request.getStartTime(), request.getEndTime()))
    .collect(Collectors.toList());
  
  // 3. Apply additional filters (price, make, model, features, etc.)
  if (request.hasActiveFilters()) {
    availableCars = applySearchFilters(availableCars, request);
  }
  
  // 4. Apply sorting
  if (request.getSort() != null) {
    availableCars = applySorting(availableCars, request.getSort());
  }
  
  // 5. Apply pagination
  int start = (int) pageable.getOffset();
  int end = Math.min(start + pageable.getPageSize(), availableCars.size());
  List<Car> paged = availableCars.subList(start, end);
  
  return new PageImpl<>(paged, pageable, availableCars.size());
}

// Helper: Apply filters
private List<Car> applySearchFilters(List<Car> cars, AvailabilitySearchRequestDTO request) {
  return cars.stream()
    .filter(car -> matchesPrice(car, request))
    .filter(car -> matchesMake(car, request))
    .filter(car -> matchesModel(car, request))
    .filter(car -> matchesYear(car, request))
    .filter(car -> matchesSeats(car, request))
    .filter(car -> matchesTransmission(car, request))
    .filter(car -> matchesFeatures(car, request))
    .collect(Collectors.toList());
}

private boolean matchesPrice(Car car, AvailabilitySearchRequestDTO request) {
  if (request.getMinPrice() != null && car.getPricePerDay().compareTo(request.getMinPrice()) < 0) {
    return false;
  }
  if (request.getMaxPrice() != null && car.getPricePerDay().compareTo(request.getMaxPrice()) > 0) {
    return false;
  }
  return true;
}

private boolean matchesMake(Car car, AvailabilitySearchRequestDTO request) {
  if (request.getMake() == null) return true;
  return normalizeSearchString(car.getBrand()).contains(normalizeSearchString(request.getMake()));
}

private boolean matchesFeatures(Car car, AvailabilitySearchRequestDTO request) {
  if (request.getFeatures() == null || request.getFeatures().isEmpty()) {
    return true;
  }
  Set<String> carFeatures = new HashSet<>(car.getFeatures());
  return carFeatures.containsAll(request.getFeatures());
}

// ... other matchers (model, year, seats, transmission)
```

#### 4.3 Update CarController /availability-search Endpoint

**Current Controller**:
```java
@GetMapping("/availability-search")
public ResponseEntity<Page<CarDto>> searchAvailableCars(
    @RequestParam String location,
    @RequestParam LocalDateTime startTime,
    @RequestParam LocalDateTime endTime,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size,
    @RequestParam(required = false) String sort
) {
  AvailabilitySearchRequestDTO request = new AvailabilitySearchRequestDTO();
  request.setLocation(location);
  request.setStartTime(startTime);
  request.setEndTime(endTime);
  request.setPage(page);
  request.setSize(size);
  request.setSort(sort);
  request.validate();
  
  Page<Car> results = availabilityService.searchAvailableCars(request, PageRequest.of(page, size));
  return ResponseEntity.ok(carDtoMapper.toDto(results));
}
```

**NEW Controller** (with geospatial + filters):
```java
@GetMapping("/availability-search")
public ResponseEntity<Page<CarDto>> searchAvailableCars(
    // Location
    @RequestParam String location,
    @RequestParam(required = false) Double latitude,
    @RequestParam(required = false) Double longitude,
    @RequestParam(defaultValue = "25") Integer radiusKm,
    
    // Time range
    @RequestParam LocalDateTime startTime,
    @RequestParam LocalDateTime endTime,
    
    // Filters
    @RequestParam(required = false) BigDecimal minPrice,
    @RequestParam(required = false) BigDecimal maxPrice,
    @RequestParam(required = false) String make,
    @RequestParam(required = false) String model,
    @RequestParam(required = false) Integer minYear,
    @RequestParam(required = false) Integer maxYear,
    @RequestParam(required = false) Integer minSeats,
    @RequestParam(required = false) String transmission,
    @RequestParam(required = false) List<String> features,
    
    // Pagination & Sorting
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size,
    @RequestParam(required = false) String sort
) {
  AvailabilitySearchRequestDTO request = new AvailabilitySearchRequestDTO();
  request.setLocation(location);
  request.setLatitude(latitude);
  request.setLongitude(longitude);
  request.setRadiusKm(radiusKm);
  request.setStartTime(startTime);
  request.setEndTime(endTime);
  request.setMinPrice(minPrice);
  request.setMaxPrice(maxPrice);
  request.setMake(make);
  request.setModel(model);
  request.setMinYear(minYear);
  request.setMaxYear(maxYear);
  request.setMinSeats(minSeats);
  request.setTransmission(transmission);
  request.setFeatures(features);
  request.setPage(page);
  request.setSize(size);
  request.setSort(sort);
  request.validate();
  
  Page<Car> results = availabilityService.searchAvailableCars(request, PageRequest.of(page, size));
  return ResponseEntity.ok(carDtoMapper.toDto(results));
}
```

---

### Phase 5: Database & Query Optimization

**Objective**: Ensure backend queries are performant with new filters

#### 5.1 Verify Spatial Index on GeoPoint

**Current Indexes** (Car.java lines 25-30):
```java
@Table(
  name = "cars",
  indexes = {
    @Index(name = "idx_car_location", columnList = "location"),
    @Index(name = "idx_car_available", columnList = "available"),
    @Index(name = "idx_car_location_city_available", columnList = "location_city, available")
  }
)
```

**ADD**: Spatial index for geospatial queries
```java
// In migration or schema definition:
CREATE SPATIAL INDEX idx_car_geolocation ON cars(location_geopoint_latitude, location_geopoint_longitude);

// Or ensure findNearby() in CarRepository uses SPATIAL INDEX via ST_Distance_Sphere
```

#### 5.2 Composite Indexes for Filter Queries

Add indexes to optimize filtered availability searches:
```sql
-- Availability + basic filters
CREATE INDEX idx_car_availability_filters ON cars(
  available,
  price_per_day,
  brand,
  `year`
);

-- Availability + features
CREATE INDEX idx_car_availability_features ON cars(
  available
);
-- Features search requires full table scan or separate junction table optimization

-- Availability + location + filters
CREATE INDEX idx_car_availability_location_filters ON cars(
  available,
  location_city,
  price_per_day
);
```

---

## Integration Checklist

### Frontend: car-list.component.ts
- [ ] Add geospatial properties to availabilityParams$ type definition
- [ ] Update searchAvailability() to include selectedGeocodeSuggestion coordinates
- [ ] Add getActiveFiltersForAvailability() helper method
- [ ] Update searchResults$ pipeline to pass geospatial + filters to CarService
- [ ] Implement updateUrlParamsForAvailability() with lat/lng/filters
- [ ] Restore full state in ngOnInit() including coordinates and filters
- [ ] Remove applyFilters() client-side filtering (backend now handles it)
- [ ] Update onFiltersChanged() to merge filters into availabilityParams when in availability mode
- [ ] Update onResetFilters() to handle both modes

### Frontend: CarService
- [ ] Define AvailabilitySearchParams DTO
- [ ] Update searchAvailableCars() signature to accept DTO
- [ ] Build HTTP params with geospatial + filter values
- [ ] Add null-safety checks for optional parameters

### Frontend: car-filters.component.ts
- [ ] No code changes needed (parent logic handles routing)

### Backend: AvailabilitySearchRequestDTO
- [ ] Add latitude, longitude, radiusKm properties
- [ ] Add minPrice, maxPrice, make, model, minYear, maxYear, minSeats, transmission, features
- [ ] Add hasActiveFilters() method
- [ ] Update validate() to check geospatial bounds

### Backend: AvailabilityService
- [ ] Refactor searchAvailableCars() to check latitude/longitude
- [ ] Implement conditional search: geospatial (findNearby) vs. location string (findByLocation)
- [ ] Add applySearchFilters() method with individual matchers
- [ ] Add applySorting() method
- [ ] Remove client-side filtering assumption

### Backend: CarController
- [ ] Add @RequestParam annotations for geospatial + filter parameters
- [ ] Construct AvailabilitySearchRequestDTO with all parameters
- [ ] Validate request before calling service

### Database
- [ ] Verify spatial index on location_geopoint_latitude, location_geopoint_longitude
- [ ] Add composite indexes for filtered queries
- [ ] Test query performance with EXPLAIN ANALYZE

---

## Testing Strategy

### Unit Tests

**Frontend: car-list.component.ts**
```typescript
// Test state merging
it('should merge filters into availabilityParams when in availability mode', () => {
  component.isAvailabilityMode$.next(true);
  component.availabilityParams$.next({
    location: 'Beograd',
    latitude: 44.8,
    longitude: 20.4,
    // ... other params
  });
  
  const criteria: CarSearchCriteria = { minPrice: 100, maxPrice: 500, make: 'BMW' };
  component.onFiltersChanged(criteria);
  
  expect(component.availabilityParams$.value).toEqual(jasmine.objectContaining({
    minPrice: 100,
    maxPrice: 500,
    make: 'BMW',
  }));
});

// Test URL persistence with coordinates
it('should include lat/lng in URL when in availability mode', () => {
  component.availabilityParams$.next({
    location: 'Beograd',
    latitude: 44.816666,
    longitude: 20.458889,
    radiusKm: 25,
    // ... other params
  });
  
  component['updateUrlParamsForAvailability'](component.availabilityParams$.value!);
  
  // Check router.navigate was called with lat/lng in queryParams
});
```

**Backend: AvailabilityService**
```java
@Test
void testSearchAvailableCarsWithGeospatialAndFilters() {
  AvailabilitySearchRequestDTO request = new AvailabilitySearchRequestDTO();
  request.setLatitude(44.816666);
  request.setLongitude(20.458889);
  request.setRadiusKm(25);
  request.setMinPrice(BigDecimal.valueOf(50));
  request.setMaxPrice(BigDecimal.valueOf(300));
  request.setMake("BMW");
  request.setStartTime(LocalDateTime.now());
  request.setEndTime(LocalDateTime.now().plusHours(1));
  
  Page<Car> results = availabilityService.searchAvailableCars(request, PageRequest.of(0, 20));
  
  // Assert all results:
  // - Are within radius
  // - Have availability for time range
  // - Match filters (price, make)
}
```

### Integration Tests

**End-to-End Availability Search with Filters**
1. User enters location → geolocation selected with coordinates
2. User selects dates/times
3. User opens filter dialog, sets make="BMW", price=100-300
4. User clicks "Prikaži rezultate"
5. Verify:
   - URL contains lat, lng, radiusKm, make, minPrice, maxPrice
   - Backend receives all parameters
   - Results are filtered (all BMW, price in range, available for time)
   - Pagination works correctly
6. User refreshes page → state fully restored

**Mode Switching**
1. Perform availability search with filters
2. Clear location (switch to standard mode)
3. Apply new filters
4. Verify: searchCriteria$ updated, availabilityParams$ cleared, URL clean

---

## Migration Path

### Step 1: Code Implementation (2-3 weeks)
- Frontend: State management + CarService changes
- Backend: DTO + Service + Controller updates
- Testing: Unit + integration tests

### Step 2: Backward Compatibility (1 week)
- Support both old and new query formats
- Availability endpoint accepts old parameters (no lat/lng)
- Defaults to location string search if coordinates not provided

### Step 3: Database Optimization (1 week)
- Add spatial indexes
- Add composite indexes for filters
- Run EXPLAIN ANALYZE on queries
- Performance testing with 10k+ cars

### Step 4: Deployment (Canary → 100%)
- Deploy backend first (backward compatible)
- Deploy frontend (uses new parameters)
- Monitor error rates, query times, user feedback
- Rollback plan: Switch frontend back to location-only search

### Step 5: Cleanup (Post-deployment)
- Remove client-side filter application
- Deprecate legacy location-only availability search path
- Update documentation
- Monitor for 2 weeks, then remove deprecated code

---

## Performance Implications

### Before (Client-Side Filtering)
```
Average query time: 200ms (SQL) + 100ms (client filtering) = 300ms
Payload: All cars in city (200-500 cars)
Network: 2-5 MB
```

### After (Server-Side Filtering)
```
Average query time: 150ms (SQL with spatial index + filters)
Payload: Only matching cars (10-50 cars with filters)
Network: 50-200 KB (10x reduction)
```

**Benefits**:
- 50% reduction in response time
- 10-20x reduction in payload size
- Better UX on mobile networks
- Reduced database load with indexed queries

---

## Success Criteria

1. ✅ Filters apply correctly in both availability and standard modes
2. ✅ URL persistence works: refresh restores full state including filters
3. ✅ Backend receives geospatial + filter parameters
4. ✅ Query performance: <200ms for typical searches
5. ✅ No regression: All existing tests pass
6. ✅ Mobile UX: Payload <500KB, load time <2s on 4G

---

## Open Questions

1. **Radius Slider**: Should car-list expose `searchRadius` slider like home component?
   - Recommendation: Add optional property, default 25 km
   - When radius changes → update availabilityParams$ → trigger search

2. **Sort Options**: Should backend support distance-based sorting?
   - Recommendation: Add `sort=DISTANCE` option
   - Use ST_Distance_Sphere in ORDER BY clause

3. **Features Matching**: Should "any feature match" or "all features match"?
   - Recommendation: "All features match" for current implementation
   - Add option later if needed

4. **Rate Limiting**: Should we add rate limiting to availability endpoint?
   - Recommendation: Yes, 100 req/min per IP for public endpoint

---

## References

- Frontend Implementation: car-list.component.ts (lines 461-525)
- Backend Implementation: AvailabilityService.java
- Geospatial Pattern: home.component.ts (lines 310-372)
- Car Entity: Car.java (lines 83-115)

