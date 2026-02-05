# AI Agent Command: Integrate Geospatial Filters with Car-List Availability Search

**Priority**: CRITICAL - Blocks production release of geospatial car-list  
**Complexity**: HIGH - Touches frontend + backend state + API contracts  
**Estimated Effort**: 4-5 hours (2 hours frontend, 2 hours backend, 1 hour testing)  
**Risk Level**: MEDIUM - Filter behavior changes but backward compatible  

---

## Pre-Flight Checklist

Before starting implementation, verify:

- [ ] Car-list geospatial upgrade COMPLETE (location search with Nominatim)
- [ ] home.component.ts geospatial pattern review COMPLETE
- [ ] AvailabilityService backend supports location string search (legacy mode)
- [ ] CarRepository has findNearby() method for geospatial radius search
- [ ] Frontend build passes `ng build` without errors
- [ ] Backend builds with `mvn clean install` without errors

---

## PHASE 1: FRONTEND STATE MANAGEMENT LAYER

### 1.1 Create AvailabilitySearchParams Type Definition

**File**: `rentoza-frontend/src/app/core/models/car-search.model.ts`

**Action**: Add new interface at end of file

```typescript
/**
 * Parameters for availability search with geospatial + filters
 * Used by car-list.component to pass all search context to backend
 */
export interface AvailabilitySearchParams {
  // Location (required)
  location: string;
  
  // Geospatial (optional - if provided, use radius search)
  latitude?: number;
  longitude?: number;
  radiusKm?: number;
  
  // Time range (required)
  startDate: string; // Kept for backward compatibility, usually empty
  startTime: string; // ISO timestamp: 2025-12-02T09:00:00
  endDate: string;   // Kept for backward compatibility, usually empty
  endTime: string;   // ISO timestamp: 2025-12-02T18:00:00
  
  // Filters (all optional)
  minPrice?: number;
  maxPrice?: number;
  make?: string;
  model?: string;
  minYear?: number;
  maxYear?: number;
  minSeats?: number;
  transmission?: string; // TransmissionType enum as string: 'AUTOMATIC' | 'MANUAL'
  features?: string[];   // Feature enum as strings
  
  // Pagination & Sorting
  page: number;
  size: number;
  sort?: string; // CarSortOption as string
}
```

**Verification**:
- TypeScript compilation succeeds
- No circular dependency imports

---

### 1.2 Update car-list.component.ts availabilityParams$ BehaviorSubject

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Action 1**: Update imports at top of file (around line 27-39)

**Add**:
```typescript
import { AvailabilitySearchParams } from '@core/models/car-search.model';
```

**Action 2**: Update availabilityParams$ declaration (lines 144-152)

**Current**:
```typescript
readonly availabilityParams$ = new BehaviorSubject<{
  location: string;
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  page: number;
  size: number;
} | null>(null);
```

**Replace With**:
```typescript
readonly availabilityParams$ = new BehaviorSubject<AvailabilitySearchParams | null>(null);
```

**Verification**:
- No compilation errors
- availabilityParams$.value type is now strongly typed to AvailabilitySearchParams | null

---

### 1.3 Add Helper Methods to Extract Active Filters

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Action**: Add two methods after private methods section (after line 325 or before searchAvailability)

**Insert**:
```typescript
/**
 * Extract active filters from searchCriteria$ that should be merged into availability params
 * Only includes non-default filter values
 */
private getActiveFiltersForAvailability(): Partial<AvailabilitySearchParams> {
  const criteria = this.searchCriteria$.value;
  const filters: any = {};
  
  if (criteria.minPrice !== undefined) {
    filters.minPrice = criteria.minPrice;
  }
  if (criteria.maxPrice !== undefined) {
    filters.maxPrice = criteria.maxPrice;
  }
  if (criteria.make) {
    filters.make = criteria.make;
  }
  if (criteria.model) {
    filters.model = criteria.model;
  }
  if (criteria.minYear !== undefined) {
    filters.minYear = criteria.minYear;
  }
  if (criteria.maxYear !== undefined) {
    filters.maxYear = criteria.maxYear;
  }
  if (criteria.minSeats !== undefined) {
    filters.minSeats = criteria.minSeats;
  }
  if (criteria.transmission) {
    filters.transmission = criteria.transmission;
  }
  if (criteria.features?.length) {
    filters.features = criteria.features;
  }
  if (criteria.sort) {
    filters.sort = criteria.sort;
  }
  
  return filters;
}

/**
 * Check if any non-default filters are active
 */
private hasActiveFilters(): boolean {
  return (this.activeFilterChips$.value?.length ?? 0) > 0;
}
```

**Verification**:
- Methods compile without errors
- activeFilterChips$ is still available at this point in file

---

### 1.4 Update searchAvailability() Method to Include Geospatial + Filters

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Location**: Lines 461-525

**Action**: Replace entire searchAvailability() method

**New Implementation**:
```typescript
searchAvailability(): void {
  this.clearSearchErrors();

  const location = this.searchLocation.trim();
  
  if (!location) {
    this.locationError = 'Unesite lokaciju';
  }
  
  if (!this.selectedGeocodeSuggestion) {
    this.locationError = 'Odaberite lokaciju iz ponuđenih opcija ili označite na mapi';
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

  const pageSize = 20;
  const startTimeISO = this.combineDateTime(this.searchStartDate as Date, this.searchStartTime);
  const endTimeISO = this.combineDateTime(this.searchEndDate as Date, this.searchEndTime);

  // Build availability params with geospatial + filters
  const availParams: AvailabilitySearchParams = {
    // Geospatial
    location: this.selectedGeocodeSuggestion.city,
    latitude: this.selectedGeocodeSuggestion.latitude,
    longitude: this.selectedGeocodeSuggestion.longitude,
    radiusKm: 25, // TODO: Make configurable via property
    
    // Time range
    startDate: '',
    startTime: startTimeISO,
    endDate: '',
    endTime: endTimeISO,
    
    // Pagination (reset to 0 when search criteria change)
    page: 0,
    size: pageSize,
    
    // Merge active filters if any
    ...(this.hasActiveFilters() ? this.getActiveFiltersForAvailability() : {}),
  };

  this.availabilityParams$.next(availParams);
  this.isAvailabilityMode$.next(true);
  this.availabilityFilterDisplay$.next(null);
  
  const startDate = this.formatDate(this.searchStartDate as Date);
  const endDate = this.formatDate(this.searchEndDate as Date);
  this.updateAvailabilityFilterDisplay(
    this.selectedGeocodeSuggestion.city,
    startDate,
    this.searchStartTime,
    endDate,
    this.searchEndTime
  );
  
  this.activeFilterChips$.next([]);
  this.searchCriteria$.next({ ...this.searchCriteria$.value, page: 0, size: pageSize });
}
```

**Verification**:
- Compilation succeeds
- selectedGeocodeSuggestion type has latitude/longitude properties
- combineDateTime() method exists (should be present from geospatial upgrade)

---

### 1.5 Update searchResults$ Observable Pipeline

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Location**: Lines 164-230

**Action**: Modify the switchMap operator in searchResults$ pipeline

**Current switchMap**:
```typescript
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
})
```

**Replace With**:
```typescript
switchMap(([isAvailability, availParams, criteria]) => {
  if (isAvailability && availParams) {
    // AVAILABILITY MODE: Pass full params to backend (no client-side filtering needed)
    return this.carService.searchAvailableCars(availParams);
  } else {
    // STANDARD MODE: Standard search with client-side filtering
    return this.carService.searchCars(criteria).pipe(
      map((results) => this.applyFilters(results, criteria, false))
    );
  }
})
```

**Verification**:
- Compilation succeeds
- CarService.searchAvailableCars signature updated (see Phase 2 below)
- applyFilters still used for standard mode

---

### 1.6 Update ngOnInit() to Restore Geospatial + Filters State

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Location**: Lines 326-424

**Action**: Replace availability search restoration section

**Current Code (lines 346-407)**:
```typescript
if (isAvailabilitySearch) {
  const location = params.get('location') || '';
  const startTimeISO = params.get('startTime') || '';
  const endTimeISO = params.get('endTime') || '';
  const page = params.get('page') ? Number(params.get('page')) : 0;
  const size = params.get('size') ? Number(params.get('size')) : 20;

  // Parse ISO strings to extract date and time for UI display
  let searchStartDate: Date | null = null;
  let searchStartTime = '';
  let searchEndDate: Date | null = null;
  let searchEndTime = '';

  if (startTimeISO && startTimeISO.includes('T')) {
    const startDateObj = new Date(startTimeISO);
    if (!isNaN(startDateObj.getTime())) {
      searchStartDate = startDateObj;
      const [datePart, timePart] = startTimeISO.split('T');
      searchStartTime = timePart.substring(0, 5);
    }
  }

  if (endTimeISO && endTimeISO.includes('T')) {
    const endDateObj = new Date(endTimeISO);
    if (!isNaN(endDateObj.getTime())) {
      searchEndDate = endDateObj;
      const [datePart, timePart] = endTimeISO.split('T');
      searchEndTime = timePart.substring(0, 5);
    }
  }

  this.availabilityParams$.next({
    location,
    startDate: '',
    startTime: startTimeISO,
    endDate: '',
    endTime: endTimeISO,
    page,
    size,
  });

  this.searchLocation = location;
  this.searchStartDate = searchStartDate;
  this.searchStartTime = searchStartTime;
  this.searchEndDate = searchEndDate;
  this.searchEndTime = searchEndTime;

  this.isAvailabilityMode$.next(true);

  const displayStartDate = searchStartDate ? this.formatDate(searchStartDate) : '';
  const displayEndDate = searchEndDate ? this.formatDate(searchEndDate) : '';
  this.updateAvailabilityFilterDisplay(location, displayStartDate, searchStartTime, displayEndDate, searchEndTime);

  this.searchCriteria$.next(parsedFilters);
  this.updateActiveFilterChips(parsedFilters);
}
```

**Replace With**:
```typescript
if (isAvailabilitySearch) {
  const location = params.get('location') || '';
  const lat = params.get('lat') ? parseFloat(params.get('lat')!) : undefined;
  const lng = params.get('lng') ? parseFloat(params.get('lng')!) : undefined;
  const radiusKm = params.get('radiusKm') ? parseInt(params.get('radiusKm')!) : 25;
  const startTimeISO = params.get('startTime') || '';
  const endTimeISO = params.get('endTime') || '';
  const page = params.get('page') ? Number(params.get('page')) : 0;
  const size = params.get('size') ? Number(params.get('size')) : 20;

  // Parse ISO strings to extract date and time for UI display
  let searchStartDate: Date | null = null;
  let searchStartTime = '';
  let searchEndDate: Date | null = null;
  let searchEndTime = '';

  if (startTimeISO && startTimeISO.includes('T')) {
    const startDateObj = new Date(startTimeISO);
    if (!isNaN(startDateObj.getTime())) {
      searchStartDate = startDateObj;
      const [datePart, timePart] = startTimeISO.split('T');
      searchStartTime = timePart.substring(0, 5);
    }
  }

  if (endTimeISO && endTimeISO.includes('T')) {
    const endDateObj = new Date(endTimeISO);
    if (!isNaN(endDateObj.getTime())) {
      searchEndDate = endDateObj;
      const [datePart, timePart] = endTimeISO.split('T');
      searchEndTime = timePart.substring(0, 5);
    }
  }

  // Restore geospatial coordinates if available
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
  this.searchStartDate = searchStartDate;
  this.searchStartTime = searchStartTime;
  this.searchEndDate = searchEndDate;
  this.searchEndTime = searchEndTime;

  this.isAvailabilityMode$.next(true);

  const displayStartDate = searchStartDate ? this.formatDate(searchStartDate) : '';
  const displayEndDate = searchEndDate ? this.formatDate(searchEndDate) : '';
  this.updateAvailabilityFilterDisplay(location, displayStartDate, searchStartTime, displayEndDate, searchEndTime);

  // Build availability params with geospatial + filters
  const availParams: AvailabilitySearchParams = {
    location,
    latitude: lat,
    longitude: lng,
    radiusKm,
    startDate: '',
    startTime: startTimeISO,
    endDate: '',
    endTime: endTimeISO,
    minPrice: parsedFilters.minPrice,
    maxPrice: parsedFilters.maxPrice,
    make: parsedFilters.make,
    model: parsedFilters.model,
    minYear: parsedFilters.minYear,
    maxYear: parsedFilters.maxYear,
    minSeats: parsedFilters.minSeats,
    transmission: parsedFilters.transmission,
    features: parsedFilters.features,
    sort: parsedFilters.sort,
    page,
    size,
  };

  this.availabilityParams$.next(availParams);
  this.searchCriteria$.next(parsedFilters);
  this.updateActiveFilterChips(parsedFilters);
}
```

**Verification**:
- Compilation succeeds
- selectedGeocodeSuggestion type has all required properties
- searchCenter property exists (from geospatial upgrade)

---

### 1.7 Update updateUrlParamsForAvailability() Method

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Location**: Lines 957-999

**Action**: Replace entire updateUrlParamsForAvailability() method

**New Implementation**:
```typescript
/**
 * Update URL params for availability search mode (includes geospatial + filters)
 */
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
  if (params.radiusKm !== undefined && params.radiusKm !== 25) {
    // Only include if non-default
    queryParams.radiusKm = params.radiusKm;
  }

  // Add time range (always included for availability search)
  queryParams.startTime = params.startTime;
  queryParams.endTime = params.endTime;

  // Add filters (only non-undefined/null values)
  if (params.minPrice !== undefined) {
    queryParams.minPrice = params.minPrice;
  }
  if (params.maxPrice !== undefined) {
    queryParams.maxPrice = params.maxPrice;
  }
  if (params.make) {
    queryParams.make = params.make;
  }
  if (params.model) {
    queryParams.model = params.model;
  }
  if (params.minYear !== undefined) {
    queryParams.minYear = params.minYear;
  }
  if (params.maxYear !== undefined) {
    queryParams.maxYear = params.maxYear;
  }
  if (params.minSeats !== undefined) {
    queryParams.minSeats = params.minSeats;
  }
  if (params.transmission) {
    queryParams.transmission = params.transmission;
  }
  if (params.features?.length) {
    queryParams.features = params.features.join(',');
  }
  if (params.sort) {
    queryParams.sort = params.sort;
  }

  // Pagination
  if (params.page > 0) {
    queryParams.page = params.page;
  }
  if (params.size !== 20) {
    queryParams.size = params.size;
  }

  this.router.navigate([], {
    relativeTo: this.route,
    queryParams,
    replaceUrl: true,
  });
}
```

**Verification**:
- Method compiles
- All parameters from AvailabilitySearchParams are handled

---

### 1.8 Update onFiltersChanged() to Merge Filters in Availability Mode

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Location**: Lines 527-532

**Action**: Replace entire onFiltersChanged() method

**New Implementation**:
```typescript
onFiltersChanged(criteria: CarSearchCriteria): void {
  if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
    // AVAILABILITY MODE: Merge filters into availabilityParams$ (triggers backend search with filters)
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
    // STANDARD MODE: Update searchCriteria$ as before (triggers client-side filtering)
    const updated = { ...criteria, page: 0, size: this.searchCriteria$.value.size };
    this.searchCriteria$.next(updated);
  }
  
  this.updateActiveFilterChips(criteria);
}
```

**Verification**:
- Compilation succeeds
- Both mode branches update appropriate state

---

### 1.9 Update onResetFilters() to Handle Availability Mode

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.ts`

**Location**: Lines 588-590 and filtersOnlyReset() helper

**Action**: Replace onResetFilters() and filtersOnlyReset()

**New Implementation**:
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
    this.updateActiveFilterChips({});
  } else {
    // STANDARD MODE: Reset all criteria
    this.filtersOnlyReset();
  }
}

private filtersOnlyReset(): void {
  const defaultCriteria: CarSearchCriteria = {
    page: 0,
    size: this.searchCriteria$.value.size ?? 20,
  };

  this.searchCriteria$.next(defaultCriteria);
  this.updateActiveFilterChips(defaultCriteria);

  // Keep availability/search params intact; just sync filter params portion
  if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
    this.updateUrlParamsForAvailability(this.availabilityParams$.value);
  } else {
    this.updateUrlParams(defaultCriteria);
  }
}
```

**Verification**:
- Compilation succeeds
- Both methods handle availability mode correctly

---

### 1.10 Run Format & Linting

**Command**:
```bash
cd rentoza-frontend
ng lint --fix
npx prettier --write "src/app/features/cars/pages/car-list/car-list.component.ts"
ng build --configuration production 2>&1 | head -50
```

**Expected Output**:
- No lint errors
- Build succeeds without warnings about car-list component

---

## PHASE 2: FRONTEND SERVICE LAYER

### 2.1 Update CarService.searchAvailableCars() Signature

**File**: `rentoza-frontend/src/app/core/services/car.service.ts`

**Location**: Lines 87-123

**Action**: Replace searchAvailableCars() method

**New Implementation**:
```typescript
/**
 * Search cars by availability (location + time range) with optional geospatial + filters.
 * 
 * Supports two location search modes:
 * 1. Legacy: String-based location matching (e.g., "Beograd")
 * 2. Geospatial: Radius-based search using latitude/longitude
 * 
 * Backend applies filters server-side for performance.
 * 
 * @param params Availability search parameters
 * @returns Paginated response with available, filtered cars
 */
searchAvailableCars(params: AvailabilitySearchParams): Observable<PagedResponse<Car>> {
  // Combine date + time if needed, or use as-is if already ISO timestamp
  const startTime = this.toISOTimestamp(params.startDate, params.startTime);
  const endTime = this.toISOTimestamp(params.endDate, params.endTime);

  let httpParams = new HttpParams()
    .set('location', params.location)
    .set('startTime', startTime)
    .set('endTime', endTime)
    .set('page', (params.page ?? 0).toString())
    .set('size', (params.size ?? 20).toString());

  // Add geospatial parameters if provided
  if (params.latitude !== undefined && params.longitude !== undefined) {
    httpParams = httpParams
      .set('latitude', params.latitude.toString())
      .set('longitude', params.longitude.toString());
  }
  if (params.radiusKm !== undefined) {
    httpParams = httpParams.set('radiusKm', params.radiusKm.toString());
  }

  // Add filter parameters (only if defined)
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

**Verification**:
- TypeScript compilation succeeds
- AvailabilitySearchParams import present
- toISOTimestamp() helper still exists (unchanged)

---

### 2.2 Verify HTTP Params Encoding

**Command**:
```bash
cd rentoza-frontend
ng build --configuration production 2>&1 | grep -A5 "ERROR\|car.service"
```

**Expected**:
- No errors
- Build completes successfully

---

## PHASE 3: BACKEND SERVICE LAYER

### 3.1 Create/Update AvailabilitySearchRequestDTO

**File**: `Rentoza/src/main/java/org/example/rentoza/car/dto/AvailabilitySearchRequestDTO.java`

**Action**: If file exists, replace entire content. If not, create new file.

**Implementation**:
```java
package org.example.rentoza.car.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Request DTO for availability search with geospatial + filters.
 * 
 * Supports two location modes:
 * 1. Legacy: location string (backward compatible)
 * 2. Geospatial: latitude/longitude + radiusKm (new)
 * 
 * All filter parameters are optional (null = no filter applied).
 */
@Data
public class AvailabilitySearchRequestDTO {
  
  // ========== LOCATION (required) ==========
  @NotBlank(message = "Location cannot be blank")
  private String location;
  
  // ========== GEOSPATIAL (optional) ==========
  private Double latitude;
  private Double longitude;
  private Integer radiusKm = 25; // Default radius if geospatial search
  
  // ========== TIME RANGE (required) ==========
  @NotNull(message = "Start time cannot be null")
  private LocalDateTime startTime;
  
  @NotNull(message = "End time cannot be null")
  private LocalDateTime endTime;
  
  // ========== FILTERS (all optional) ==========
  private BigDecimal minPrice;
  private BigDecimal maxPrice;
  private String make;
  private String model;
  private Integer minYear;
  private Integer maxYear;
  private Integer minSeats;
  private String transmission; // "AUTOMATIC" or "MANUAL"
  private List<String> features; // List of feature names
  
  // ========== PAGINATION & SORTING ==========
  private Integer page = 0;
  private Integer size = 20;
  private String sort;
  
  /**
   * Validate request parameters
   * @throws IllegalArgumentException if validation fails
   */
  public void validate() {
    // Time range validation
    if (startTime.isAfter(endTime)) {
      throw new IllegalArgumentException("startTime must be before endTime");
    }
    
    long durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime);
    if (durationMinutes < 60) {
      throw new IllegalArgumentException("Rental period must be at least 1 hour");
    }
    if (durationMinutes > 90 * 24 * 60) { // 90 days
      throw new IllegalArgumentException("Rental period cannot exceed 90 days");
    }
    
    // Geospatial validation (if provided, both must be present)
    if ((latitude == null && longitude != null) || (latitude != null && longitude == null)) {
      throw new IllegalArgumentException("Both latitude and longitude must be provided together");
    }
    if (latitude != null && (latitude < -90 || latitude > 90)) {
      throw new IllegalArgumentException("Invalid latitude: must be between -90 and 90");
    }
    if (longitude != null && (longitude < -180 || longitude > 180)) {
      throw new IllegalArgumentException("Invalid longitude: must be between -180 and 180");
    }
    if (radiusKm != null && (radiusKm < 1 || radiusKm > 500)) {
      throw new IllegalArgumentException("Radius must be between 1 and 500 km");
    }
    
    // Filter validation
    if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
      throw new IllegalArgumentException("minPrice cannot be greater than maxPrice");
    }
    if (minYear != null && maxYear != null && minYear > maxYear) {
      throw new IllegalArgumentException("minYear cannot be greater than maxYear");
    }
    
    // Pagination validation
    if (page < 0) {
      throw new IllegalArgumentException("page must be >= 0");
    }
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("size must be between 1 and 100");
    }
  }
  
  /**
   * Check if any filters are active (non-null values)
   */
  public boolean hasActiveFilters() {
    return minPrice != null || maxPrice != null ||
           make != null || model != null ||
           minYear != null || maxYear != null ||
           minSeats != null || transmission != null ||
           (features != null && !features.isEmpty());
  }
  
  /**
   * Check if geospatial search should be used
   */
  public boolean isGeospatialSearch() {
    return latitude != null && longitude != null;
  }
}
```

**Verification**:
- Maven compilation succeeds: `mvn clean compile 2>&1 | tail -20`
- No errors about missing properties or validation annotations

---

### 3.2 Update AvailabilityService.searchAvailableCars()

**File**: `Rentoza/src/main/java/org/example/rentoza/car/AvailabilityService.java`

**Action**: Find and replace the searchAvailableCars() method

**New Implementation**:
```java
/**
 * Search for available cars matching location + time range + optional filters.
 * 
 * Supports two location modes:
 * 1. Geospatial: Use latitude/longitude + radius for proximity search
 * 2. Legacy: Use location string matching (backward compatible)
 * 
 * Filters are applied server-side for performance.
 * 
 * @param request Availability search request with all parameters
 * @param pageable Pagination information
 * @return Paginated cars available for rental in specified time range
 */
public Page<Car> searchAvailableCars(AvailabilitySearchRequestDTO request, Pageable pageable) {
  // Validate request
  request.validate();
  
  List<Car> candidates;
  
  // 1. Find cars by location (geospatial vs. legacy string search)
  if (request.isGeospatialSearch()) {
    // GEOSPATIAL: Find cars within radius of coordinates
    candidates = carRepository.findNearby(
      request.getLatitude(),
      request.getLongitude(),
      request.getRadiusKm()
    );
  } else {
    // LEGACY: String-based location matching
    candidates = carRepository.findByLocationIgnoreCaseAndAvailableTrue(request.getLocation());
  }
  
  // 2. Filter out unavailable cars (booking conflicts, blocked dates)
  List<Car> availableCars = candidates.stream()
    .filter(car -> !isOverlappingBooking(car.getId(), request.getStartTime(), request.getEndTime()))
    .filter(car -> !hasBlockedDate(car.getId(), request.getStartTime(), request.getEndTime()))
    .collect(Collectors.toList());
  
  // 3. Apply additional filters if provided
  if (request.hasActiveFilters()) {
    availableCars = applySearchFilters(availableCars, request);
  }
  
  // 4. Apply sorting
  if (request.getSort() != null && !request.getSort().isEmpty()) {
    availableCars = applySorting(availableCars, request.getSort());
  }
  
  // 5. Apply pagination (manual since we filtered in memory)
  int start = (int) pageable.getOffset();
  int end = Math.min(start + pageable.getPageSize(), availableCars.size());
  List<Car> paged = availableCars.subList(start, Math.min(end, availableCars.size()));
  
  return new PageImpl<>(paged, pageable, availableCars.size());
}

/**
 * Apply search filters (price, make, model, year, seats, transmission, features)
 * 
 * @param cars Cars to filter
 * @param request Request with filter values
 * @return Filtered cars
 */
private List<Car> applySearchFilters(List<Car> cars, AvailabilitySearchRequestDTO request) {
  return cars.stream()
    .filter(car -> matchesPrice(car, request.getMinPrice(), request.getMaxPrice()))
    .filter(car -> matchesMake(car, request.getMake()))
    .filter(car -> matchesModel(car, request.getModel()))
    .filter(car -> matchesYear(car, request.getMinYear(), request.getMaxYear()))
    .filter(car -> matchesSeats(car, request.getMinSeats()))
    .filter(car -> matchesTransmission(car, request.getTransmission()))
    .filter(car -> matchesFeatures(car, request.getFeatures()))
    .collect(Collectors.toList());
}

private boolean matchesPrice(Car car, BigDecimal minPrice, BigDecimal maxPrice) {
  if (minPrice != null && car.getPricePerDay().compareTo(minPrice) < 0) {
    return false;
  }
  if (maxPrice != null && car.getPricePerDay().compareTo(maxPrice) > 0) {
    return false;
  }
  return true;
}

private boolean matchesMake(Car car, String make) {
  if (make == null || make.isEmpty()) {
    return true;
  }
  String normalizedMake = normalizeSearchString(make);
  String carBrand = normalizeSearchString(car.getBrand());
  return carBrand.contains(normalizedMake);
}

private boolean matchesModel(Car car, String model) {
  if (model == null || model.isEmpty()) {
    return true;
  }
  String normalizedModel = normalizeSearchString(model);
  String carModel = normalizeSearchString(car.getModel());
  return carModel.contains(normalizedModel);
}

private boolean matchesYear(Car car, Integer minYear, Integer maxYear) {
  if (minYear != null && car.getYear() < minYear) {
    return false;
  }
  if (maxYear != null && car.getYear() > maxYear) {
    return false;
  }
  return true;
}

private boolean matchesSeats(Car car, Integer minSeats) {
  if (minSeats == null) {
    return true;
  }
  Integer carSeats = car.getSeats();
  return carSeats != null && carSeats >= minSeats;
}

private boolean matchesTransmission(Car car, String transmission) {
  if (transmission == null || transmission.isEmpty()) {
    return true;
  }
  return car.getTransmissionType() != null && 
         car.getTransmissionType().name().equalsIgnoreCase(transmission);
}

private boolean matchesFeatures(Car car, List<String> requiredFeatures) {
  if (requiredFeatures == null || requiredFeatures.isEmpty()) {
    return true;
  }
  List<String> carFeatures = car.getFeatures();
  if (carFeatures == null || carFeatures.isEmpty()) {
    return false;
  }
  // All required features must be present
  Set<String> carFeaturesSet = new HashSet<>(carFeatures);
  return carFeaturesSet.containsAll(requiredFeatures);
}

private List<Car> applySorting(List<Car> cars, String sort) {
  // Implement sorting based on sort parameter
  // Examples: "price_asc", "price_desc", "year_desc", "year_asc", "relevance"
  return cars; // TODO: Implement sorting logic
}

/**
 * Check for overlapping bookings in the requested time range
 * 
 * @param carId Car ID to check
 * @param startTime Requested start time
 * @param endTime Requested end time
 * @return true if car has overlapping booking, false if available
 */
private boolean isOverlappingBooking(Long carId, LocalDateTime startTime, LocalDateTime endTime) {
  // Query bookings that overlap with [startTime, endTime]
  // Overlap occurs if: booking.startTime < endTime AND booking.endTime > startTime
  List<Booking> overlappingBookings = bookingRepository.findByCarIdAndTimeRangeOverlap(
    carId,
    startTime,
    endTime
  );
  return !overlappingBookings.isEmpty();
}

/**
 * Check if car has blocked dates in the requested time range
 * 
 * @param carId Car ID to check
 * @param startTime Requested start time
 * @param endTime Requested end time
 * @return true if car has blocked dates, false otherwise
 */
private boolean hasBlockedDate(Long carId, LocalDateTime startTime, LocalDateTime endTime) {
  // Query blocked dates that overlap with [startTime, endTime]
  List<BlockedDate> blockedDates = blockedDateRepository.findByCarIdAndDateRangeOverlap(
    carId,
    startTime,
    endTime
  );
  return !blockedDates.isEmpty();
}

/**
 * Normalize search string for accent-insensitive matching
 */
private String normalizeSearchString(String text) {
  if (text == null) {
    return "";
  }
  return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
    .replaceAll("[^\\p{ASCII}]", "")
    .toLowerCase();
}
```

**Verification**:
- Method compiles
- All helper methods referenced (matchesPrice, etc.) present or created
- Request validation called before processing

---

### 3.3 Update CarController /availability-search Endpoint

**File**: `Rentoza/src/main/java/org/example/rentoza/car/CarController.java`

**Location**: Existing /availability-search endpoint (around lines 189-240)

**Action**: Replace entire endpoint method

**New Implementation**:
```java
/**
 * Search for available cars with optional filters.
 * 
 * Supports geospatial search (lat/lng/radiusKm) or legacy string-based location matching.
 * Backend applies all filters server-side for performance.
 * 
 * @param location Location string (required, used if geospatial params not provided)
 * @param latitude Latitude for geospatial search (optional)
 * @param longitude Longitude for geospatial search (optional)
 * @param radiusKm Search radius in kilometers (default: 25)
 * @param startTime ISO-8601 datetime for rental start
 * @param endTime ISO-8601 datetime for rental end
 * @param minPrice Minimum daily price (optional)
 * @param maxPrice Maximum daily price (optional)
 * @param make Car brand/make (optional, partial matching)
 * @param model Car model (optional, partial matching)
 * @param minYear Minimum year (optional)
 * @param maxYear Maximum year (optional)
 * @param minSeats Minimum number of seats (optional)
 * @param transmission Transmission type: AUTOMATIC or MANUAL (optional)
 * @param features Comma-separated feature names (optional)
 * @param page Page number (0-indexed, default: 0)
 * @param size Page size (default: 20, max: 100)
 * @param sort Sort order (optional)
 * @return Paginated available cars
 */
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
    @RequestParam(required = false) String features, // Comma-separated
    
    // Pagination & Sorting
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size,
    @RequestParam(required = false) String sort
) {
  // Validate pagination params
  if (size < 1 || size > 100) {
    throw new IllegalArgumentException("Page size must be between 1 and 100");
  }
  if (page < 0) {
    throw new IllegalArgumentException("Page must be >= 0");
  }
  
  // Parse features from comma-separated string to list
  List<String> featuresList = null;
  if (features != null && !features.isEmpty()) {
    featuresList = Arrays.asList(features.split(","));
  }
  
  // Build request DTO
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
  request.setFeatures(featuresList);
  request.setPage(page);
  request.setSize(size);
  request.setSort(sort);
  
  // Validate request
  request.validate();
  
  // Create pageable
  Pageable pageable = PageRequest.of(page, size);
  
  // Execute search
  Page<Car> results = availabilityService.searchAvailableCars(request, pageable);
  
  // Map to DTO
  Page<CarDto> dtoResults = results.map(carDtoMapper::toDto);
  
  return ResponseEntity.ok(dtoResults);
}
```

**Verification**:
- Method compiles
- AvailabilitySearchRequestDTO imported
- availabilityService dependency injected
- carDtoMapper dependency injected

---

### 3.4 Compile and Test Backend

**Commands**:
```bash
cd Rentoza
mvn clean compile 2>&1 | tail -50
mvn test -Dtest=AvailabilityServiceTest 2>&1 | tail -30
mvn clean package -DskipTests 2>&1 | tail -20
```

**Expected**:
- Compilation succeeds
- Tests pass (or existing tests don't fail due to new code)
- JAR builds successfully

---

## PHASE 4: INTEGRATION TESTING

### 4.1 Manual End-to-End Test

**Scenario 1: Availability Search with Geospatial Coordinates**

1. Navigate to `/cars` page
2. Enter location: "Beograd"
3. Wait for suggestions, select "Beograd" from dropdown
4. Verify `selectedGeocodeSuggestion` has latitude/longitude (console.log)
5. Select dates/times
6. Click "Pretraži dostupnost"
7. Verify URL contains: `&lat=44.xxx&lng=20.xxx&radiusKm=25`
8. Verify results are returned
9. Open filter dialog, set `make=BMW`, `minPrice=100`
10. Click "Prikaži rezultate"
11. Verify URL updated with: `&make=BMW&minPrice=100`
12. Verify results filtered to BMW cars >= 100 RSD/day
13. Refresh page → verify state fully restored
14. Clear filters → verify filters removed from URL

**Scenario 2: Standard Search Mode Still Works**

1. Navigate to `/cars` page
2. Don't enter location (skip geospatial search)
3. Open filter dialog
4. Set `make=Audi`, `maxPrice=200`
5. Click "Prikaži rezultate"
6. Verify results are client-side filtered (no availability mode)
7. Verify URL contains only: `&make=Audi&maxPrice=200`
8. Verify no lat/lng in URL

**Scenario 3: Filter Application in Availability Mode**

1. Start availability search with location + dates/times (no filters)
2. Results display
3. Open filter dialog, set `transmission=AUTOMATIC`
4. Click "Prikaži rezultate"
5. Verify:
   - URL updated with `&transmission=AUTOMATIC`
   - availabilityParams$ merged filters
   - Backend receives transmission parameter
   - Results filtered to automatic cars only
   - Page count updated correctly

---

### 4.2 Backend API Testing (cURL)

**Test: Availability Search with Geospatial + Filters**

```bash
curl -X GET \
  "http://localhost:8080/api/cars/availability-search?location=Beograd&latitude=44.816666&longitude=20.458889&radiusKm=25&startTime=2025-12-10T09:00:00&endTime=2025-12-10T18:00:00&minPrice=100&maxPrice=500&make=BMW&transmission=AUTOMATIC&page=0&size=20" \
  -H "Accept: application/json"
```

**Expected Response**:
```json
{
  "content": [
    {
      "id": "1",
      "brand": "BMW",
      "model": "320",
      "year": 2023,
      "pricePerDay": 250,
      "transmissionType": "AUTOMATIC",
      "available": true,
      "location": "Beograd"
    }
  ],
  "totalElements": 5,
  "totalPages": 1,
  "currentPage": 0,
  "pageSize": 20,
  "hasNext": false,
  "hasPrevious": false
}
```

**Test: Legacy Location String Search (backward compatibility)**

```bash
curl -X GET \
  "http://localhost:8080/api/cars/availability-search?location=Beograd&startTime=2025-12-10T09:00:00&endTime=2025-12-10T18:00:00&page=0&size=20" \
  -H "Accept: application/json"
```

**Expected**: Same response (finds cars by string location matching)

---

### 4.3 Frontend Component Unit Tests

**File**: `rentoza-frontend/src/app/features/cars/pages/car-list/car-list.component.spec.ts`

**Add Tests**:
```typescript
describe('Car List - Filters Integration', () => {
  it('should merge filters into availabilityParams when in availability mode', (done) => {
    component.isAvailabilityMode$.next(true);
    component.availabilityParams$.next({
      location: 'Beograd',
      latitude: 44.8,
      longitude: 20.4,
      radiusKm: 25,
      startDate: '',
      startTime: '2025-12-10T09:00:00',
      endDate: '',
      endTime: '2025-12-10T18:00:00',
      page: 0,
      size: 20,
    });
    
    const criteria: CarSearchCriteria = {
      minPrice: 100,
      maxPrice: 500,
      make: 'BMW',
    };
    
    component.onFiltersChanged(criteria);
    
    expect(component.availabilityParams$.value).toEqual(jasmine.objectContaining({
      minPrice: 100,
      maxPrice: 500,
      make: 'BMW',
      location: 'Beograd',
      latitude: 44.8,
      longitude: 20.4,
    }));
    done();
  });
  
  it('should include coordinates in URL when updating availability params', (done) => {
    spyOn(component['router'], 'navigate');
    
    component.availabilityParams$.next({
      location: 'Beograd',
      latitude: 44.816666,
      longitude: 20.458889,
      radiusKm: 25,
      startDate: '',
      startTime: '2025-12-10T09:00:00',
      endDate: '',
      endTime: '2025-12-10T18:00:00',
      page: 0,
      size: 20,
    });
    
    component['updateUrlParamsForAvailability'](component.availabilityParams$.value!);
    
    expect(component['router'].navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: jasmine.objectContaining({
          lat: '44.816666',
          lng: '20.458889',
          radiusKm: 25,
        }),
      })
    );
    done();
  });
});
```

**Run Tests**:
```bash
cd rentoza-frontend
ng test --include="**/car-list.component.spec.ts" --watch=false
```

---

## PHASE 5: PRODUCTION VERIFICATION

### 5.1 Build & Deployment Checklist

- [ ] Frontend builds without errors: `ng build --configuration production`
- [ ] Backend builds without errors: `mvn clean package`
- [ ] No console errors in browser DevTools
- [ ] Network tab shows geospatial parameters in /availability-search requests
- [ ] Performance: Query responds in <500ms for typical searches
- [ ] No regressions: Existing tests pass
- [ ] Documentation updated in code (JSDoc/JavaDoc comments)

### 5.2 Monitoring Post-Deployment

- [ ] Error rate for /availability-search endpoint remains <0.1%
- [ ] Average response time <300ms
- [ ] No database query timeout errors
- [ ] Filters correctly applied: verify via manual testing

### 5.3 Rollback Plan (if issues detected)

If geospatial search breaks:
1. Deploy previous frontend version (uses old searchAvailableCars signature)
2. Backend remains compatible (old parameters still work)
3. Availability searches fall back to location string matching only

---

## File Checklist

| File | Status | Changes |
|------|--------|---------|
| `car-search.model.ts` | UPDATE | Add `AvailabilitySearchParams` interface |
| `car-list.component.ts` | UPDATE | Add state helpers, update methods |
| `car.service.ts` | UPDATE | New `searchAvailableCars()` signature |
| `AvailabilitySearchRequestDTO.java` | CREATE/UPDATE | Add geospatial + filter fields |
| `AvailabilityService.java` | UPDATE | Add filter application logic |
| `CarController.java` | UPDATE | New endpoint parameters |
| `car-list.component.spec.ts` | UPDATE | Add integration tests |

---

## Success Criteria (Sign-Off)

✅ **Must Have**:
1. Filters apply correctly in availability mode
2. URL persistence works with coordinates + filters
3. Page refresh restores full state
4. No regressions in standard search mode
5. Backend receives all parameters

✅ **Should Have**:
1. Performance: <300ms response time
2. Mobile UX: <500KB payload
3. Documentation updated

✅ **Nice to Have**:
1. Distance-based sorting
2. Radius slider in car-list
3. Rate limiting on endpoint

---

## Estimated Timeline

- **Frontend Changes**: 1.5 hours (state management + service)
- **Backend Changes**: 1.5 hours (DTO + service + controller)
- **Testing**: 1 hour (unit + integration + manual)
- **Documentation**: 0.5 hours (code comments + README)

**Total**: 4-5 hours

---

## References

- Full Plan: `/Users/kljaja01/Developer/Rentoza/docs/GEOSPATIAL_FILTERS_RECONNECTION_PLAN.md`
- Current Implementation: car-list.component.ts, CarService
- Backend: AvailabilityService, CarController
- Geospatial Pattern: home.component.ts

